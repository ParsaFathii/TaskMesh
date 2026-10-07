import type { ReactNode } from 'react';
import type { JobPriority, JobStatus, LogLevel, WorkerStatus } from '../../types';
import { jobStatusVisual, logLevelVisual, priorityVisual, workerStatusVisual } from '../../lib/status';

/** Pulsing square status indicator (RUNNING pulses). */
export function StatusDot({ status, className = '' }: { status: JobStatus | WorkerStatus; className?: string }) {
  const visual = (jobStatusVisual as Record<string, { dot: string }>)[status] ??
    (workerStatusVisual as Record<string, { dot: string }>)[status];
  return (
    <>
      <span className={`inline-block h-2 w-2 shrink-0 rounded-[2px] ${visual?.dot ?? 'bg-mute'} ${className}`} aria-hidden="true" />
      <span className="sr-only">{status}</span>
    </>
  );
}

export function StatusBadge({ status, size = 'sm' }: { status: JobStatus; size?: 'sm' | 'md' }) {
  const v = jobStatusVisual[status];
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-sm border px-1.5 font-mono font-semibold uppercase tracking-[0.06em] ${v.chip} ${
        size === 'md' ? 'py-1 text-[11px]' : 'py-0.5 text-[10px]'
      }`}
    >
      <StatusDot status={status} />
      {status}
    </span>
  );
}

export function PriorityBadge({ priority }: { priority: JobPriority }) {
  const v = priorityVisual[priority];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-sm border px-1.5 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-[0.06em] ${v.chip}`}>
      <span className={`inline-block h-1.5 w-1.5 rounded-[1px] ${v.dot}`} aria-hidden="true" />
      {priority}
    </span>
  );
}

export function WorkerStatusBadge({ status }: { status: WorkerStatus }) {
  const v = workerStatusVisual[status];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-sm border px-1.5 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-[0.06em] ${v.chip}`}>
      <span className={`inline-block h-1.5 w-1.5 rounded-[1px] ${v.dot}`} aria-hidden="true" />
      {status}
    </span>
  );
}

export function LevelBadge({ level }: { level: LogLevel }) {
  const v = logLevelVisual[level];
  return (
    <span className={`inline-flex items-center rounded-sm border px-1 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-[0.06em] ${v.chip}`}>
      {level}
    </span>
  );
}

export function Chip({
  children,
  tone = 'line',
  title,
}: {
  children: ReactNode;
  tone?: 'line' | 'amber' | 'teal' | 'emerald' | 'rose' | 'yellow' | 'mute';
  title?: string;
}) {
  const tones: Record<string, string> = {
    line: 'border-line text-mute',
    amber: 'border-amber/40 text-amber',
    teal: 'border-teal/40 text-teal',
    emerald: 'border-emerald/40 text-emerald',
    rose: 'border-rose/40 text-rose',
    yellow: 'border-yellow/40 text-yellow',
    mute: 'border-mute/30 text-mute',
  };
  return (
    <span
      title={title}
      className={`inline-flex max-w-full items-center truncate rounded-sm border bg-transparent px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-[0.06em] ${tones[tone]}`}
    >
      {children}
    </span>
  );
}
