import type { QueryClient } from '@tanstack/react-query';
import type { LogLevel, TickerTone, WsFrame } from '../types';
import { TERMINAL_JOB_STATUSES } from '../types';
import { shortId } from '../lib/format';
import { useEventsStore, selectJobLogs } from '../stores/events';
import { useConnectionStore } from '../stores/connection';
import { logLevelVisual } from '../lib/status';

/**
 * Route an incoming WS frame (SPEC §8):
 *  - feed the ticker / row-flash / live-log stores (direct streams)
 *  - invalidate TanStack Query caches (job.updated → jobs + job detail,
 *    worker.updated → workers, terminal transitions → metrics)
 * List queries are invalidated on a short debounce to survive event bursts.
 */

const pending = new Map<string, ReturnType<typeof setTimeout>>();

function invalidateDebounced(qc: QueryClient, key: readonly unknown[], ms = 400): void {
  const k = JSON.stringify(key);
  const existing = pending.get(k);
  if (existing != null) clearTimeout(existing);
  pending.set(
    k,
    setTimeout(() => {
      pending.delete(k);
      void qc.invalidateQueries({ queryKey: key });
    }, ms),
  );
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}

function tickerToneForLevel(level: LogLevel | undefined): TickerTone {
  if (!level) return 'dim';
  switch (level) {
    case 'ERROR': return 'rose';
    case 'WARN': return 'yellow';
    case 'INFO': return 'teal';
    default: return 'dim';
  }
}

export function dispatchFrame(frame: WsFrame, qc: QueryClient): void {
  const events = useEventsStore.getState();
  const conn = useConnectionStore.getState();
  conn.noteEvent();

  switch (frame.type) {
    case 'job.updated': {
      const jobId = str(frame.jobId);
      if (!jobId) return;
      const status = str(frame.status);
      const progress = typeof frame.progress === 'number' ? frame.progress : null;
      events.pushTicker({
        at: Date.now(),
        kind: 'job.updated',
        tone: 'amber',
        text: `${shortId(jobId)} · ${status ?? '?'}${progress != null ? ` · ${progress}%` : ''}`,
        jobId,
      });
      events.flashJob(jobId);
      invalidateDebounced(qc, ['job', jobId], 300);
      invalidateDebounced(qc, ['jobs']);
      if (status && (TERMINAL_JOB_STATUSES as readonly string[]).includes(status)) {
        invalidateDebounced(qc, ['metrics']);
      }
      break;
    }

    case 'job.log': {
      const jobId = str(frame.jobId);
      if (!jobId) return;
      const levelRaw = str(frame.level);
      const level: LogLevel = levelRaw === 'DEBUG' || levelRaw === 'WARN' || levelRaw === 'ERROR' ? levelRaw : 'INFO';
      const message = typeof frame.message === 'string' ? frame.message : '';
      const createdAt = str(frame.timestamp) ?? new Date().toISOString();
      events.appendJobLog({ jobId, level, message, createdAt, workerId: null });
      events.pushTicker({
        at: Date.now(),
        kind: 'job.log',
        tone: tickerToneForLevel(level),
        text: `${shortId(jobId)} · ${level} · ${message.slice(0, 72)}`,
        jobId,
      });
      break;
    }

    case 'worker.updated': {
      const workerId = str(frame.workerId);
      if (!workerId) return;
      const status = str(frame.status);
      const currentJobId = str(frame.currentJobId);
      events.pushTicker({
        at: Date.now(),
        kind: 'worker.updated',
        tone: 'emerald',
        text: `${shortId(workerId)} · ${status ?? '?'}${currentJobId ? ` · job ${shortId(currentJobId)}` : ''}`,
        workerId,
      });
      invalidateDebounced(qc, ['workers']);
      invalidateDebounced(qc, ['worker', workerId], 300);
      invalidateDebounced(qc, ['metrics']);
      break;
    }

    case 'queue.event': {
      const jobId = str(frame.jobId);
      const event = str(frame.event);
      events.pushTicker({
        at: Date.now(),
        kind: 'queue.event',
        tone: 'mute',
        text: `${event ?? 'event'}${jobId ? ` · ${shortId(jobId)}` : ''}`,
        jobId,
      });
      invalidateDebounced(qc, ['jobs']);
      invalidateDebounced(qc, ['metrics']);
      break;
    }

    case 'hello': {
      const server = str(frame.server);
      if (server) useConnectionStore.getState().setServer(server);
      events.pushTicker({
        at: Date.now(),
        kind: 'hello',
        tone: 'teal',
        text: `stream connected${server ? ` · ${server}` : ''}`,
      });
      break;
    }

    default: {
      events.pushTicker({
        at: Date.now(),
        kind: frame.type,
        tone: 'dim',
        text: frame.type,
      });
    }
  }
}

/** Live log lines for a job, as a stable selector. */
export { selectJobLogs };

export function levelToneClass(level: LogLevel): string {
  return logLevelVisual[level].text;
}
