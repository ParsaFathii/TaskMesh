import { vi } from 'vitest';
import type { Attempt, Job, JobLog, Worker } from '../types';

export const JOB_RUNNING: Job = {
  id: '11111111-2222-3333-4444-555555555555',
  projectId: 'p-00000000-0000-0000-0000-000000000001',
  ownerId: 'u-00000000-0000-0000-0000-000000000001',
  type: 'cpu_benchmark',
  priority: 'HIGH',
  payload: { workload: 'primes', durationSeconds: 5 },
  status: 'RUNNING',
  createdAt: '2026-01-01T10:00:00Z',
  queuedAt: '2026-01-01T10:00:01Z',
  startedAt: '2026-01-01T10:00:05Z',
  completedAt: null,
  retryCount: 0,
  maxRetries: 3,
  timeoutSeconds: 120,
  workerId: 'w-11111111-1111-1111-1111-111111111111',
  worker: { id: 'w-11111111-1111-1111-1111-111111111111', name: 'worker-01', status: 'BUSY' },
  progress: 42,
  cancelRequested: false,
  availableAt: '2026-01-01T10:00:00Z',
  leaseExpiresAt: '2026-01-01T10:02:05Z',
  lastError: null,
};

export const JOB_SUCCEEDED: Job = {
  ...JOB_RUNNING,
  id: '22222222-3333-4444-5555-666666666666',
  status: 'SUCCEEDED',
  startedAt: '2026-01-01T10:00:05Z',
  completedAt: '2026-01-01T10:00:09Z',
  progress: 100,
  workerId: 'w-11111111-1111-1111-1111-111111111111',
  leaseExpiresAt: null,
};

export const JOB_FAILED: Job = {
  ...JOB_RUNNING,
  id: '33333333-4444-5555-6666-777777777777',
  status: 'FAILED',
  completedAt: '2026-01-01T10:00:20Z',
  lastError: 'workload crashed: simulated fault',
  workerId: null,
  worker: null,
  progress: null,
};

export const JOB_LOW: Job = {
  ...JOB_RUNNING,
  id: '44444444-5555-6666-7777-888888888888',
  priority: 'LOW',
  status: 'QUEUED',
  startedAt: null,
  workerId: null,
  worker: null,
  progress: null,
};

export const JOB_CRITICAL: Job = {
  ...JOB_RUNNING,
  id: '55555555-6666-7777-8888-999999999999',
  priority: 'CRITICAL',
  status: 'QUEUED',
  startedAt: null,
  workerId: null,
  worker: null,
  progress: null,
};

export const JOB_NORMAL: Job = {
  ...JOB_RUNNING,
  id: '66666666-7777-8888-9999-aaaaaaaaaaaa',
  priority: 'NORMAL',
  status: 'QUEUED',
  startedAt: null,
  workerId: null,
  worker: null,
  progress: null,
};

export const ATTEMPTS: Attempt[] = [
  {
    id: 1,
    jobId: JOB_RUNNING.id,
    attemptNumber: 1,
    workerId: 'w-11111111-1111-1111-1111-111111111111',
    startedAt: '2026-01-01T10:00:05Z',
    finishedAt: '2026-01-01T10:00:09Z',
    outcome: 'FAILED',
    error: 'transient connection reset',
  },
  {
    id: 2,
    jobId: JOB_RUNNING.id,
    attemptNumber: 2,
    workerId: 'w-11111111-1111-1111-1111-111111111111',
    startedAt: '2026-01-01T10:00:10Z',
    finishedAt: null,
    outcome: null,
    error: null,
  },
];

export const LOGS: JobLog[] = [
  {
    id: 3,
    jobId: JOB_RUNNING.id,
    workerId: 'w-11111111-1111-1111-1111-111111111111',
    level: 'INFO',
    message: 'claimed job, starting workload',
    metadata: { phase: 'start' },
    createdAt: '2026-01-01T10:00:05Z',
  },
  {
    id: 2,
    jobId: JOB_RUNNING.id,
    workerId: 'w-11111111-1111-1111-1111-111111111111',
    level: 'DEBUG',
    message: 'thread pool size 4',
    createdAt: '2026-01-01T10:00:06Z',
  },
  {
    id: 1,
    jobId: JOB_RUNNING.id,
    workerId: 'w-11111111-1111-1111-1111-111111111111',
    level: 'WARN',
    message: 'cpu thermal throttling detected',
    createdAt: '2026-01-01T10:00:08Z',
  },
];

export const WORKER: Worker = {
  id: 'w-11111111-1111-1111-1111-111111111111',
  name: 'worker-01',
  hostname: 'gpu-box-3',
  version: '0.1.0',
  status: 'BUSY',
  capabilities: ['cpu_benchmark', 'csv_analysis', 'hash_sha256'],
  startedAt: '2026-01-01T09:00:00Z',
  lastHeartbeat: '2026-01-01T10:00:03Z',
  heartbeatIntervalS: 10,
  currentJobId: JOB_RUNNING.id,
  controlPort: 9100,
  stale: false,
};

/** Route-aware fetch mock: map of "METHOD /path" (without /api/v1 prefix) → body or [status, body]. */
export function mockFetch(routes: Record<string, unknown>) {
  const calls: Array<{ url: string; init: RequestInit | undefined }> = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = String(input);
    calls.push({ url, init });
    const path = url.replace(/^\/api\/v1/, '').split('?')[0] ?? '';
    const method = (init?.method ?? 'GET').toUpperCase();
    const responder = routes[`${method} ${path}`] ?? routes[`GET ${path}`] ?? routes[path];
    if (responder === undefined) {
      return jsonResponse(404, { error: { code: 'NOT_FOUND', message: `no route ${path}` } });
    }
    if (responder instanceof Response) return responder;
    // A [status, body, headers?] tuple starts with a number; a plain data array
    // (attempts, logs) is a 200 JSON body.
    const isStatusTuple = Array.isArray(responder) && typeof responder[0] === 'number';
    if (isStatusTuple) {
      const [status, body, headers] = responder as [number, unknown, Record<string, string>?];
      return jsonResponse(status, body, headers);
    }
    if (typeof responder === 'function') {
      const result = (responder as () => unknown)();
      if (result instanceof Response) return result;
      return jsonResponse(200, result);
    }
    return jsonResponse(200, responder);
  });
  vi.stubGlobal('fetch', fn);
  return { calls, fn };
}

export function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

export const OPERATOR_SESSION = {
  token: 'test-token-op',
  user: {
    id: 'u-00000000-0000-0000-0000-000000000001',
    username: 'operator',
    role: 'OPERATOR' as const,
  },
  expiresAt: Date.now() + 60 * 60 * 1000,
};
