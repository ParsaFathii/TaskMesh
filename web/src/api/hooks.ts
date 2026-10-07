import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { apiFetch, apiRaw, qs, toApiError, ApiError } from './client';
import { parseJobTypes, SPEC_JOB_TYPES } from './catalog';
import { parseMetrics, type Metrics } from '../lib/metrics';
import { useAuthStore } from '../stores/auth';
import type {
  Attempt,
  AuditFilters,
  AuditLog,
  Health,
  Job,
  JobFilters,
  JobLog,
  JobResultView,
  JobType,
  LoginResponse,
  Page,
  Project,
  UserRecord,
  Worker,
  WorkerSummary,
} from '../types';

export { ApiError, errMessage, API_BASE, apiUrl } from './client';

/* --------------------------------- auth ----------------------------------- */

export function useHealth(): UseQueryResult<Health> {
  return useQuery({
    queryKey: ['health'],
    queryFn: () => apiFetch<Health>('/health'),
    retry: false,
    staleTime: 10_000,
    refetchInterval: 30_000,
  });
}

export function useMe(): UseQueryResult<UserRecord> {
  return useQuery({ queryKey: ['me'], queryFn: () => apiFetch<UserRecord>('/me') });
}

export function useLogin() {
  return useMutation({
    mutationFn: (input: { username: string; password: string }) =>
      apiFetch<LoginResponse>('/auth/login', { method: 'POST', body: JSON.stringify(input) }),
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      useAuthStore.getState().logout();
    },
    onSuccess: () => qc.clear(),
  });
}

/* ------------------------------- job types -------------------------------- */

export function useJobTypes(): UseQueryResult<JobType[]> {
  return useQuery({
    queryKey: ['job-types'],
    queryFn: async (): Promise<JobType[]> => {
      try {
        const raw = await apiFetch<unknown>('/job-types');
        const parsed = parseJobTypes(raw);
        if (parsed.length > 0) return parsed;
      } catch (err) {
        // auth/permission failures must surface — never masked by the fallback
        if (err instanceof ApiError && err.isAuthError) throw err;
      }
      // endpoint unreachable or empty → fall back to the SPEC §9 catalog so the
      // submission form stays usable against a not-yet-started backend
      return SPEC_JOB_TYPES;
    },
    staleTime: 5 * 60_000,
  });
}

/* -------------------------------- projects --------------------------------- */

export function useProjects(page = 0, size = 25, enabled = true): UseQueryResult<Page<Project>> {
  return useQuery({
    queryKey: ['projects', page, size],
    queryFn: () => apiFetch<Page<Project>>(`/projects${qs({ page, size })}`),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useProject(id: string | undefined): UseQueryResult<Project> {
  return useQuery({
    queryKey: ['project', id],
    queryFn: () => apiFetch<Project>(`/projects/${id}`),
    enabled: id != null,
  });
}

export function useCreateProject() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string; description: string }) =>
      apiFetch<Project>('/projects', { method: 'POST', body: JSON.stringify(input) }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['projects'] });
    },
  });
}

export function useUpdateProject() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: string; name?: string; description?: string }) =>
      apiFetch<Project>(
        `/projects/${input.id}`,
        { method: 'PATCH', body: JSON.stringify({ name: input.name, description: input.description }) },
      ),
    onSuccess: (project) => {
      void qc.invalidateQueries({ queryKey: ['projects'] });
      if (project?.id) void qc.invalidateQueries({ queryKey: ['project', project.id] });
    },
  });
}

export function useDeleteProject() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/projects/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['projects'] });
    },
  });
}

/* ---------------------------------- jobs ----------------------------------- */

export function useJobs(filters: JobFilters, enabled = true): UseQueryResult<Page<Job>> {
  return useQuery({
    queryKey: ['jobs', filters],
    queryFn: () =>
      apiFetch<Page<Job>>(
        `/jobs${qs({
          projectId: filters.projectId,
          status: filters.status,
          type: filters.type,
          priority: filters.priority,
          page: filters.page ?? 0,
          size: filters.size ?? 25,
        })}`,
      ),
    enabled,
    placeholderData: keepPreviousData,
  });
}

/**
 * GET /jobs/{id} wraps the row as {"job": {...}, "worker": {…|null}}
 * (SPEC §8: "full job incl. worker summary") — flatten it into a single Job.
 */
function unwrapJobDetail(res: unknown): Job {
  if (res != null && typeof res === 'object' && 'job' in (res as Record<string, unknown>)) {
    const wrapped = res as { job: Job; worker?: WorkerSummary | null };
    return { ...wrapped.job, worker: wrapped.worker ?? wrapped.job.worker ?? null };
  }
  return res as Job;
}

export function useJob(id: string | undefined): UseQueryResult<Job> {
  return useQuery({
    queryKey: ['job', id],
    queryFn: async () => unwrapJobDetail(await apiFetch<unknown>(`/jobs/${id}`)),
    enabled: id != null,
    // WS events drive refetches; RUNNING jobs additionally poll at 5s as a net
    refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? 5_000 : false),
  });
}

export function useJobLogs(id: string | undefined, limit = 500): UseQueryResult<JobLog[]> {
  return useQuery({
    queryKey: ['job-logs', id, limit],
    queryFn: async () => {
      const logs = await apiFetch<JobLog[]>(`/jobs/${id}/logs${qs({ limit })}`);
      // SPEC: newest-first → reverse into chronological console order
      return [...logs].reverse();
    },
    enabled: id != null,
  });
}

export function useAttempts(id: string | undefined): UseQueryResult<Attempt[]> {
  return useQuery({
    queryKey: ['attempts', id],
    queryFn: () => apiFetch<Attempt[]>(`/jobs/${id}/attempts`),
    enabled: id != null,
  });
}

export function useJobResult(id: string | undefined, enabled: boolean): UseQueryResult<JobResultView> {
  return useQuery({
    queryKey: ['job-result', id],
    queryFn: async (): Promise<JobResultView> => {
      const res = await apiRaw(`/jobs/${id}/result`);
      if (!res.ok) throw await toApiError(res);
      const sha = res.headers.get('X-Job-Result-SHA256');
      const ct = res.headers.get('content-type') ?? '';
      if (ct.includes('json')) {
        const data: unknown = await res.json();
        return { kind: 'inline', data, sha256: sha, contentType: ct };
      }
      const blob = await res.blob();
      return {
        kind: 'file',
        blobUrl: URL.createObjectURL(blob),
        sha256: sha,
        sizeBytes: blob.size,
        contentType: ct || 'application/octet-stream',
      };
    },
    enabled: id != null && enabled,
  });
}

export interface CreateJobInput {
  projectId: string;
  type: string;
  priority: string;
  payload: Record<string, unknown>;
  timeoutSeconds?: number;
  maxRetries?: number;
  idempotencyKey?: string;
}

export function useCreateJob() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateJobInput) => apiFetch<Job>('/jobs', { method: 'POST', body: JSON.stringify(input) }),
    onSuccess: (job) => {
      void qc.invalidateQueries({ queryKey: ['jobs'] });
      void qc.invalidateQueries({ queryKey: ['metrics'] });
      if (job?.id) void qc.setQueryData(['job', job.id], job);
    },
  });
}

export function useCancelJob(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch<Job>(`/jobs/${id}/cancel`, { method: 'POST' }),
    onSuccess: (job) => {
      if (job?.id) void qc.setQueryData(['job', job.id], job);
      void qc.invalidateQueries({ queryKey: ['job', id] });
      void qc.invalidateQueries({ queryKey: ['jobs'] });
      void qc.invalidateQueries({ queryKey: ['metrics'] });
    },
  });
}

export function useRetryJob(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch<Job>(`/jobs/${id}/retry`, { method: 'POST' }),
    onSuccess: (job) => {
      if (job?.id) void qc.setQueryData(['job', job.id], job);
      void qc.invalidateQueries({ queryKey: ['job', id] });
      void qc.invalidateQueries({ queryKey: ['jobs'] });
      void qc.invalidateQueries({ queryKey: ['attempts', id] });
      void qc.invalidateQueries({ queryKey: ['metrics'] });
    },
  });
}

/* --------------------------------- workers --------------------------------- */

export function useWorkers(enabled = true): UseQueryResult<Worker[]> {
  return useQuery({
    queryKey: ['workers'],
    queryFn: async (): Promise<Worker[]> => {
      // The API returns a paged envelope {items,page,size,total}; accept a bare
      // array too so the hook stays shape-agnostic.
      const res = await apiFetch<Page<Worker> | Worker[]>('/workers');
      return Array.isArray(res) ? res : (res.items ?? []);
    },
    enabled,
    refetchInterval: 15_000,
  });
}

export function useWorker(id: string | undefined): UseQueryResult<Worker> {
  return useQuery({
    queryKey: ['worker', id],
    queryFn: () => apiFetch<Worker>(`/workers/${id}`),
    enabled: id != null,
    refetchInterval: 10_000,
  });
}

/* ------------------------------- audit logs -------------------------------- */

export function useAudit(filters: AuditFilters): UseQueryResult<Page<AuditLog>> {
  return useQuery({
    queryKey: ['audit', filters],
    queryFn: () =>
      apiFetch<Page<AuditLog>>(
        `/logs${qs({
          actor: filters.actor,
          action: filters.action,
          result: filters.result,
          page: filters.page ?? 0,
          size: filters.size ?? 25,
        })}`,
      ),
    placeholderData: keepPreviousData,
  });
}

/* --------------------------------- metrics --------------------------------- */

export function useMetrics(enabled = true): UseQueryResult<Metrics> {
  return useQuery({
    queryKey: ['metrics'],
    queryFn: async () => parseMetrics(await apiFetch<unknown>('/metrics')),
    enabled,
    refetchInterval: 10_000,
  });
}
