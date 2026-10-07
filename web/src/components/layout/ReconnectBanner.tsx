import { useConnectionStore } from '../../stores/connection';

/**
 * Amber banner while the WS stream reconnects, red when it is down.
 * Hidden while the first connect is still in flight (no noise on load).
 */
export function ReconnectBanner() {
  const { status, attempt } = useConnectionStore();

  if (status === 'open' || status === 'connecting' || status === 'idle') return null;

  const down = status === 'closed';
  return (
    <div
      role="status"
      aria-live="polite"
      className={`fixed left-0 right-0 top-12 z-40 flex h-7 items-center justify-center gap-2 border-b font-mono text-[10.5px] uppercase tracking-[0.1em] animate-banner-in ${
        down
          ? 'border-rose/40 bg-rose/10 text-rose'
          : 'border-amber/40 bg-amber/10 text-amber'
      } md:left-16`}
    >
      <span className="h-1.5 w-1.5 animate-pulse-status bg-current" aria-hidden="true" />
      {down
        ? 'event stream down — live updates paused'
        : `reconnecting to event stream · attempt ${attempt}`}
    </div>
  );
}
