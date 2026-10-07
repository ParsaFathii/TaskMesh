import { Link, useLocation, useParams } from 'react-router-dom';
import { useWorker } from '../api/hooks';
import { RoleGate } from '../components/layout/RequireAuth';
import { usePageChips } from '../stores/ui';
import { useEventsStore } from '../stores/events';
import { Panel, PageHead } from '../components/ui/Panel';
import { WorkerStatusBadge, Chip } from '../components/ui/Bits';
import { CopyButton } from '../components/ui/CopyButton';
import { TimelineStepper, type Step } from '../components/TimelineStepper';
import { ErrorPanel, LinesSkeleton, EmptyState } from '../components/ui/States';
import { fmtDateTime, shortId, timeAgo, parseIso, fmtClock } from '../lib/format';
import { useNow } from '../hooks/useNow';
import { toneClass } from '../stores/events';
import { IconPulse, IconStack } from '../components/icons';
import type { WorkerStatus } from '../types';

export default function WorkerDetailPage() {
  return (
    <RoleGate roles={['OPERATOR', 'ADMIN']}>
      <WorkerDetailContent />
    </RoleGate>
  );
}

function WorkerDetailContent() {
  const { id } = useParams<{ id: string }>();
  const { pathname } = useLocation();
  const workerQ = useWorker(id);
  const worker = workerQ.data;
  const now = useNow(1000);
  const ticker = useEventsStore((s) => s.ticker);

  usePageChips(
    pathname,
    worker != null
      ? [
          { label: worker.status, tone: worker.status === 'ERROR' ? 'rose' : worker.status === 'IDLE' ? 'emerald' : 'amber' },
          { label: worker.name, tone: 'mute', mono: true },
        ]
      : [],
    [worker?.status, worker?.name],
  );

  if (workerQ.isError) {
    return (
      <div className="max-w-2xl space-y-3">
        <PageHead title="Worker" sub={id} />
        <ErrorPanel err={workerQ.error} onRetry={() => void workerQ.refetch()} />
      </div>
    );
  }

  if (worker == null) {
    return (
      <div className="space-y-3">
        <PageHead title="Worker" sub="loading…" />
        <Panel title="worker">
          <LinesSkeleton lines={6} />
        </Panel>
      </div>
    );
  }

  const workerEvents = ticker.filter((e) => e.workerId === worker.id).slice(-25).reverse();
  const stale = worker.stale === true;
  const hbAge = timeAgo(parseIso(worker.lastHeartbeat), now);
  const hbTone =
    stale || (parseIso(worker.lastHeartbeat) != null && now - (parseIso(worker.lastHeartbeat) as number) > worker.heartbeatIntervalS * 3000)
      ? 'text-rose'
      : 'text-emerald';

  const steps: Step[] = [
    { key: 'registered', label: 'registered', at: worker.startedAt, tone: 'teal' },
    {
      key: 'heartbeat',
      label: 'last heartbeat',
      at: worker.lastHeartbeat,
      tone: 'mute',
      note: `interval ${worker.heartbeatIntervalS}s · ${hbAge} ago`,
    },
    {
      key: 'current',
      label: 'current assignment',
      at: null,
      tone: 'amber',
      active: worker.status === 'BUSY',
      note:
        worker.currentJobId != null
          ? `executing job ${shortId(worker.currentJobId, 8)}`
          : 'idle — no claimed job',
    },
  ];

  return (
    <div className="space-y-3">
      <PageHead
        title={`Worker ${worker.name}`}
        sub={`${worker.hostname} · v${worker.version} · ${worker.id.slice(0, 13)}…`}
        actions={
          worker.currentJobId != null ? (
            <Link to={`/jobs/${worker.currentJobId}`}>
              <CurrentJobButton />
            </Link>
          ) : undefined
        }
      />

      {stale && (
        <div className="flex items-center gap-2 rounded-sm border border-yellow/45 bg-yellow/5 px-3 py-2 font-mono text-[11px] text-yellow" role="status">
          <IconPulse size={13} />
          stale worker — no heartbeat for {hbAge} (threshold 3 × {worker.heartbeatIntervalS}s); the sweeper marks it
          OFFLINE and re-queues abandoned jobs
        </div>
      )}

      <div className="grid gap-3 lg:grid-cols-[320px_1fr]">
        <div className="space-y-3">
          <Panel title="status">
            <div className="flex items-center gap-4">
              <StatusRing status={worker.status} />
              <div className="min-w-0">
                <WorkerStatusBadge status={worker.status} />
                <p className={`mt-1.5 font-mono text-[11px] ${hbTone}`}>heartbeat {hbAge} ago</p>
                <p className="font-mono text-[10px] text-dim">control port {worker.controlPort ?? '—'}</p>
              </div>
            </div>
            <p className="mt-3 border-t border-line/60 pt-2.5 font-mono text-[10px] leading-4 text-dim">
              GET /api/v1/workers/{shortId(worker.id, 8)} · poll 10s + WS worker.updated invalidation
            </p>
          </Panel>

          <Panel title="capabilities">
            <div className="flex flex-wrap gap-1.5">
              {worker.capabilities.length === 0 ? (
                <p className="font-mono text-[11px] text-dim">no capabilities advertised</p>
              ) : (
                worker.capabilities.map((cap) => (
                  <Chip key={cap} tone="teal">
                    {cap}
                  </Chip>
                ))
              )}
            </div>
          </Panel>

          <Panel title="identity">
            <dl className="space-y-2 font-mono text-[11px]">
              <Row label="worker id" value={worker.id} copy />
              <Row label="name" value={worker.name} />
              <Row label="hostname" value={worker.hostname} />
              <Row label="version" value={worker.version} />
              <Row label="started" value={fmtDateTime(worker.startedAt)} />
              <Row label="uptime" value={timeAgo(parseIso(worker.startedAt), now)} />
              <Row label="hb interval" value={`${worker.heartbeatIntervalS}s`} />
              <Row label="current job" value={worker.currentJobId ?? '—'} copy={worker.currentJobId != null} />
            </dl>
          </Panel>
        </div>

        <div className="space-y-3">
          <Panel title="status timeline">
            <TimelineStepper steps={steps} />
          </Panel>

          <Panel title="recent worker events · live" flush>
            {workerEvents.length === 0 ? (
              <EmptyState
                icon={<IconPulse size={20} />}
                title="no worker.updated events yet"
                hint="state transitions broadcast by the backend land here in real time (WS /ws/v1/events)"
              />
            ) : (
              <ul className="divide-y divide-line/50">
                {workerEvents.map((e) => (
                  <li key={e.id} className="flex items-center gap-3 px-3 py-1.5 font-mono text-[11px]">
                    <span className="tabular shrink-0 text-dim">{fmtClock(e.at)}</span>
                    <span className={`shrink-0 ${toneClass(e.tone)}`}>{e.kind}</span>
                    <span className="min-w-0 flex-1 truncate text-mute">{e.text}</span>
                    {e.jobId != null && (
                      <Link to={`/jobs/${e.jobId}`} className="shrink-0 text-amber hover:underline">
                        job →
                      </Link>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="current assignment">
            {worker.currentJobId != null ? (
              <div className="flex flex-wrap items-center gap-3">
                <Link
                  to={`/jobs/${worker.currentJobId}`}
                  className="rounded-sm border border-amber/50 bg-amber/5 px-2.5 py-1.5 font-mono text-[11.5px] font-semibold text-amber hover:bg-amber/10"
                >
                  <IconStack size={12} className="mr-1.5 inline" />
                  job {shortId(worker.currentJobId, 10)} →
                </Link>
                <CopyButton value={worker.currentJobId} label="current job id" />
                <span className="font-mono text-[10px] text-dim">
                  lease renews with each heartbeat while running
                </span>
              </div>
            ) : (
              <p className="py-2 text-center font-mono text-[11px] text-dim">
                idle — this worker has no claimed job
              </p>
            )}
          </Panel>
        </div>
      </div>
    </div>
  );
}

function CurrentJobButton() {
  return (
    <span className="inline-flex h-8 items-center gap-2 rounded-sm border border-amber/50 bg-amber/5 px-3 font-mono text-[11px] font-semibold uppercase tracking-[0.08em] text-amber hover:bg-amber/10">
      <IconStack size={13} /> current job
    </span>
  );
}

function Row({ label, value, copy = false }: { label: string; value: string; copy?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-2">
      <dt className="micro shrink-0 text-dim">{label}</dt>
      <dd className="min-w-0 flex items-center gap-1 truncate text-right text-mute" title={value}>
        {value}
        {copy && value !== '—' && <CopyButton value={value} label={label} />}
      </dd>
    </div>
  );
}

function StatusRing({ status }: { status: WorkerStatus }) {
  const color =
    status === 'IDLE' ? 'var(--color-emerald)'
    : status === 'BUSY' ? 'var(--color-amber)'
    : status === 'STARTING' ? 'var(--color-teal)'
    : status === 'DRAINING' ? 'var(--color-yellow)'
    : status === 'ERROR' ? 'var(--color-rose)'
    : 'var(--color-dim)';
  const pulse = status === 'BUSY' || status === 'STARTING' || status === 'ERROR';
  return (
    <svg width={64} height={64} viewBox="0 0 64 64" aria-hidden="true">
      <rect x={4} y={4} width={56} height={56} rx={8} fill="none" stroke="var(--color-line)" strokeWidth={1.5} />
      <rect
        x={4}
        y={4}
        width={56}
        height={56}
        rx={8}
        fill="none"
        stroke={color}
        strokeWidth={3}
        strokeDasharray="68 180"
        strokeLinecap="butt"
        className={pulse ? 'animate-pulse-status' : ''}
        transform="rotate(-90 32 32)"
      />
      <text x={32} y={37} textAnchor="middle" fill={color} fontSize={9} fontFamily="var(--font-mono)" fontWeight={700}>
        {status.slice(0, 7)}
      </text>
    </svg>
  );
}
