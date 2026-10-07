import type { JobPriority, JobStatus, WorkerStatus } from '../types';

/**
 * Normalized /api/v1/metrics view. The SPEC §8 lists the metric families
 * (counts by status, queue depth per priority, workers by status, avg/max
 * duration, throughput per hour) but not the exact JSON keys, so this parser
 * accepts camelCase, snake_case and a few reasonable aliases (verified against
 * the live backend: jobsByStatus, queueDepthByPriority, workersByStatus,
 * succeededLast24h{count,avgDurationSeconds,maxDurationSeconds},
 * completedPerHourLast24h). Anything the API does not provide stays undefined
 * — the UI renders only real numbers.
 */
export interface Metrics {
  jobsByStatus: Partial<Record<JobStatus, number>>;
  queueDepth: Partial<Record<JobPriority, number>>;
  workersByStatus: Partial<Record<WorkerStatus, number>>;
  avgDurationMs?: number;
  maxDurationMs?: number;
  /** succeeded jobs in the last 24h (scalar) */
  succeededLast24h?: number;
  /** hourly completion counts, when the API publishes a series */
  throughputPerHour?: number[];
  /** average completed per hour over the last 24h, when the API publishes a scalar */
  throughputPerHourScalar?: number;
}

type UnknownRecord = Record<string, unknown>;

function asRecord(v: unknown): UnknownRecord | null {
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as UnknownRecord) : null;
}

function pickRecord(source: UnknownRecord, keys: string[]): UnknownRecord | undefined {
  for (const key of keys) {
    const rec = asRecord(source[key]);
    if (rec) return rec;
  }
  return undefined;
}

function pickNumber(source: UnknownRecord, keys: string[]): number | undefined {
  for (const key of keys) {
    const v = source[key];
    if (typeof v === 'number' && Number.isFinite(v)) return v;
  }
  return undefined;
}

function pickArray(source: UnknownRecord, keys: string[]): number[] | undefined {
  for (const key of keys) {
    const v = source[key];
    if (Array.isArray(v) && v.every((n) => typeof n === 'number' && Number.isFinite(n)) && v.length > 0) return v;
  }
  return undefined;
}

function countsToPartial(rec: UnknownRecord | undefined, keys: readonly string[]): Partial<Record<string, number>> {
  if (!rec) return {};
  const out: Partial<Record<string, number>> = {};
  for (const key of keys) {
    const lower = key.toLowerCase();
    for (const [rawKey, rawValue] of Object.entries(rec)) {
      if (rawKey.toLowerCase().replace(/[-\s]/g, '_') === lower && typeof rawValue === 'number' && Number.isFinite(rawValue)) {
        out[key] = rawValue;
        break;
      }
    }
  }
  return out;
}

/** Duration in ms from either a ms-valued or a seconds-valued key family. */
function durationMs(
  source: UnknownRecord,
  msKeys: string[],
  secKeys: string[],
): number | undefined {
  const ms = pickNumber(source, msKeys);
  if (ms != null) return ms;
  const sec = pickNumber(source, secKeys);
  if (sec != null) return sec * 1000;
  return undefined;
}

export function parseMetrics(raw: unknown): Metrics {
  const src = asRecord(raw) ?? {};
  const jobsByStatusRaw = pickRecord(src, ['jobsByStatus', 'jobs_by_status', 'jobStatusCounts', 'statusCounts', 'jobs']);
  const queueRaw = pickRecord(src, ['queueDepth', 'queue_depth', 'queueDepthByPriority', 'queue']);
  const workersRaw = pickRecord(src, ['workersByStatus', 'workers_by_status', 'workerStatusCounts', 'workers']);

  // durations + 24h count: nested in succeededLast24h when present, top-level otherwise
  const last24 = pickRecord(src, ['succeededLast24h', 'succeeded_last_24h', 'last24h', 'succeeded24h']);
  const durationSource = last24 ?? src;
  const avgDurationMs = durationMs(
    durationSource,
    ['avgDurationMs', 'avg_duration_ms', 'avgDuration'],
    ['avgDurationSeconds', 'avg_duration_seconds', 'avgDurationS'],
  );
  const maxDurationMs = durationMs(
    durationSource,
    ['maxDurationMs', 'max_duration_ms', 'maxDuration'],
    ['maxDurationSeconds', 'max_duration_seconds', 'maxDurationS'],
  );
  const succeededLast24h = last24 != null
    ? pickNumber(last24, ['count', 'succeeded', 'completed'])
    : pickNumber(src, ['completedLast24h', 'completed_last_24h', 'succeededLast24h']);

  const throughputPerHour = pickArray(src, ['throughputPerHour', 'throughput_per_hour', 'throughput']);
  const throughputPerHourScalar = pickNumber(src, [
    'completedPerHourLast24h',
    'completed_per_hour_last_24h',
    'completedPerHour',
    'completed_per_hour',
    'throughputPerHourScalar',
  ]);

  return {
    jobsByStatus: countsToPartial(jobsByStatusRaw, ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'RETRYING', 'TIMED_OUT', 'CANCELLED']) as Partial<Record<JobStatus, number>>,
    queueDepth: countsToPartial(queueRaw, ['CRITICAL', 'HIGH', 'NORMAL', 'LOW']) as Partial<Record<JobPriority, number>>,
    workersByStatus: countsToPartial(workersRaw, ['STARTING', 'IDLE', 'BUSY', 'DRAINING', 'OFFLINE', 'ERROR']) as Partial<Record<WorkerStatus, number>>,
    avgDurationMs,
    maxDurationMs,
    succeededLast24h,
    throughputPerHour,
    throughputPerHourScalar,
  };
}

export function sumQueueDepth(m: Metrics | undefined): number {
  if (!m) return 0;
  return (m.queueDepth.CRITICAL ?? 0) + (m.queueDepth.HIGH ?? 0) + (m.queueDepth.NORMAL ?? 0) + (m.queueDepth.LOW ?? 0);
}

export function workersOnline(m: Metrics | undefined): number {
  if (!m) return 0;
  const { OFFLINE, ERROR, ...rest } = m.workersByStatus;
  void OFFLINE;
  void ERROR;
  return Object.values(rest).reduce<number>((acc, v) => acc + (v ?? 0), 0);
}
