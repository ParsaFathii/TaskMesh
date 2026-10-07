import type { WsFrame } from '../types';
import type { ConnStatus } from '../stores/connection';

export const BASE_DELAY_MS = 1_000;
export const MAX_DELAY_MS = 30_000;
export const STALE_MS = 30_000;
export const JITTER_RATIO = 0.2;

export interface SocketHandlers {
  onFrame(frame: WsFrame): void;
  onStatus(status: ConnStatus, attempt: number): void;
}

/**
 * Exponential backoff with jitter: 1s, 2s, 4s, … capped at 30s, ±20% jitter.
 * `rand` is injectable for deterministic tests.
 */
export function backoffDelay(attempt: number, rand: () => number = Math.random): number {
  const exp = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** Math.max(0, attempt - 1));
  const jitter = exp * JITTER_RATIO * (rand() * 2 - 1);
  return Math.max(500, Math.round(exp + jitter));
}

/** Build the events WS URL (SPEC §8: /ws/v1/events?token=<JWT>). */
export function wsEventsUrl(token: string, loc: { protocol: string; host: string }): string {
  const proto = loc.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${loc.host}/ws/v1/events?token=${encodeURIComponent(token)}`;
}

/**
 * WebSocket connection manager for the TaskMesh event stream.
 *
 * - parses raw JSON text frames (silently drops malformed ones)
 * - reconnects with exponential backoff + jitter after connection loss
 * - stale detection: no frame within 30s → force reconnect
 * - close() is final (component unmount); reconnect is never scheduled after
 */
export class EventSocket {
  private ws: WebSocket | null = null;
  private attempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private staleTimer: ReturnType<typeof setTimeout> | null = null;
  private userClosed = false;

  constructor(
    private readonly url: string,
    private readonly handlers: SocketHandlers,
    private readonly staleMs: number = STALE_MS,
  ) {}

  connect(): void {
    if (this.userClosed || this.ws) return;
    this.handlers.onStatus(this.attempt === 0 ? 'connecting' : 'reconnecting', this.attempt);

    let ws: WebSocket;
    try {
      ws = new WebSocket(this.url);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.ws = ws;

    ws.onopen = () => {
      this.attempt = 0;
      this.handlers.onStatus('open', 0);
      this.armStale();
    };

    ws.onmessage = (ev: MessageEvent) => {
      this.armStale();
      const frame = parseFrame(ev.data);
      if (frame) this.handlers.onFrame(frame);
    };

    ws.onclose = () => {
      this.teardownSocket();
      if (this.userClosed) this.handlers.onStatus('closed', 0);
      else this.scheduleReconnect();
    };

    ws.onerror = () => {
      /* the browser always follows with onclose */
    };
  }

  /** Final close — never reconnects. */
  close(): void {
    this.userClosed = true;
    if (this.reconnectTimer != null) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    const ws = this.ws;
    this.teardownSocket();
    ws?.close();
    this.handlers.onStatus('closed', 0);
  }

  get attemptCount(): number {
    return this.attempt;
  }

  get isOpen(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  private armStale(): void {
    if (this.staleTimer != null) clearTimeout(this.staleTimer);
    this.staleTimer = setTimeout(() => {
      // silent stream for 30s — force a reconnect cycle
      this.ws?.close();
    }, this.staleMs);
  }

  private teardownSocket(): void {
    if (this.ws != null) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onclose = null;
      this.ws.onerror = null;
      this.ws = null;
    }
    if (this.staleTimer != null) {
      clearTimeout(this.staleTimer);
      this.staleTimer = null;
    }
  }

  private scheduleReconnect(): void {
    this.attempt += 1;
    const delay = backoffDelay(this.attempt);
    this.handlers.onStatus('reconnecting', this.attempt);
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, delay);
  }
}

/** Parse a raw WS text frame per SPEC §8; null when malformed. */
export function parseFrame(data: unknown): WsFrame | null {
  if (typeof data !== 'string' || data.length === 0) return null;
  try {
    const parsed: unknown = JSON.parse(data);
    if (parsed !== null && typeof parsed === 'object' && typeof (parsed as { type?: unknown }).type === 'string') {
      return parsed as WsFrame;
    }
    return null;
  } catch {
    return null;
  }
}
