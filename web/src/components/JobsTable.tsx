import { Link } from 'react-router-dom';
import type { Job } from '../types';
import { StatusBadge, PriorityBadge } from './ui/Bits';
import { CopyButton } from './ui/CopyButton';
import { ProgressBar } from './ui/ProgressBar';
import { jobStatusVisual } from '../lib/status';
import { durationBetween, parseIso, shortId, timeAgo } from '../lib/format';
import { useEventsStore } from '../stores/events';

/**
 * Dense jobs table shared by Jobs / Operations / Project detail.
 * Rows flash amber when a WS job.updated event lands; RUNNING rows show a
 * live progress bar and duration.
 */
export function JobsTable({
  jobs,
  now,
  showProject = false,
  emptyLabel = 'no jobs match the current filters',
}: {
  jobs: Job[];
  now: number;
  showProject?: boolean;
  emptyLabel?: string;
}) {
  const flashedAt = useEventsStore((s) => s.flashedAt);
  const hasFlash = Object.keys(flashedAt).length > 0;

  if (jobs.length === 0) {
    return <p className="px-3 py-6 text-center font-mono text-[11.5px] text-dim">{emptyLabel}</p>;
  }

  return (
    <div className="overflow-x-auto scroll-thin">
      <table className="w-full min-w-[640px] text-left font-mono text-[11.5px]">
        <thead>
          <tr className="border-b border-line">
            {['id', 'type', 'priority', 'status', 'progress', 'duration', 'created', ...(showProject ? ['project'] : [])].map(
              (h) => (
                <th key={h} scope="col" className="micro px-3 py-2 text-mute">
                  {h}
                </th>
              ),
            )}
          </tr>
        </thead>
        <tbody>
          {jobs.map((job) => {
            const flashAt = flashedAt[job.id];
            const flashing = hasFlash && flashAt != null && now - flashAt < 2400;
            const running = job.status === 'RUNNING';
            const duration = running
              ? durationBetween(job.startedAt, new Date(now).toISOString())
              : durationBetween(job.startedAt, job.completedAt);
            return (
              <tr
                key={`${job.id}:${flashAt ?? 0}`}
                className={`border-b border-line/50 hover:bg-panel-2/70 ${flashing ? 'animate-row-flash' : ''}`}
              >
                <td className="px-3 py-2 whitespace-nowrap">
                  <Link to={`/jobs/${job.id}`} className="text-amber hover:underline" title={job.id}>
                    {shortId(job.id, 8)}
                  </Link>
                  <span className="ml-1 inline-flex translate-y-px align-middle">
                    <CopyButton value={job.id} label="job id" />
                  </span>
                </td>
                <td className="px-3 py-2 whitespace-nowrap text-ink">{job.type}</td>
                <td className="px-3 py-2 whitespace-nowrap">
                  <PriorityBadge priority={job.priority} />
                </td>
                <td className="px-3 py-2 whitespace-nowrap">
                  <StatusBadge status={job.status} />
                  {job.cancelRequested && running && (
                    <span className="ml-1.5 rounded-sm border border-yellow/45 bg-yellow/5 px-1 py-0.5 text-[9px] uppercase tracking-[0.06em] text-yellow">
                      cancel req
                    </span>
                  )}
                </td>
                <td className="w-[110px] px-3 py-2">
                  {running ? (
                    <div className="flex items-center gap-2">
                      <ProgressBar value={job.progress} className="w-16" />
                      <span className={`tabular text-[10.5px] ${jobStatusVisual.RUNNING.text}`}>
                        {job.progress != null ? `${job.progress}%` : '…'}
                      </span>
                    </div>
                  ) : (
                    <span className="text-dim">—</span>
                  )}
                </td>
                <td className="tabular px-3 py-2 whitespace-nowrap text-mute">
                  {running && duration != null ? (
                    <span className={jobStatusVisual.RUNNING.text}>{fmtLive(duration)}</span>
                  ) : (
                    fmtDurationShort(duration)
                  )}
                </td>
                <td className="px-3 py-2 whitespace-nowrap text-dim" title={new Date(job.createdAt).toISOString()}>
                  {timeAgo(parseIso(job.createdAt), now)}
                </td>
                {showProject && (
                  <td className="px-3 py-2 whitespace-nowrap">
                    {job.projectId != null ? (
                      <Link to={`/projects/${job.projectId}`} className="text-mute hover:text-ink">
                        {job.projectName ?? shortId(job.projectId, 8)}
                      </Link>
                    ) : (
                      <span className="text-dim">—</span>
                    )}
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function fmtLive(ms: number): string {
  return `${(ms / 1000).toFixed(0)}s`;
}

function fmtDurationShort(ms: number | null): string {
  if (ms == null) return '—';
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m${String(Math.floor(s % 60)).padStart(2, '0')}s`;
  return `${Math.floor(m / 60)}h${String(m % 60).padStart(2, '0')}m`;
}
