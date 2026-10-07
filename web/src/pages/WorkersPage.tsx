import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useWorkers } from '../api/hooks';
import { RoleGate } from '../components/layout/RequireAuth';
import { Panel, PageHead } from '../components/ui/Panel';
import { WorkerTile } from '../components/WorkerTile';
import { EmptyState, ErrorPanel, TableSkeleton } from '../components/ui/States';
import { Button } from '../components/ui/Button';
import { Chip, WorkerStatusBadge } from '../components/ui/Bits';
import { useNow } from '../hooks/useNow';
import { fmtDateTime, shortId, timeAgo, parseIso } from '../lib/format';
import { IconCpu, IconGrid, IconTable } from '../components/icons';

export default function WorkersPage() {
  return (
    <RoleGate roles={['OPERATOR', 'ADMIN']}>
      <WorkersContent />
    </RoleGate>
  );
}

function WorkersContent() {
  const workersQ = useWorkers();
  const now = useNow(1000);
  const [view, setView] = useState<'grid' | 'table'>('grid');
  const [staleOnly, setStaleOnly] = useState(false);

  const workers = (workersQ.data ?? []).filter((w) => !staleOnly || w.stale === true);

  return (
    <div className="space-y-3">
      <PageHead
        title="Workers"
        sub={`fleet registry · heartbeat window = 3 × interval · stale flagged ${staleOnly ? '· filtered' : ''}`}
        actions={
          <>
            <Button
              size="sm"
              variant={staleOnly ? 'primary' : 'secondary'}
              onClick={() => setStaleOnly((s) => !s)}
              aria-pressed={staleOnly}
            >
              stale only
            </Button>
            <div className="flex overflow-hidden rounded-sm border border-line" role="group" aria-label="View mode">
              {(['grid', 'table'] as const).map((v) => (
                <button
                  key={v}
                  type="button"
                  aria-pressed={view === v}
                  onClick={() => setView(v)}
                  className={`flex h-7 items-center gap-1 px-2 font-mono text-[10px] uppercase tracking-[0.08em] ${
                    view === v ? 'bg-panel-2 text-amber' : 'text-mute hover:text-ink'
                  }`}
                >
                  {v === 'grid' ? <IconGrid size={12} /> : <IconTable size={12} />}
                  {v}
                </button>
              ))}
            </div>
          </>
        }
      />

      <Panel
        title={`workers · ${(workersQ.data ?? []).length} registered · ${
          (workersQ.data ?? []).filter((w) => w.stale === true).length
        } stale`}
        flush={view === 'table'}
      >
        {workersQ.isLoading ? (
          view === 'grid' ? (
            <div className="grid gap-2 p-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="h-[128px] animate-pulse rounded-md border border-line bg-panel-2/50" />
              ))}
            </div>
          ) : (
            <TableSkeleton rows={6} cols={6} />
          )
        ) : workersQ.isError ? (
          <div className="p-3">
            <ErrorPanel err={workersQ.error} onRetry={() => void workersQ.refetch()} />
          </div>
        ) : workers.length === 0 ? (
          <EmptyState
            icon={<IconCpu size={22} />}
            title={staleOnly ? 'no stale workers' : 'no workers registered'}
            hint="launch one with: python -m taskmesh_worker — it self-registers and heartbeats every 10s"
          />
        ) : view === 'grid' ? (
          <div className="grid gap-2 p-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
            {workers.map((worker) => (
              <Link key={worker.id} to={`/workers/${worker.id}`} className="rounded-md focus-visible:outline-2 focus-visible:outline-amber">
                <WorkerTile worker={worker} now={now} showJobLink />
              </Link>
            ))}
          </div>
        ) : (
          <div className="overflow-x-auto scroll-thin">
            <table className="w-full min-w-[760px] text-left font-mono text-[11.5px]">
              <thead>
                <tr className="border-b border-line">
                  {['id', 'name', 'hostname', 'status', 'capabilities', 'heartbeat', 'current job', 'up since'].map((h) => (
                    <th key={h} scope="col" className="micro px-3 py-2 text-mute">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {workers.map((worker) => (
                  <tr
                    key={worker.id}
                    className={`border-b border-line/50 hover:bg-panel-2/70 ${worker.stale === true ? 'bg-yellow/[0.04]' : ''}`}
                  >
                    <td className="px-3 py-2 whitespace-nowrap">
                      <Link to={`/workers/${worker.id}`} className="text-amber hover:underline">
                        {shortId(worker.id, 8)}
                      </Link>
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap text-ink">
                      {worker.name}
                      {worker.stale === true && (
                        <span className="ml-2 rounded-sm border border-yellow/45 bg-yellow/5 px-1 py-0.5 text-[9px] uppercase tracking-[0.06em] text-yellow">
                          stale
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap text-mute">{worker.hostname}</td>
                    <td className="px-3 py-2 whitespace-nowrap">
                      <WorkerStatusBadge status={worker.status} />
                    </td>
                    <td className="max-w-[220px] px-3 py-2">
                      <span className="flex flex-wrap gap-1">
                        {worker.capabilities.slice(0, 3).map((c) => (
                          <Chip key={c} tone="teal">
                            {c}
                          </Chip>
                        ))}
                        {worker.capabilities.length > 3 && <Chip tone="mute">+{worker.capabilities.length - 3}</Chip>}
                      </span>
                    </td>
                    <td className="tabular px-3 py-2 whitespace-nowrap text-mute">
                      {timeAgo(parseIso(worker.lastHeartbeat), now)}
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap">
                      {worker.currentJobId != null ? (
                        <Link to={`/jobs/${worker.currentJobId}`} className="text-amber hover:underline">
                          {shortId(worker.currentJobId, 6)}
                        </Link>
                      ) : (
                        <span className="text-dim">—</span>
                      )}
                    </td>
                    <td className="tabular px-3 py-2 whitespace-nowrap text-dim" title={fmtDateTime(worker.startedAt)}>
                      {timeAgo(parseIso(worker.startedAt), now)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
      <p className="px-1 font-mono text-[10px] leading-4 text-dim">
        worker rows include a server-computed <span className="text-mute">stale</span> flag (last_heartbeat older than 3
        × heartbeat_interval_s) · GET /api/v1/workers · refetch 15s + WS invalidation
      </p>
    </div>
  );
}
