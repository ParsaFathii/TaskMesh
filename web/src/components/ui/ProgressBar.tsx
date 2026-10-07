/** Thin monospace progress bar for RUNNING jobs (0..100). */
export function ProgressBar({ value, className = '' }: { value: number | null | undefined; className?: string }) {
  const pct = value == null ? 0 : Math.max(0, Math.min(100, Math.round(value)));
  const indeterminate = value == null;
  return (
    <div
      className={`h-1.5 w-full overflow-hidden rounded-[2px] border border-line bg-bg-deep ${className}`}
      role="progressbar"
      aria-valuenow={indeterminate ? undefined : pct}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label={indeterminate ? 'progress pending' : `progress ${pct}%`}
    >
      <div
        className={`h-full bg-amber transition-[width] duration-500 ${indeterminate ? 'w-[8%] animate-pulse-status' : ''}`}
        style={indeterminate ? undefined : { width: `${pct}%` }}
      />
    </div>
  );
}
