import type { JobPriority, JobStatus, LogLevel, WorkerStatus } from '../types';

export interface Visual {
  /** square dot background */
  dot: string;
  /** text color */
  text: string;
  /** badge chip border + text + faint tint */
  chip: string;
  pulse?: boolean;
}

export const jobStatusVisual: Record<JobStatus, Visual> = {
  QUEUED: { dot: 'bg-amber-dim', text: 'text-amber-dim', chip: 'border-amber-dim/45 bg-amber/5 text-amber-dim' },
  RUNNING: { dot: 'bg-amber animate-pulse-status', text: 'text-amber', chip: 'border-amber/55 bg-amber/5 text-amber', pulse: true },
  SUCCEEDED: { dot: 'bg-emerald', text: 'text-emerald', chip: 'border-emerald/45 bg-emerald/5 text-emerald' },
  FAILED: { dot: 'bg-rose', text: 'text-rose', chip: 'border-rose/45 bg-rose/5 text-rose' },
  RETRYING: { dot: 'bg-yellow', text: 'text-yellow', chip: 'border-yellow/45 bg-yellow/5 text-yellow' },
  TIMED_OUT: { dot: 'bg-orange', text: 'text-orange', chip: 'border-orange/45 bg-orange/5 text-orange' },
  CANCELLED: { dot: 'bg-mute', text: 'text-mute', chip: 'border-mute/35 bg-mute/5 text-mute' },
};

export const priorityVisual: Record<JobPriority, Visual> = {
  CRITICAL: { dot: 'bg-rose', text: 'text-rose', chip: 'border-rose/55 bg-rose/5 text-rose' },
  HIGH: { dot: 'bg-amber', text: 'text-amber', chip: 'border-amber/55 bg-amber/5 text-amber' },
  NORMAL: { dot: 'bg-teal', text: 'text-teal', chip: 'border-teal/45 bg-teal/5 text-teal' },
  LOW: { dot: 'bg-mute', text: 'text-mute', chip: 'border-mute/40 bg-mute/5 text-mute' },
};

export const workerStatusVisual: Record<WorkerStatus, Visual> = {
  STARTING: { dot: 'bg-teal', text: 'text-teal', chip: 'border-teal/45 bg-teal/5 text-teal', pulse: true },
  IDLE: { dot: 'bg-emerald', text: 'text-emerald', chip: 'border-emerald/45 bg-emerald/5 text-emerald' },
  BUSY: { dot: 'bg-amber animate-pulse-status', text: 'text-amber', chip: 'border-amber/55 bg-amber/5 text-amber', pulse: true },
  DRAINING: { dot: 'bg-yellow', text: 'text-yellow', chip: 'border-yellow/45 bg-yellow/5 text-yellow' },
  OFFLINE: { dot: 'bg-dim', text: 'text-dim', chip: 'border-dim/45 bg-dim/5 text-dim' },
  ERROR: { dot: 'bg-rose', text: 'text-rose', chip: 'border-rose/45 bg-rose/5 text-rose' },
};

export const logLevelVisual: Record<LogLevel, { text: string; chip: string }> = {
  DEBUG: { text: 'text-dim', chip: 'border-dim/40 text-dim' },
  INFO: { text: 'text-teal', chip: 'border-teal/45 text-teal' },
  WARN: { text: 'text-yellow', chip: 'border-yellow/45 text-yellow' },
  ERROR: { text: 'text-rose', chip: 'border-rose/45 text-rose' },
};

export const outcomeVisual: Record<string, Visual> = {
  SUCCEEDED: { dot: 'bg-emerald', text: 'text-emerald', chip: 'border-emerald/45 bg-emerald/5 text-emerald' },
  FAILED: { dot: 'bg-rose', text: 'text-rose', chip: 'border-rose/45 bg-rose/5 text-rose' },
  TIMED_OUT: { dot: 'bg-orange', text: 'text-orange', chip: 'border-orange/45 bg-orange/5 text-orange' },
  CANCELLED: { dot: 'bg-mute', text: 'text-mute', chip: 'border-mute/35 bg-mute/5 text-mute' },
  ABANDONED: { dot: 'bg-yellow', text: 'text-yellow', chip: 'border-yellow/45 bg-yellow/5 text-yellow' },
  RUNNING: { dot: 'bg-amber animate-pulse-status', text: 'text-amber', chip: 'border-amber/55 bg-amber/5 text-amber' },
};

export function outcomeVisualFor(outcome: string | null | undefined): Visual {
  if (!outcome) return { dot: 'bg-amber animate-pulse-status', text: 'text-amber', chip: 'border-amber/55 bg-amber/5 text-amber' };
  return outcomeVisual[outcome] ?? { dot: 'bg-line-bright', text: 'text-mute', chip: 'border-line-bright text-mute' };
}
