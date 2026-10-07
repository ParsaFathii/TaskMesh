import { useConnectionStore } from '../../stores/connection';
import { useNow } from '../../hooks/useNow';
import { timeAgo } from '../../lib/format';

/** Connection LED: green steady (open), amber pulse (connecting/reconnecting), red (closed). */
export function ConnectionLed() {
  const { status, attempt, lastEventAt, server } = useConnectionStore();
  const now = useNow(1000);

  let color = 'bg-dim';
  let label = 'idle';
  let pulse = false;

  switch (status) {
    case 'open':
      color = 'bg-emerald';
      label = 'live';
      break;
    case 'connecting':
      color = 'bg-amber animate-pulse-status';
      label = 'connecting';
      pulse = true;
      break;
    case 'reconnecting':
      color = 'bg-amber animate-pulse-status';
      label = `reconnecting ${attempt}`;
      pulse = true;
      break;
    case 'closed':
      color = 'bg-rose';
      label = 'down';
      break;
    default:
      color = 'bg-dim';
      label = 'idle';
  }

  const age = lastEventAt != null ? timeAgo(lastEventAt, now) : null;
  const title =
    `WS stream: ${label}` +
    (server ? ` · ${server}` : '') +
    (age ? ` · last event ${age} ago` : ' · no events yet');

  return (
    <span className="inline-flex items-center gap-1.5" title={title}>
      <span
        className={`inline-block h-2 w-2 rounded-[2px] ${color} ${pulse ? '' : ''}`}
        role="status"
        aria-label={`WebSocket ${label}`}
      />
      <span className="micro hidden text-mute lg:inline">{label}</span>
    </span>
  );
}
