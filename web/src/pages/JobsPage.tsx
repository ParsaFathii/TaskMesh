import { useCallback, useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useJobs, useJobTypes, useProjects } from '../api/hooks';
import { isJobPriority, isJobStatus, JOB_PRIORITIES, JOB_STATUSES, type JobFilters, type JobPriority, type JobStatus } from '../types';
import { Panel, PageHead } from '../components/ui/Panel';
import { JobsTable } from '../components/JobsTable';
import { Pagination } from '../components/ui/Pagination';
import { ErrorPanel, TableSkeleton, EmptyState } from '../components/ui/States';
import { Button } from '../components/ui/Button';
import { SelectInput } from '../components/ui/Inputs';
import { useNow } from '../hooks/useNow';
import { fmtCount } from '../lib/format';
import { jobStatusVisual } from '../lib/status';
import { IconPlus, IconX } from '../components/icons';

const DEFAULT_SIZE = 25;

function parseFilters(params: URLSearchParams): JobFilters {
  const status = params
    .getAll('status')
    .filter((s): s is JobStatus => isJobStatus(s));
  const priorityRaw = params.get('priority');
  return {
    status: status.length > 0 ? status : undefined,
    type: params.get('type') ?? undefined,
    priority: isJobPriority(priorityRaw) ? priorityRaw : undefined,
    projectId: params.get('projectId') ?? undefined,
    page: Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0),
    size: DEFAULT_SIZE,
  };
}

export default function JobsPage() {
  const [params, setParams] = useSearchParams();
  const filters = useMemo(() => parseFilters(params), [params]);
  const jobsQ = useJobs(filters);
  const typesQ = useJobTypes();
  const projectsQ = useProjects(0, 100);
  const now = useNow(1000);

  const update = useCallback(
    (patch: Partial<JobFilters>, resetPage = true) => {
      const next = new URLSearchParams(params);
      const setOrDelete = (key: string, value: string | string[] | undefined) => {
        next.delete(key);
        if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) return;
        if (Array.isArray(value)) value.forEach((v) => next.append(key, v));
        else next.set(key, value);
      };
      setOrDelete('projectId', patch.projectId);
      setOrDelete('type', patch.type);
      setOrDelete('priority', patch.priority);
      if (patch.status !== undefined) setOrDelete('status', patch.status);
      if (resetPage) next.delete('page');
      setParams(next, { replace: true });
    },
    [params, setParams],
  );

  const toggleStatus = (status: JobStatus) => {
    const current = filters.status ?? [];
    const next = current.includes(status) ? current.filter((s) => s !== status) : [...current, status];
    update({ status: next });
  };

  const onPage = (page: number) => {
    const next = new URLSearchParams(params);
    next.set('page', String(Math.max(0, page)));
    setParams(next, { replace: true });
  };

  const hasFilters =
    (filters.status?.length ?? 0) > 0 || filters.type != null || filters.priority != null || filters.projectId != null;

  return (
    <div className="space-y-3">
      <PageHead
        title="Jobs"
        sub={`dense queue view · ${jobsQ.data?.total != null ? fmtCount(jobsQ.data.total) + ' total' : '…'} · server-side paging`}
        actions={
          <Link to="/jobs/new">
            <Button variant="primary" icon={<IconPlus size={13} />}>
              new job
            </Button>
          </Link>
        }
      />

      <Panel flush title="filters">
        <div className="flex flex-col gap-2.5 border-b border-line/60 px-3 py-2.5">
          <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Status filters">
            <span className="micro mr-1 text-dim">status</span>
            {JOB_STATUSES.map((status) => {
              const active = filters.status?.includes(status) ?? false;
              const v = jobStatusVisual[status];
              return (
                <button
                  key={status}
                  type="button"
                  aria-pressed={active}
                  onClick={() => toggleStatus(status)}
                  className={`h-6 rounded-sm border px-2 font-mono text-[10px] font-semibold uppercase tracking-[0.06em] transition-colors ${
                    active ? v.chip : 'border-line text-mute hover:border-line-bright hover:text-ink'
                  }`}
                >
                  {status}
                </button>
              );
            })}
          </div>
          <div className="flex flex-wrap items-end gap-2.5">
            <div className="w-[170px]">
              <SelectInput
                label="type"
                value={filters.type ?? ''}
                onChange={(e) => update({ type: e.target.value || undefined })}
                options={[
                  { value: '', label: 'all types' },
                  ...(typesQ.data ?? []).map((t) => ({ value: t.type, label: t.type })),
                ]}
              />
            </div>
            <div className="w-[130px]">
              <SelectInput
                label="priority"
                value={filters.priority ?? ''}
                onChange={(e) => update({ priority: (e.target.value || undefined) as JobPriority | undefined })}
                options={[
                  { value: '', label: 'all' },
                  ...JOB_PRIORITIES.map((p) => ({ value: p, label: p })),
                ]}
              />
            </div>
            <div className="min-w-[170px] flex-1 sm:max-w-[260px]">
              <SelectInput
                label="project"
                value={filters.projectId ?? ''}
                onChange={(e) => update({ projectId: e.target.value || undefined })}
                options={[
                  { value: '', label: 'all projects' },
                  ...(projectsQ.data?.items ?? []).map((p) => ({ value: p.id, label: p.name })),
                ]}
              />
            </div>
            {hasFilters && (
              <Button
                variant="ghost"
                size="sm"
                icon={<IconX size={11} />}
                onClick={() => setParams(new URLSearchParams(), { replace: true })}
              >
                reset
              </Button>
            )}
          </div>
        </div>

        {jobsQ.isLoading ? (
          <TableSkeleton rows={8} cols={7} />
        ) : jobsQ.isError ? (
          <div className="p-3">
            <ErrorPanel err={jobsQ.error} onRetry={() => void jobsQ.refetch()} />
          </div>
        ) : (jobsQ.data?.items.length ?? 0) === 0 ? (
          <EmptyState
            title="no jobs match"
            hint={hasFilters ? 'adjust or reset the filters above' : 'submit your first job to populate the queue'}
            action={
              <Link to="/jobs/new">
                <Button size="sm" variant="primary">
                  new job
                </Button>
              </Link>
            }
          />
        ) : (
          <JobsTable jobs={jobsQ.data?.items ?? []} now={now} showProject />
        )}

        {jobsQ.data != null && (
          <Pagination
            page={jobsQ.data.page}
            size={jobsQ.data.size}
            total={jobsQ.data.total}
            onPage={onPage}
            loading={jobsQ.isFetching}
          />
        )}
      </Panel>
    </div>
  );
}
