import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useJobs, useMetrics, useWorkers } from '../api/hooks';
import { useAuthStore } from '../stores/auth';
import { isOperator } from '../lib/roles';
import { Panel, PageHead } from '../components/ui/Panel';
import { KpiTile } from '../components/KpiStrip';
import { QueueLanes } from '../components/QueueLanes';
import { WorkerTile } from '../components/WorkerTile';
import { JobsTable } from '../components/JobsTable';
import { EmptyState, ErrorPanel, TableSkeleton } from '../components/ui/States';
import { Button } from '../components/ui/Button';
import { useNow } from '../hooks/useNow';
import { fmtCount, timeAgo, parseIso } from '../lib/format';
import { IconLanes, IconCpu } from '../components/icons';

const DAY_MS = 24 * 60 * 60 * 1000;
const EMPTY_ITEMS: never[] = [];

/**
 * Operations — the control room.
 * ROLE-ADAPTIVE LAYOUT (documented in README):
 *  - ADMIN / OPERATOR: KPI strip from /metrics, live queue lanes, worker grid,
 *    plus the global WS event ticker (in the shell).
 *  - USER: own jobs only (the API filters for USER). KPIs are derived from the
 *    user's own job page, lanes show their own queued jobs; no workers /
 *    metrics sections — replaced by a personal recent-jobs panel.
 */
export default function OperationsPage() {
  const role = useAuthStore((s) => s.user?.role);
  const ops = isOperator(role);
  const now = useNow(1000);

  const metricsQ = useMetrics(ops);
  const lanesQ = useJobs({ status: ['QUEUED', 'RETRYING'], size: 100 });
  const workersQ = useWorkers(ops);
  const myJobsQ = useJobs({ size: 100, page: 0 }, !ops);

  const own = useMemo(() => myJobsQ.data?.items ?? EMPTY_ITEMS, [myJobsQ.data]);
  const ownStats = useMemo(() => {
    const dayAgo = now - DAY_MS;
    let queued = 0;
    let running = 0;
    let succeeded = 0;
    let failed = 0;
    let recent = 0;
    for (const job of own) {
      if (job.status === 'QUEUED' || job.status === 'RETRYING') queued += 1;
      else if (job.status === 'RUNNING') running += 1;
      else if (job.status === 'SUCCEEDED') succeeded += 1;
      else failed += 1;
      const created = parseIso(job.createdAt);
      if (created != null && created >= dayAgo) recent += 1;
    }
    return { queued, running, succeeded, failed, recent };
  }, [own, now]);

  const m = metricsQ.data;
  const queueDepthTotal =
    (m?.queueDepth.CRITICAL ?? 0) + (m?.queueDepth.HIGH ?? 0) + (m?.queueDepth.NORMAL ?? 0) + (m?.queueDepth.LOW ?? 0);
  const workersOnline = m
    ? Object.entries(m.workersByStatus).reduce(
        (acc, [status, count]) => (status === 'OFFLINE' || status === 'ERROR' ? acc : acc + (count ?? 0)),
        0,
      )
    : 0;

  const laneJobs = lanesQ.data?.items ?? [];
  const recentOwnJobs = own.slice(0, 10);

  return (
    <div className="space-y-3">
      <PageHead
        title="Operations"
        sub={
          ops
            ? 'live queue lanes · worker fleet · cluster metrics'
            : 'your queue lanes · your jobs · cluster events'
        }
        actions={
          <Link to="/jobs/new">
            <Button variant="primary" icon={<span aria-hidden="true">+</span>}>
              new job
            </Button>
          </Link>
        }
      />

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5" role="group" aria-label="Key indicators">
        {ops ? (
          <>
            <KpiTile label="queued" tone="amber" value={fmtCount(queueDepthTotal)} loading={metricsQ.isLoading} error={metricsQ.isError} title="Sum of queue depth by priority" />
            <KpiTile label="running" tone="amber" value={fmtCount(m?.jobsByStatus.RUNNING)} loading={metricsQ.isLoading} error={metricsQ.isError} />
            <KpiTile
              label="completed · 24h"
              tone="emerald"
              value={fmtCount(m?.succeededLast24h ?? m?.jobsByStatus.SUCCEEDED)}
              sub={m?.succeededLast24h != null ? 'succeeded last 24h' : 'all-time succeeded'}
              loading={metricsQ.isLoading}
              error={metricsQ.isError}
            />
            <KpiTile label="failed" tone="rose" value={fmtCount((m?.jobsByStatus.FAILED ?? 0) + (m?.jobsByStatus.TIMED_OUT ?? 0))} sub={m?.jobsByStatus.TIMED_OUT != null ? `${fmtCount(m?.jobsByStatus.TIMED_OUT)} timed out` : undefined} loading={metricsQ.isLoading} error={metricsQ.isError} />
            <KpiTile label="workers online" tone="teal" value={fmtCount(workersOnline)} loading={metricsQ.isLoading || workersQ.isLoading} error={metricsQ.isError && workersQ.isError} />
          </>
        ) : (
          <>
            <KpiTile label="my queued" tone="amber" value={fmtCount(ownStats.queued)} loading={myJobsQ.isLoading} error={myJobsQ.isError} />
            <KpiTile label="my running" tone="amber" value={fmtCount(ownStats.running)} loading={myJobsQ.isLoading} error={myJobsQ.isError} />
            <KpiTile label="my succeeded" tone="emerald" value={fmtCount(ownStats.succeeded)} loading={myJobsQ.isLoading} error={myJobsQ.isError} />
            <KpiTile label="my failed" tone="rose" value={fmtCount(ownStats.failed)} loading={myJobsQ.isLoading} error={myJobsQ.isError} />
            <KpiTile label="my jobs 24h" tone="teal" value={fmtCount(ownStats.recent)} loading={myJobsQ.isLoading} error={myJobsQ.isError} />
          </>
        )}
      </div>

      {ops && metricsQ.isError && <ErrorPanel err={metricsQ.error} onRetry={() => void metricsQ.refetch()} compact />}

      <Panel
        title="queue lanes · live"
        flush
        actions={
          <span className="micro text-dim">
            {lanesQ.data != null ? `${fmtCount(lanesQ.data.total)} waiting` : '…'}
          </span>
        }
      >
        {lanesQ.isLoading ? (
          <div className="px-3 py-2">
            <TableSkeleton rows={4} cols={3} />
          </div>
        ) : lanesQ.isError ? (
          <div className="p-3">
            <ErrorPanel err={lanesQ.error} onRetry={() => void lanesQ.refetch()} />
          </div>
        ) : (
          <QueueLanes jobs={laneJobs} depth={ops ? m?.queueDepth : undefined} overflowTotal={lanesQ.data?.total} />
        )}
        <p className="border-t border-line/60 px-3 py-1.5 font-mono text-[10px] text-dim">
          lanes ordered by claim priority · aging guarantees no low-priority starvation beyond ~1 rank/hour
        </p>
      </Panel>

      {ops ? (
        <Panel
          title="worker fleet"
          flush={false}
          actions={
            <Link to="/workers">
              <Button size="sm" variant="secondary" icon={<IconCpu size={12} />}>
                all workers
              </Button>
            </Link>
          }
        >
          {workersQ.isLoading ? (
            <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="h-[128px] animate-pulse rounded-md border border-line bg-panel-2/50" />
              ))}
            </div>
          ) : workersQ.isError ? (
            <ErrorPanel err={workersQ.error} onRetry={() => void workersQ.refetch()} />
          ) : (workersQ.data?.length ?? 0) === 0 ? (
            <EmptyState
              icon={<IconCpu size={22} />}
              title="no workers registered"
              hint="start a worker: python -m taskmesh_worker (registers itself via heartbeat)"
            />
          ) : (
            <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
              {(workersQ.data ?? []).slice(0, 8).map((worker) => (
                <WorkerTile key={worker.id} worker={worker} now={now} />
              ))}
            </div>
          )}
        </Panel>
      ) : (
        <Panel
          title="my recent jobs"
          flush
          actions={
            <Link to="/jobs">
              <Button size="sm" variant="secondary">
                all my jobs
              </Button>
            </Link>
          }
        >
          {myJobsQ.isLoading ? (
            <TableSkeleton rows={5} />
          ) : myJobsQ.isError ? (
            <div className="p-3">
              <ErrorPanel err={myJobsQ.error} onRetry={() => void myJobsQ.refetch()} />
            </div>
          ) : recentOwnJobs.length === 0 ? (
            <EmptyState
              icon={<IconLanes size={22} />}
              title="you have no jobs yet"
              hint="submit your first job — the payload form is generated from the live type catalog"
              action={
                <Link to="/jobs/new">
                  <Button size="sm" variant="primary">
                    new job
                  </Button>
                </Link>
              }
            />
          ) : (
            <JobsTable jobs={recentOwnJobs} now={now} showProject />
          )}
          {recentOwnJobs.length > 0 && (
            <p className="border-t border-line/60 px-3 py-1.5 font-mono text-[10px] text-dim">
              latest created {timeAgo(parseIso(recentOwnJobs[0]?.createdAt), now)} ago · view restricted to your jobs by the API
            </p>
          )}
        </Panel>
      )}
    </div>
  );
}
