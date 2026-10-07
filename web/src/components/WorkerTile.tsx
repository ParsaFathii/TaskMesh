import { Link } from 'react-router-dom';
import type { Worker } from '../types';
import { workerStatusVisual } from '../lib/status';
import { shortId, timeAgo, parseIso } from '../lib/format';
import { Chip } from './ui/Bits';

function heartbeatTone(worker: Worker, now: number): { text: string; label: string } {
  const hb = parseIso(worker.lastHeartbeat);
  const ageS = hb != null ? (now - hb) / 1000 : Infinity;
  const interval = Math.max(1, worker.heartbeatIntervalS);
  if (ageS > interval * 3) return { text: 'text-rose', label: 'stale' };
  if (ageS > interval * 2) return { text: 'text-yellow', label: 'late' };
  return { text: 'text-emerald', label: 'ok' };
}

/**
 * WorkerTile — worker card with a state ring (SVG), live heartbeat age
 * counter, current job link and capability chips. Stale workers get a
 * dashed border + warning tag.
 */
export function WorkerTile({ worker, now, showJobLink = true }: { worker: Worker; now: number; showJobLink?: boolean }) {
  const v = workerStatusVisual[worker.status];
  const hb = heartbeatTone(worker, now);
  const started = parseIso(worker.startedAt);
  const stale = worker.stale === true;
  const ringColor =
    worker.status === 'IDLE' ? 'var(--color-emerald)'
    : worker.status === 'BUSY' ? 'var(--color-amber)'
    : worker.status === 'STARTING' ? 'var(--color-teal)'
    : worker.status === 'DRAINING' ? 'var(--color-yellow)'
    : worker.status === 'ERROR' ? 'var(--color-rose)'
    : 'var(--color-dim)';

  return (
    <article
      className={`rounded-md border bg-panel p-3 ${stale ? 'border-dashed border-yellow/50' : 'border-line'}`}
      aria-label={`Worker ${worker.name}`}
    >
      <div className="flex items-start gap-3">
        <div className="relative shrink-0" aria-hidden="true">
          <svg width={46} height={46} viewBox="0 0 46 46">
            <rect x={3} y={3} width={40} height={40} rx={6} fill="none" stroke="var(--color-line)" strokeWidth={1.5} />
            <rect
              x={3}
              y={3}
              width={40}
              height={40}
              rx={6}
              fill="none"
              stroke={ringColor}
              strokeWidth={2.5}
              strokeDasharray="48 128"
              strokeLinecap="butt"
              className={v.pulse ? 'animate-pulse-status' : ''}
              transform="rotate(-90 23 23)"
            />
          </svg>
          <span className={`absolute inset-0 flex items-center justify-center font-mono text-[8px] font-bold uppercase tracking-tight ${v.text}`}>
            {worker.status.slice(0, 6)}
          </span>
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <h3 className="truncate font-mono text-[12.5px] font-semibold text-ink" title={worker.id}>
              {worker.name}
            </h3>
            {stale && (
              <Chip tone="yellow" title={`No heartbeat for > ${worker.heartbeatIntervalS * 3}s`}>
                stale
              </Chip>
            )}
          </div>
          <p className="truncate font-mono text-[10.5px] text-dim">
            {worker.hostname} · v{worker.version}
          </p>
          <p className={`mt-1.5 font-mono text-[10.5px] ${hb.text}`}>
            hb {timeAgo(parseIso(worker.lastHeartbeat), now)} · {hb.label}
          </p>
        </div>
      </div>

      <div className="mt-2.5 flex flex-wrap items-center gap-1">
        {worker.capabilities.slice(0, 4).map((cap) => (
          <Chip key={cap} tone="teal">
            {cap}
          </Chip>
        ))}
        {worker.capabilities.length > 4 && <Chip tone="mute">+{worker.capabilities.length - 4}</Chip>}
        {worker.capabilities.length === 0 && <Chip tone="mute">no capabilities</Chip>}
      </div>

      <div className="mt-2.5 flex items-center justify-between border-t border-line/60 pt-2 font-mono text-[10.5px]">
        <span className="text-dim">
          up {started != null ? timeAgo(started, now) : '—'}
        </span>
        {showJobLink && worker.currentJobId != null ? (
          <Link
            to={`/jobs/${worker.currentJobId}`}
            className="text-amber hover:underline"
            title={`Current job ${worker.currentJobId}`}
          >
            job {shortId(worker.currentJobId, 6)} →
          </Link>
        ) : (
          <span className="text-dim">no current job</span>
        )}
      </div>
    </article>
  );
}
