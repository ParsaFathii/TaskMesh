import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { useAttempts, useCancelJob, useJob, useJobLogs, useJobResult, useRetryJob, errMessage, ApiError } from '../api/hooks';
import { useAuthStore } from '../stores/auth';
import { isOperator } from '../lib/roles';
import { useEventsStore, selectJobLogs } from '../stores/events';
import { usePageChips } from '../stores/ui';
import { Panel, PageHead } from '../components/ui/Panel';
import { StatusBadge, PriorityBadge, Chip, WorkerStatusBadge } from '../components/ui/Bits';
import { Button } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { JsonInspector } from '../components/ui/JsonInspector';
import { ProgressBar } from '../components/ui/ProgressBar';
import { TimelineStepper, type Step } from '../components/TimelineStepper';
import { ErrorPanel, LinesSkeleton, TableSkeleton, EmptyState } from '../components/ui/States';
import { ConfirmDialog } from '../components/ui/Modal';
import { outcomeVisualFor } from '../lib/status';
import { fmtDateTime, fmtDuration, durationBetween, parseIso, shortId, timeAgo, timeUntil, fmtBytes } from '../lib/format';
import { useNow } from '../hooks/useNow';
import { LOG_LEVELS, type Job, type JobLog, type LogLevel } from '../types';
import { IconDownload, IconRetry, IconStop, IconTerminal } from '../components/icons';

function timelineSteps(job: Job, now: number): Step[] {
  const finishedLabel =
    job.status === 'SUCCEEDED' ? 'completed'
    : job.status === 'FAILED' ? 'failed'
    : job.status === 'CANCELLED' ? 'cancelled'
    : job.status === 'TIMED_OUT' ? 'timed out'
    : 'finished';

  const finishedTone: Step['tone'] =
    job.status === 'SUCCEEDED' ? 'emerald'
    : job.status === 'FAILED' ? 'rose'
    : job.status === 'CANCELLED' ? 'mute'
    : 'orange';

  const steps: Step[] = [
    { key: 'created', label: 'created', at: job.createdAt, tone: 'mute' },
    {
      key: 'queued',
      label: 'queued',
      at: job.queuedAt,
      tone: 'amber',
      note:
        job.status === 'RETRYING'
          ? `retry #${job.retryCount} scheduled · next attempt in ${timeUntil(parseIso(job.availableAt), now)}`
          : undefined,
    },
    {
      key: 'started',
      label: 'started',
      at: job.startedAt,
      tone: 'amber',
      active: job.status === 'RUNNING',
      note: job.cancelRequested && job.status === 'RUNNING' ? 'cancellation requested — worker finalizes at next checkpoint' : undefined,
    },
    {
      key: 'finished',
      label: finishedLabel,
      at: job.completedAt,
      tone: finishedTone,
      note: job.lastError != null ? job.lastError : undefined,
    },
  ];
  return steps;
}

const LOG_OPTIONS: readonly ('ALL' | LogLevel)[] = ['ALL', ...LOG_LEVELS];

function LogConsole({ jobId, restLogs }: { jobId: string; restLogs: JobLog[] }) {
  const liveLogs = useEventsStore(selectJobLogs(jobId));
  const [level, setLevel] = useState<'ALL' | LogLevel>('ALL');
  const [showMeta, setShowMeta] = useState<Record<number, boolean>>({});
  const scrollRef = useRef<HTMLDivElement>(null);
  const atBottomRef = useRef(true);
  const now = useNow(5000);

  const merged = useMemo(() => {
    const seen = new Set<string>();
    const out: JobLog[] = [];
    for (const log of [...restLogs, ...liveLogs]) {
      const key = `${log.createdAt}|${log.level}|${log.message}`;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(log);
    }
    return out;
  }, [restLogs, liveLogs]);

  const counts = useMemo(() => {
    const c: Record<string, number> = { ALL: merged.length };
    for (const l of merged) c[l.level] = (c[l.level] ?? 0) + 1;
    return c;
  }, [merged]);

  const filtered = level === 'ALL' ? merged : merged.filter((l) => l.level === level);

  useEffect(() => {
    const el = scrollRef.current;
    if (el == null) return;
    if (atBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [filtered.length]);

  const onScroll = () => {
    const el = scrollRef.current;
    if (el == null) return;
    atBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  };

  return (
    <div>
      <div className="flex flex-wrap items-center gap-1.5 border-b border-line/60 px-3 py-2" role="group" aria-label="Log level filter">
        {LOG_OPTIONS.map((l) => {
          const active = level === l;
          const tone =
            l === 'ERROR' ? 'border-rose/45 text-rose'
            : l === 'WARN' ? 'border-yellow/45 text-yellow'
            : l === 'INFO' ? 'border-teal/45 text-teal'
            : l === 'DEBUG' ? 'border-dim/40 text-dim'
            : 'border-amber/50 text-amber';
          return (
            <button
              key={l}
              type="button"
              aria-pressed={active}
              onClick={() => setLevel(l)}
              className={`h-6 rounded-sm border px-2 font-mono text-[10px] font-semibold uppercase tracking-[0.06em] ${
                active ? `${tone} bg-panel-2` : 'border-line text-mute hover:border-line-bright hover:text-ink'
              }`}
            >
              {l} <span className="text-dim">{counts[l] ?? 0}</span>
            </button>
          );
        })}
        <span className="ml-auto font-mono text-[10px] text-dim">
          {filtered.length} lines · newest at bottom · auto-follow
        </span>
      </div>
      <div
        ref={scrollRef}
        onScroll={onScroll}
        className="h-80 overflow-y-auto bg-bg-deep/60 p-2 scroll-thin font-mono text-[11px] leading-[1.45]"
        role="log"
        aria-label="Job log console"
      >
        {filtered.length === 0 ? (
          <p className="py-6 text-center text-dim">
            no log output{level !== 'ALL' ? ` at ${level} level` : ''} — worker streams stdout lines here
          </p>
        ) : (
          filtered.map((log, i) => {
            const key = `${log.id ?? 'ws'}-${i}`;
            const levelClass =
              log.level === 'ERROR' ? 'text-rose'
              : log.level === 'WARN' ? 'text-yellow'
              : log.level === 'INFO' ? 'text-teal'
              : 'text-dim';
            const metaOpen = showMeta[i] === true && log.metadata != null;
            return (
              <div key={key} className="group flex gap-2 rounded-sm px-1 py-px hover:bg-panel-2/50">
                <span className="tabular shrink-0 text-dim">{fmtDateTime(log.createdAt).slice(11)}</span>
                <span className={`w-11 shrink-0 font-semibold ${levelClass}`}>{log.level}</span>
                <span className="min-w-0 flex-1 break-all text-mute">
                  {log.message}
                  {log.metadata != null && (
                    <>
                      <button
                        type="button"
                        onClick={() => setShowMeta((prev) => ({ ...prev, [i]: !prev[i] }))}
                        className="ml-1.5 rounded-sm border border-line px-1 text-[9px] uppercase text-dim hover:text-amber"
                        aria-expanded={metaOpen}
                        aria-label="Toggle metadata"
                      >
                        {'{ }'}
                      </button>
                      {metaOpen && (
                        <span className="mt-1 block">
                          <JsonInspector data={log.metadata} defaultOpen={1} />
                        </span>
                      )}
                    </>
                  )}
                </span>
                <span className="shrink-0 text-dim opacity-0 group-hover:opacity-100">{timeAgo(parseIso(log.createdAt), now)}</span>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}

export default function JobDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { pathname } = useLocation();
  const jobQ = useJob(id);
  const attemptsQ = useAttempts(id);
  const logsQ = useJobLogs(id);
  const job = jobQ.data;
  const now = useNow(1000);

  const resultEnabled = job?.status === 'SUCCEEDED';
  const resultQ = useJobResult(id, resultEnabled);

  const cancelMut = useCancelJob(id ?? '');
  const retryMut = useRetryJob(id ?? '');
  const [confirm, setConfirm] = useState<null | 'cancel' | 'retry'>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const role = useAuthStore((s) => s.user?.role);
  const myId = useAuthStore((s) => s.user?.id);
  const ops = isOperator(role);
  const canManage = ops || job?.ownerId === myId;

  usePageChips(
    pathname,
    job != null
      ? [
          { label: job.type, tone: 'amber', mono: true },
          { label: job.status, tone: job.status === 'SUCCEEDED' ? 'emerald' : job.status === 'FAILED' ? 'rose' : 'amber' },
          { label: job.priority, tone: 'mute' },
        ]
      : [],
    [job?.type, job?.status, job?.priority],
  );

  // reset the live log buffer when entering this job (REST provides the history)
  useEffect(() => {
    if (id != null) useEventsStore.getState().clearJobLogs(id);
  }, [id]);

  // revoke the file blob when it changes / unmounts
  const blobUrl = resultQ.data?.blobUrl;
  useEffect(() => {
    if (blobUrl == null) return;
    return () => URL.revokeObjectURL(blobUrl);
  }, [blobUrl]);

  const confirmAction = async () => {
    if (confirm == null || id == null) return;
    setActionError(null);
    try {
      if (confirm === 'cancel') await cancelMut.mutateAsync();
      else await retryMut.mutateAsync();
      setConfirm(null);
    } catch (err) {
      setActionError(errMessage(err, 'action failed'));
    }
  };

  if (jobQ.isError) {
    return (
      <div className="max-w-2xl space-y-3">
        <PageHead title="Job" sub={id} />
        <ErrorPanel err={jobQ.error} onRetry={() => void jobQ.refetch()} />
      </div>
    );
  }

  if (job == null) {
    return (
      <div className="space-y-3">
        <PageHead title="Job" sub="loading…" />
        <Panel title="job">
          <LinesSkeleton lines={6} />
        </Panel>
      </div>
    );
  }

  const running = job.status === 'RUNNING';
  const duration = running
    ? durationBetween(job.startedAt, new Date(now).toISOString())
    : durationBetween(job.startedAt, job.completedAt);
  const cancelable = job.status === 'QUEUED' || job.status === 'RUNNING' || job.status === 'RETRYING';
  const retryable = job.status === 'FAILED' || job.status === 'TIMED_OUT' || job.status === 'CANCELLED';

  return (
    <div className="space-y-3">
      <PageHead
        title={`Job ${shortId(job.id, 8)}`}
        sub={
          <span className="flex flex-wrap items-center gap-2">
            <span className="text-amber">{job.type}</span>
            <span className="text-dim">·</span>
            <span>created {timeAgo(parseIso(job.createdAt), now)} ago</span>
            <span className="text-dim">·</span>
            <Link to={`/projects/${job.projectId}`} className="text-mute hover:text-ink">
              {job.projectName ?? shortId(job.projectId, 8)}
            </Link>
            <span className="text-dim">·</span>
            <span className="text-dim">owner {job.ownerName ?? shortId(job.ownerId, 8)}</span>
          </span>
        }
        actions={
          canManage ? (
            <>
              {cancelable && (
                <Button variant="danger" icon={<IconStop size={13} />} onClick={() => { setActionError(null); setConfirm('cancel'); }}>
                  cancel job
                </Button>
              )}
              {retryable && (
                <Button variant="secondary" icon={<IconRetry size={13} />} onClick={() => { setActionError(null); setConfirm('retry'); }}>
                  retry job
                </Button>
              )}
            </>
          ) : null
        }
      />

      {actionError != null && <ErrorPanel err={new ApiError(0, 'ACTION', actionError)} compact />}

      <Panel title="state">
        <div className="flex flex-wrap items-center gap-x-5 gap-y-3">
          <div className="flex items-center gap-2.5">
            <StatusBadge status={job.status} size="md" />
            <PriorityBadge priority={job.priority} />
            {job.idempotencyKey != null && <Chip tone="teal" title={`Idempotency key: ${job.idempotencyKey}`}>idem</Chip>}
          </div>
          {running && (
            <div className="flex min-w-[180px] flex-1 items-center gap-2.5 sm:max-w-xs">
              <ProgressBar value={job.progress} />
              <span className="tabular font-mono text-[11px] text-amber">
                {job.progress != null ? `${job.progress}%` : 'pending'}
              </span>
            </div>
          )}
          <dl className="flex flex-wrap items-center gap-x-5 gap-y-2 font-mono text-[11px]">
            <div className="flex items-baseline gap-1.5">
              <dt className="micro text-dim">duration</dt>
              <dd className={`tabular ${running ? 'text-amber' : 'text-mute'}`}>{running && duration != null ? `${Math.round(duration / 100) / 10}s` : fmtDuration(duration)}</dd>
            </div>
            <div className="flex items-baseline gap-1.5">
              <dt className="micro text-dim">retries</dt>
              <dd className="tabular text-mute">
                {job.retryCount}/{job.maxRetries}
              </dd>
            </div>
            <div className="flex items-baseline gap-1.5">
              <dt className="micro text-dim">timeout</dt>
              <dd className="tabular text-mute">{job.timeoutSeconds}s</dd>
            </div>
            <div className="flex items-baseline gap-1.5">
              <dt className="micro text-dim">job id</dt>
              <dd className="flex items-center gap-1 break-all text-mute">
                {job.id}
                <CopyButton value={job.id} label="job id" />
              </dd>
            </div>
          </dl>
        </div>
        {(job.lastError != null || job.cancelRequested) && (
          <div className="mt-3 rounded-sm border border-rose/40 bg-rose/5 px-3 py-2">
            {job.cancelRequested && running && (
              <p className="font-mono text-[11px] text-yellow">cancellation requested — waiting for worker checkpoint / lease expiry + 30s grace</p>
            )}
            {job.lastError != null && (
              <p className="mt-1 break-words font-mono text-[11px] leading-4 text-rose">
                <span className="micro mr-2 text-rose">last error</span>
                {job.lastError}
              </p>
            )}
          </div>
        )}
      </Panel>

      <div className="grid gap-3 xl:grid-cols-[1fr_380px]">
        <div className="space-y-3">
          <Panel title="execution timeline">
            <TimelineStepper steps={timelineSteps(job, now)} />
          </Panel>

          <Panel title={`attempts · ${attemptsQ.data?.length ?? 0}`} flush>
            {attemptsQ.isLoading ? (
              <TableSkeleton rows={3} cols={5} />
            ) : attemptsQ.isError ? (
              <div className="p-3">
                <ErrorPanel err={attemptsQ.error} onRetry={() => void attemptsQ.refetch()} />
              </div>
            ) : (attemptsQ.data?.length ?? 0) === 0 ? (
              <EmptyState icon={<IconRetry size={20} />} title="no attempts yet — job has not been claimed" />
            ) : (
              <div className="overflow-x-auto scroll-thin">
                <table className="w-full min-w-[520px] text-left font-mono text-[11px]">
                  <thead>
                    <tr className="border-b border-line">
                      {['#', 'worker', 'started', 'duration', 'outcome', 'error'].map((h) => (
                        <th key={h} scope="col" className="micro px-3 py-2 text-mute">
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {(attemptsQ.data ?? []).map((attempt) => {
                      const v = outcomeVisualFor(attempt.outcome);
                      const dur = durationBetween(attempt.startedAt, attempt.finishedAt);
                      return (
                        <tr key={attempt.id} className="border-b border-line/50 align-top hover:bg-panel-2/60">
                          <td className="tabular px-3 py-2 text-ink">{attempt.attemptNumber}</td>
                          <td className="px-3 py-2">
                            {attempt.workerId != null ? (
                              ops ? (
                                <Link to={`/workers/${attempt.workerId}`} className="text-mute hover:text-ink">
                                  {shortId(attempt.workerId, 6)}
                                </Link>
                              ) : (
                                <span className="text-mute">{shortId(attempt.workerId, 6)}</span>
                              )
                            ) : (
                              <span className="text-dim">—</span>
                            )}
                          </td>
                          <td className="tabular px-3 py-2 text-mute" title={fmtDateTime(attempt.startedAt)}>
                            {fmtDateTime(attempt.startedAt).slice(5)}
                          </td>
                          <td className="tabular px-3 py-2 text-mute">
                            {attempt.finishedAt == null ? (
                              <span className="text-amber">in progress…</span>
                            ) : (
                              fmtDuration(dur)
                            )}
                          </td>
                          <td className="px-3 py-2">
                            <span className={`inline-flex items-center gap-1.5 rounded-sm border px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.06em] ${v.chip}`}>
                              {attempt.outcome ?? 'RUNNING'}
                            </span>
                          </td>
                          <td className="max-w-[280px] px-3 py-2">
                            {attempt.error != null ? (
                              <span className="block truncate text-rose" title={attempt.error}>
                                {attempt.error}
                              </span>
                            ) : (
                              <span className="text-dim">—</span>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          <Panel title="live log console" flush>
            {logsQ.isError ? (
              <div className="p-3">
                <ErrorPanel err={logsQ.error} onRetry={() => void logsQ.refetch()} />
              </div>
            ) : logsQ.isLoading ? (
              <div className="p-3">
                <LinesSkeleton lines={6} />
              </div>
            ) : (
              <LogConsole jobId={job.id} restLogs={logsQ.data ?? []} />
            )}
            <p className="flex items-center gap-2 border-t border-line/60 px-3 py-1.5 font-mono text-[10px] text-dim">
              <IconTerminal size={11} />
              initial history GET /api/v1/jobs/{shortId(job.id, 6)}/logs · live tail via WS job.log frames
            </p>
          </Panel>
        </div>

        <div className="space-y-3">
          <Panel title="payload">
            <JsonInspector data={job.payload} name={`${job.type} · payload`} defaultOpen={2} />
          </Panel>

          {resultEnabled && (
            <Panel title="result">
              {resultQ.isLoading ? (
                <LinesSkeleton lines={4} />
              ) : resultQ.isError ? (
                <ErrorPanel err={resultQ.error} onRetry={() => void resultQ.refetch()} />
              ) : resultQ.data != null ? (
                resultQ.data.kind === 'inline' ? (
                  <div className="space-y-2.5">
                    <JsonInspector data={resultQ.data.data} name="inline result" defaultOpen={3} />
                    <ResultFooter sha256={resultQ.data.sha256} sizeBytes={null} />
                  </div>
                ) : (
                  <div className="space-y-3">
                    <a
                      href={resultQ.data.blobUrl}
                      download={`taskmesh-job-${shortId(job.id, 8)}.bin`}
                      className="flex h-14 items-center justify-center gap-2 rounded-sm border border-amber/50 bg-amber/5 font-mono text-[11px] font-semibold uppercase tracking-[0.08em] text-amber hover:bg-amber/10"
                    >
                      <IconDownload size={15} />
                      download result file · {fmtBytes(resultQ.data.sizeBytes)}
                    </a>
                    <ResultFooter sha256={resultQ.data.sha256} sizeBytes={resultQ.data.sizeBytes} />
                  </div>
                )
              ) : null}
            </Panel>
          )}

          {job.status !== 'SUCCEEDED' && job.status !== 'FAILED' && job.status !== 'TIMED_OUT' && (
            <Panel title="result · pending">
              <p className="py-2 text-center font-mono text-[11px] text-dim">
                results appear when the job reaches SUCCEEDED
              </p>
            </Panel>
          )}

          <Panel title="worker">
            {job.workerId == null ? (
              <p className="py-2 text-center font-mono text-[11px] text-dim">
                {job.status === 'QUEUED' || job.status === 'RETRYING' ? 'waiting for a worker claim' : 'no worker assigned'}
              </p>
            ) : job.worker != null ? (
              <div className="space-y-2.5">
                <div className="flex items-center justify-between gap-2">
                  {ops ? (
                    <Link to={`/workers/${job.workerId}`} className="font-mono text-[12px] font-semibold text-amber hover:underline">
                      {job.worker.name}
                    </Link>
                  ) : (
                    <span className="font-mono text-[12px] font-semibold text-mute">{job.worker.name}</span>
                  )}
                  <WorkerStatusBadge status={job.worker.status} />
                </div>
                <p className="font-mono text-[10.5px] text-dim">worker {shortId(job.workerId, 13)}…</p>
              </div>
            ) : (
              <p className="font-mono text-[11px] text-mute">
                worker {shortId(job.workerId, 13)}…
                {ops ? (
                  <>
                    {' '}
                    <Link to={`/workers/${job.workerId}`} className="text-amber hover:underline">
                      open →
                    </Link>
                  </>
                ) : null}
              </p>
            )}
            {running && job.leaseExpiresAt != null && (
              <p className="mt-2.5 border-t border-line/60 pt-2 font-mono text-[10px] text-dim">
                lease expires in {timeUntil(parseIso(job.leaseExpiresAt), now)} — sweeper re-queues abandoned runs
              </p>
            )}
          </Panel>

          <Panel title="retry policy">
            <dl className="space-y-2 font-mono text-[11px]">
              <div className="flex justify-between gap-2">
                <dt className="text-dim">retry count</dt>
                <dd className="tabular text-mute">
                  {job.retryCount} / {job.maxRetries}
                </dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-dim">backoff</dt>
                <dd className="text-mute">min(300, 2^retry × 5) s</dd>
              </div>
              {job.status === 'RETRYING' && (
                <div className="flex justify-between gap-2">
                  <dt className="text-dim">next attempt</dt>
                  <dd className="text-amber">{timeUntil(parseIso(job.availableAt), now)}</dd>
                </div>
              )}
              <div className="flex justify-between gap-2">
                <dt className="text-dim">idempotency key</dt>
                <dd className="truncate text-mute">{job.idempotencyKey ?? '—'}</dd>
              </div>
            </dl>
          </Panel>
        </div>
      </div>

      <ConfirmDialog
        open={confirm === 'cancel'}
        onClose={() => setConfirm(null)}
        onConfirm={confirmAction}
        tone="danger"
        title="cancel job"
        confirmLabel="cancel job"
        busy={cancelMut.isPending}
        error={actionError}
        message={
          running ? (
            <>
              The job is RUNNING — a <span className="text-ink">cancellation request</span> will be recorded. The worker
              finalizes CANCELLED at its next checkpoint; the sweeper enforces it after lease expiry + 30s grace.
            </>
          ) : (
            <>
              Cancel job <span className="text-ink">{shortId(job.id, 8)}</span> ({job.status})? This takes effect
              immediately.
            </>
          )
        }
      />

      <ConfirmDialog
        open={confirm === 'retry'}
        onClose={() => setConfirm(null)}
        onConfirm={confirmAction}
        title="retry job"
        confirmLabel="requeue job"
        busy={retryMut.isPending}
        error={actionError}
        message={
          <>
            Re-queue job <span className="text-ink">{shortId(job.id, 8)}</span> as QUEUED? The retry counter resets to
            0 and the last error is kept on record.
          </>
        }
      />
    </div>
  );
}

function ResultFooter({ sha256, sizeBytes }: { sha256: string | null | undefined; sizeBytes: number | null | undefined }) {
  return (
    <div className="space-y-1.5 border-t border-line/60 pt-2.5 font-mono text-[10.5px]">
      {sha256 != null && (
        <p className="flex items-center gap-1.5">
          <span className="micro shrink-0 text-dim">sha256</span>
          <span className="truncate text-mute" title={sha256}>
            {sha256}
          </span>
          <CopyButton value={sha256} label="sha256" />
        </p>
      )}
      {sizeBytes != null && (
        <p className="flex items-baseline gap-1.5">
          <span className="micro shrink-0 text-dim">size</span>
          <span className="tabular text-mute">{fmtBytes(sizeBytes)}</span>
        </p>
      )}
      <p className="text-dim">{sha256 != null ? 'checksum from X-Job-Result-SHA256' : 'checksum not provided by API'}</p>
    </div>
  );
}
