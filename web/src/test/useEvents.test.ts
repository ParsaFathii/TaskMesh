import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, type QueryClient as QC } from '@tanstack/react-query';
import { EventSocket, backoffDelay, parseFrame, wsEventsUrl, STALE_MS, MAX_DELAY_MS } from '../ws/EventSocket';
import { dispatchFrame } from '../ws/dispatch';
import { useEventsStore } from '../stores/events';
import { useConnectionStore } from '../stores/connection';
import type { WsFrame } from '../types';

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  static OPEN = 1;
  url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closeSpy = vi.fn();

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  fireOpen(): void {
    this.readyState = 1;
    this.onopen?.();
  }

  fireMessage(data: string): void {
    this.onmessage?.({ data });
  }

  fireClose(): void {
    if (this.readyState === 3) return;
    this.readyState = 3;
    this.onclose?.();
  }

  close(): void {
    this.closeSpy();
    this.fireClose();
  }
}

function makeSocket(handlers: { onFrame: (f: WsFrame) => void; onStatus: (s: string, a: number) => void }) {
  return new EventSocket('ws://test/ws/v1/events?token=t', handlers);
}

describe('parseFrame (SPEC §8 JSON text frames)', () => {
  it('parses a valid job.updated frame', () => {
    const frame = parseFrame('{"type":"job.updated","jobId":"j1","status":"RUNNING","progress":42}');
    expect(frame).toEqual({ type: 'job.updated', jobId: 'j1', status: 'RUNNING', progress: 42 });
  });

  it('returns null for malformed JSON', () => {
    expect(parseFrame('not json at all')).toBeNull();
    expect(parseFrame('')).toBeNull();
  });

  it('returns null for JSON without a string type field', () => {
    expect(parseFrame('{"nope":1}')).toBeNull();
    expect(parseFrame('{"type":42}')).toBeNull();
    expect(parseFrame('[]')).toBeNull();
  });

  it('returns null for non-string payloads', () => {
    expect(parseFrame(undefined)).toBeNull();
    expect(parseFrame({ data: 1 } as unknown)).toBeNull();
  });
});

describe('backoffDelay', () => {
  it('follows the exponential schedule 1s→2s→4s… capped at 30s', () => {
    const noJitter = () => 0.5; // exactly base
    expect(backoffDelay(1, noJitter)).toBe(1000);
    expect(backoffDelay(2, noJitter)).toBe(2000);
    expect(backoffDelay(3, noJitter)).toBe(4000);
    expect(backoffDelay(4, noJitter)).toBe(8000);
    expect(backoffDelay(5, noJitter)).toBe(16000);
    expect(backoffDelay(6, noJitter)).toBe(30000); // 32000 capped
    expect(backoffDelay(20, noJitter)).toBe(MAX_DELAY_MS);
  });

  it('applies ±20% jitter', () => {
    expect(backoffDelay(1, () => 0)).toBe(800);
    expect(backoffDelay(1, () => 1)).toBe(1200);
    const d = backoffDelay(1);
    expect(d).toBeGreaterThanOrEqual(800);
    expect(d).toBeLessThanOrEqual(1200);
  });

  it('never returns less than 500ms', () => {
    expect(backoffDelay(1, () => -1)).toBeGreaterThanOrEqual(500);
  });
});

describe('wsEventsUrl', () => {
  it('builds ws:// on http and wss:// on https with the token query', () => {
    expect(wsEventsUrl('jwt-token', { protocol: 'http:', host: 'localhost:5173' })).toBe(
      'ws://localhost:5173/ws/v1/events?token=jwt-token',
    );
    expect(wsEventsUrl('jwt token', { protocol: 'https:', host: 'mesh.example' })).toBe(
      'wss://mesh.example/ws/v1/events?token=jwt%20token',
    );
  });
});

describe('EventSocket reconnect behaviour', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('connects, opens, and dispatches valid frames only', () => {
    const onFrame = vi.fn();
    const onStatus = vi.fn();
    const socket = makeSocket({ onFrame, onStatus });
    socket.connect();

    expect(FakeWebSocket.instances).toHaveLength(1);
    expect(FakeWebSocket.instances[0]!.url).toBe('ws://test/ws/v1/events?token=t');
    expect(onStatus).toHaveBeenCalledWith('connecting', 0);

    const ws = FakeWebSocket.instances[0]!;
    ws.fireOpen();
    expect(onStatus).toHaveBeenCalledWith('open', 0);

    ws.fireMessage('{"type":"job.log","jobId":"j1","level":"INFO","message":"hi"}');
    ws.fireMessage('garbage');
    ws.fireMessage('{"noType":true}');
    expect(onFrame).toHaveBeenCalledTimes(1);
    expect(onFrame).toHaveBeenCalledWith(expect.objectContaining({ type: 'job.log', jobId: 'j1' }));
    socket.close();
  });

  it('reconnects with backoff after the socket closes', () => {
    const onStatus = vi.fn();
    const socket = makeSocket({ onFrame: vi.fn(), onStatus });
    socket.connect();
    const ws = FakeWebSocket.instances[0]!;
    ws.fireOpen();
    ws.fireClose();

    expect(onStatus).toHaveBeenLastCalledWith('reconnecting', 1);
    expect(FakeWebSocket.instances).toHaveLength(1);

    vi.advanceTimersByTime(1500); // > 1s ± 20% for attempt 1
    expect(FakeWebSocket.instances).toHaveLength(2);

    FakeWebSocket.instances[1]!.fireOpen();
    expect(onStatus).toHaveBeenLastCalledWith('open', 0);
    socket.close();
  });

  it('escalates the backoff after repeated failures (no successful open)', () => {
    const onStatus = vi.fn();
    const socket = makeSocket({ onFrame: vi.fn(), onStatus });
    socket.connect();

    // The server is down: every socket closes without ever opening.
    for (let i = 0; i < 4; i++) {
      const ws = FakeWebSocket.instances[FakeWebSocket.instances.length - 1]!;
      ws.fireClose();
      vi.advanceTimersByTime(60_000); // far beyond any schedule
    }
    expect(onStatus).toHaveBeenLastCalledWith('reconnecting', 4);
    expect(FakeWebSocket.instances.length).toBeGreaterThanOrEqual(5);
    socket.close();
  });

  it('forces a reconnect when no frame arrives within the stale window', () => {
    const onStatus = vi.fn();
    const socket = makeSocket({ onFrame: vi.fn(), onStatus });
    socket.connect();
    const ws = FakeWebSocket.instances[0]!;
    ws.fireOpen();

    vi.advanceTimersByTime(STALE_MS);
    expect(ws.closeSpy).toHaveBeenCalledTimes(1);
    // close cascades into a reconnect attempt
    expect(onStatus).toHaveBeenLastCalledWith('reconnecting', 1);
    vi.advanceTimersByTime(1500);
    expect(FakeWebSocket.instances).toHaveLength(2);
    socket.close();
  });

  it('keeps the stale timer away while frames arrive', () => {
    const socket = makeSocket({ onFrame: vi.fn(), onStatus: vi.fn() });
    socket.connect();
    const ws = FakeWebSocket.instances[0]!;
    ws.fireOpen();

    for (let i = 0; i < 5; i++) {
      vi.advanceTimersByTime(10_000);
      ws.fireMessage('{"type":"hello"}');
    }
    expect(ws.closeSpy).not.toHaveBeenCalled();
    socket.close();
  });

  it('close() is final — no reconnection afterwards', () => {
    const onStatus = vi.fn();
    const socket = makeSocket({ onFrame: vi.fn(), onStatus });
    socket.connect();
    FakeWebSocket.instances[0]!.fireOpen();
    socket.close();

    expect(onStatus).toHaveBeenLastCalledWith('closed', 0);
    vi.advanceTimersByTime(120_000);
    expect(FakeWebSocket.instances).toHaveLength(1);
  });
});

describe('dispatchFrame side effects', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('job.updated flashes the row, feeds the ticker, and invalidates the caches', () => {
    const invalidate = vi.fn();
    const qc = { invalidateQueries: invalidate } as unknown as QC;
    useEventsStore.setState({ ticker: [], flashedAt: {}, logsByJob: {} });

    dispatchFrame({ type: 'job.updated', jobId: 'job-123', status: 'SUCCEEDED', progress: 100 }, qc);
    vi.advanceTimersByTime(1000);

    expect(useEventsStore.getState().flashedAt['job-123']).toBeTypeOf('number');
    expect(useEventsStore.getState().ticker.at(-1)?.kind).toBe('job.updated');
    expect(useEventsStore.getState().ticker.at(-1)?.text).toContain('job-123');
    expect(useEventsStore.getState().ticker.at(-1)?.text).toContain('SUCCEEDED');

    const keys = invalidate.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey));
    expect(keys).toContain(JSON.stringify(['job', 'job-123']));
    expect(keys).toContain(JSON.stringify(['jobs']));
    expect(keys).toContain(JSON.stringify(['metrics'])); // terminal transition
  });

  it('job.log appends to the live log buffer without cache invalidation', () => {
    const invalidate = vi.fn();
    const qc = { invalidateQueries: invalidate } as unknown as QueryClient;
    useEventsStore.setState({ ticker: [], flashedAt: {}, logsByJob: {} });

    dispatchFrame(
      { type: 'job.log', jobId: 'job-123', level: 'WARN', message: 'throttling', timestamp: '2026-01-01T10:00:08Z' },
      qc,
    );
    const logs = useEventsStore.getState().logsByJob['job-123'];
    expect(logs).toHaveLength(1);
    expect(logs[0]).toMatchObject({ jobId: 'job-123', level: 'WARN', message: 'throttling' });
    expect(invalidate).not.toHaveBeenCalled();
    expect(useEventsStore.getState().ticker.at(-1)?.kind).toBe('job.log');
  });

  it('worker.updated invalidates worker caches', () => {
    const invalidate = vi.fn();
    const qc = { invalidateQueries: invalidate } as unknown as QueryClient;
    dispatchFrame({ type: 'worker.updated', workerId: 'w1', status: 'BUSY', currentJobId: 'j9' }, qc);
    vi.advanceTimersByTime(1000);

    const keys = invalidate.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey));
    expect(keys).toContain(JSON.stringify(['workers']));
    expect(keys).toContain(JSON.stringify(['worker', 'w1']));
  });

  it('hello records the server id in the connection store', () => {
    const qc = { invalidateQueries: vi.fn() } as unknown as QueryClient;
    dispatchFrame({ type: 'hello', server: 'taskmesh-0.1.0' }, qc);
    expect(useConnectionStore.getState().server).toBe('taskmesh-0.1.0');
    expect(useConnectionStore.getState().lastEventAt).toBeTypeOf('number');
  });

  it('ignores unknown frame types gracefully', () => {
    const qc = { invalidateQueries: vi.fn() } as unknown as QueryClient;
    expect(() => dispatchFrame({ type: 'mystery.event' }, qc)).not.toThrow();
    expect(useEventsStore.getState().ticker.at(-1)?.kind).toBe('mystery.event');
  });
});
