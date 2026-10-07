import { Link } from 'react-router-dom';
import type { Job, JobPriority } from '../types';
import { JOB_PRIORITIES } from '../types';
import { priorityVisual } from '../lib/status';
import { shortId, timeAgo, parseIso } from '../lib/format';
import { useEventsStore } from '../stores/events';
import { useNow } from '../hooks/useNow';

const LANE_TINT: Record<JobPriority, string> = {
  CRITICAL: 'bg-rose/[0.035]',
  HIGH: 'bg-amber/[0.03]',
  NORMAL: 'bg-teal/[0.02]',
  LOW: 'bg-panel/0',
};

/**
 * QueueLanes — the signature motif: four horizontal lanes (CRITICAL / HIGH /
 * NORMAL / LOW), each with a live count and horizontally scrolling job chips.
 * Aged chips fade toward the lane end. Flash highlight on WS updates.
 */
export function QueueLanes({
  jobs,
  depth,
  overflowTotal,
}: {
  jobs: Job[];
  /** authoritative lane counts from /metrics (OPERATOR+), if available */
  depth?: Partial<Record<JobPriority, number>>;
  /** server-side total of queued+retrying jobs (may exceed the fetched page) */
  overflowTotal?: number;
}) {
  const now = useNow(2000);
  const flashedAt = useEventsStore((s) => s.flashedAt);

  return (
    <div data-testid="queue-lanes">
      {JOB_PRIORITIES.slice()
        .reverse()
        .map((lane) => {
          const laneJobs = jobs.filter((j) => j.priority === lane);
          const count = depth?.[lane] ?? laneJobs.length;
          const oldest = laneJobs.reduce<number | null>((acc, j) => {
            const t = parseIso(j.createdAt);
            return t != null && (acc == null || t < acc) ? t : acc;
          }, null);
          const v = priorityVisual[lane];

          return (
            <div key={lane} className={`border-b border-line/60 last:border-b-0 ${LANE_TINT[lane]}`}>
              <div className="flex items-center gap-2.5 px-3 py-1.5">
                <span className={`inline-block h-2 w-2 rounded-[2px] ${v.dot}`} aria-hidden="true" />
                <span className="micro w-[64px] text-ink">{lane}</span>
                <span className={`tabular font-mono text-[14px] font-bold ${v.text}`}>{count}</span>
                <span className="micro text-dim">in lane</span>
                <span className="ml-auto micro hidden text-dim sm:inline">
                  {oldest != null ? `oldest ${timeAgo(oldest, now)}` : ''}
                </span>
              </div>
              <div
                data-lane={lane}
                className="flex min-h-[36px] items-center gap-1.5 overflow-x-auto px-3 pb-2 scroll-thin"
              >
                {laneJobs.length === 0 ? (
                  <span className="font-mono text-[10.5px] text-dim">lane empty</span>
                ) : (
                  laneJobs.map((job) => {
                    const flashAt = flashedAt[job.id];
                    const flashKey = flashAt ?? 0;
                    const createdAt = parseIso(job.createdAt);
                    const age = createdAt != null ? timeAgo(createdAt, now) : '—';
                    return (
                      <Link
                        key={`${job.id}:${flashKey}`}
                        to={`/jobs/${job.id}`}
                        data-job-id={job.id}
                        title={`${job.type} · ${job.id} · ${job.status} · created ${age} ago`}
                        className="inline-flex shrink-0 items-center gap-1.5 rounded-sm border border-line bg-panel/80 px-2 py-1 font-mono text-[10.5px] text-ink animate-ticker-in hover:border-amber/50 hover:bg-panel-2"
                      >
                        <span className={`inline-block h-1.5 w-1.5 rounded-[1px] ${
                          job.status === 'RETRYING' ? 'bg-yellow' : 'bg-amber-dim'
                        }`} aria-hidden="true" />
                        <span className="text-ink">{job.type}</span>
                        <span className="text-dim">·</span>
                        <span className="text-mute">{shortId(job.id, 6)}</span>
                        <span className="text-dim">{age}</span>
                      </Link>
                    );
                  })
                )}
                {overflowTotal != null && overflowTotal > jobs.length && (
                  <Link
                    to="/jobs?status=QUEUED&status=RETRYING"
                    className="inline-flex shrink-0 items-center rounded-sm border border-dashed border-line-bright px-2 py-1 font-mono text-[10.5px] text-mute hover:border-amber/40 hover:text-amber"
                  >
                    +{(overflowTotal - jobs.length).toLocaleString('en-US')} more
                  </Link>
                )}
              </div>
            </div>
          );
        })}
    </div>
  );
}
