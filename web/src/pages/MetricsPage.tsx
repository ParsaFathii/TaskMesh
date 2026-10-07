import { useMetrics } from '../api/hooks';
import { RoleGate } from '../components/layout/RequireAuth';
import { Panel, PageHead } from '../components/ui/Panel';
import { KpiTile, BarRow, Sparkline } from '../components/KpiStrip';
import { ErrorPanel } from '../components/ui/States';
import { Button } from '../components/ui/Button';
import { sumQueueDepth, workersOnline } from '../lib/metrics';
import { fmtCount, fmtDuration } from '../lib/format';
import { IconRefresh } from '../components/icons';
import type { JobStatus, JobPriority, WorkerStatus } from '../types';

const STATUS_TONES: Record<JobStatus, Parameters<typeof BarRow>[0]['tone']> = {
  QUEUED: 'dim',
  RUNNING: 'amber',
  SUCCEEDED: 'emerald',
  FAILED: 'rose',
  RETRYING: 'yellow',
  TIMED_OUT: 'orange',
  CANCELLED: 'mute',
};

const QUEUE_TONES: Record<JobPriority, Parameters<typeof BarRow>[0]['tone']> = {
  CRITICAL: 'rose',
  HIGH: 'amber',
  NORMAL: 'teal',
  LOW: 'mute',
};

const WORKER_TONES: Record<WorkerStatus, Parameters<typeof BarRow>[0]['tone']> = {
  STARTING: 'teal',
  IDLE: 'emerald',
  BUSY: 'amber',
  DRAINING: 'yellow',
  OFFLINE: 'dim',
  ERROR: 'rose',
};

function Bars<T extends string>({
  entries,
  toneFor,
  emptyLabel,
}: {
  entries: [T, number][];
  toneFor: (key: T) => Parameters<typeof BarRow>[0]['tone'];
  emptyLabel: string;
}) {
  if (entries.length === 0) {
    return <p className="py-4 text-center font-mono text-[11px] text-dim">{emptyLabel}</p>;
  }
  const max = Math.max(...entries.map(([, v]) => v), 1);
  return (
    <div>
      {entries.map(([key, value]) => (
        <BarRow key={key} label={key} value={value} max={max} tone={toneFor(key)} />
      ))}
    </div>
  );
}

export default function MetricsPage() {
  return (
    <RoleGate roles={['OPERATOR', 'ADMIN']}>
      <MetricsContent />
    </RoleGate>
  );
}

function MetricsContent() {
  const metricsQ = useMetrics();
  const m = metricsQ.data;

  return (
    <div className="space-y-3">
      <PageHead
        title="Metrics"
        sub="cluster snapshot · auto-refresh 10s + WS invalidation on terminal transitions"
        actions={
          <Button
            size="sm"
            variant="secondary"
            icon={<IconRefresh size={12} />}
            onClick={() => void metricsQ.refetch()}
            loading={metricsQ.isFetching}
          >
            refresh
          </Button>
        }
      />

      {metricsQ.isError && <ErrorPanel err={metricsQ.error} onRetry={() => void metricsQ.refetch()} />}

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
        <KpiTile label="queue depth" tone="amber" value={fmtCount(sumQueueDepth(m))} loading={metricsQ.isLoading} error={metricsQ.isError} sub="total waiting" />
        <KpiTile label="running" tone="amber" value={fmtCount(m?.jobsByStatus.RUNNING)} loading={metricsQ.isLoading} error={metricsQ.isError} />
        <KpiTile label="succeeded" tone="emerald" value={fmtCount(m?.jobsByStatus.SUCCEEDED)} loading={metricsQ.isLoading} error={metricsQ.isError} />
        <KpiTile label="failed" tone="rose" value={fmtCount((m?.jobsByStatus.FAILED ?? 0) + (m?.jobsByStatus.TIMED_OUT ?? 0))} loading={metricsQ.isLoading} error={metricsQ.isError} />
        <KpiTile label="workers online" tone="teal" value={fmtCount(m != null ? workersOnline(m) : undefined)} loading={metricsQ.isLoading} error={metricsQ.isError} sub="excl. offline/error" />
      </div>

      <div className="grid gap-3 lg:grid-cols-2">
        <Panel title="queue depth · by priority">
          {m == null ? (
            <div className="h-16 animate-pulse rounded-sm bg-panel-2/50" />
          ) : (
            <Bars<JobPriority>
              entries={(Object.entries(m.queueDepth) as [JobPriority, number][]).filter(([, v]) => v != null)}
              toneFor={(p) => QUEUE_TONES[p]}
              emptyLabel="queue depth not provided"
            />
          )}
        </Panel>

        <Panel title="jobs · by status">
          {m == null ? (
            <div className="h-16 animate-pulse rounded-sm bg-panel-2/50" />
          ) : (
            <Bars<JobStatus>
              entries={(Object.entries(m.jobsByStatus) as [JobStatus, number][]).filter(([, v]) => v != null)}
              toneFor={(s) => STATUS_TONES[s]}
              emptyLabel="status counts not provided"
            />
          )}
        </Panel>

        <Panel title="workers · by status">
          {m == null ? (
            <div className="h-16 animate-pulse rounded-sm bg-panel-2/50" />
          ) : (
            <Bars<WorkerStatus>
              entries={(Object.entries(m.workersByStatus) as [WorkerStatus, number][]).filter(([, v]) => v != null)}
              toneFor={(s) => WORKER_TONES[s]}
              emptyLabel="worker counts not provided"
            />
          )}
        </Panel>

        <Panel title="duration · succeeded, last 24h">
          <div className="grid grid-cols-3 gap-2">
            <KpiTile label="succeeded" tone="emerald" value={fmtCount(m?.succeededLast24h)} loading={metricsQ.isLoading} error={metricsQ.isError} sub="24h" />
            <KpiTile label="avg" tone="teal" value={fmtDuration(m?.avgDurationMs)} loading={metricsQ.isLoading} error={metricsQ.isError} />
            <KpiTile label="max" tone="amber" value={fmtDuration(m?.maxDurationMs)} loading={metricsQ.isLoading} error={metricsQ.isError} />
          </div>
          {m?.avgDurationMs == null && m?.maxDurationMs == null && !metricsQ.isLoading && (
            <p className="mt-2 text-center font-mono text-[10.5px] text-dim">duration metrics not provided by the API</p>
          )}
        </Panel>

        <Panel title="throughput · completed per hour, last 24h" className="lg:col-span-2">
          {m?.throughputPerHour != null ? (
            <>
              <Sparkline points={m.throughputPerHour} />
              <p className="mt-1 font-mono text-[10px] text-dim">
                {m.throughputPerHour.length} hourly buckets · peak{' '}
                {fmtCount(Math.max(...m.throughputPerHour))}/h · total{' '}
                {fmtCount(m.throughputPerHour.reduce((a, b) => a + b, 0))}
              </p>
            </>
          ) : m?.throughputPerHourScalar != null ? (
            <div className="flex flex-wrap items-center gap-4 py-2">
              <KpiTile
                label="avg completed / hour"
                tone="teal"
                value={m.throughputPerHourScalar.toLocaleString('en-US', { maximumFractionDigits: 2 })}
                loading={metricsQ.isLoading}
                error={metricsQ.isError}
              />
              <p className="max-w-md font-mono text-[10.5px] leading-4 text-dim">
                the API publishes a scalar average over the last 24h — no hourly series available, so the console renders
                a tile instead of fabricating a trend chart
              </p>
            </div>
          ) : metricsQ.isLoading ? (
            <div className="h-16 animate-pulse rounded-sm bg-panel-2/50" />
          ) : (
            <p className="py-4 text-center font-mono text-[11px] text-dim">
              throughput not provided by the API — tiles only, no fabricated charts
            </p>
          )}
        </Panel>
      </div>
    </div>
  );
}
