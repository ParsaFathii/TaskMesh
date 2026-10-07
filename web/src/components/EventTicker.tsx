import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useEventsStore, toneClass } from '../stores/events';
import { useConnectionStore } from '../stores/connection';
import { fmtClock, timeAgo } from '../lib/format';
import { useNow } from '../hooks/useNow';
import { IconPulse } from './icons';

/**
 * EventTicker — bottom console strip streaming WS events.
 * Horizontally scrolling like a stock tape; newest appended at the end;
 * auto-scrolls unless the user hovers / scrolls manually.
 */
export function EventTicker() {
  const ticker = useEventsStore((s) => s.ticker);
  const status = useConnectionStore((s) => s.status);
  const navigate = useNavigate();
  const scrollRef = useRef<HTMLDivElement>(null);
  const pausedRef = useRef(false);
  const now = useNow(1000);
  const [, forceRender] = useState(0);
  const lastCountRef = useRef(ticker.length);

  useEffect(() => {
    const el = scrollRef.current;
    if (el == null) return;
    if (ticker.length === lastCountRef.current) return;
    lastCountRef.current = ticker.length;
    if (!pausedRef.current) {
      requestAnimationFrame(() => {
        el.scrollLeft = el.scrollWidth;
      });
    }
    // trim to the last 40 items for DOM sanity
    if (ticker.length > 240) forceRender((n) => n + 1);
  }, [ticker]);

  const onScroll = () => {
    const el = scrollRef.current;
    if (el == null) return;
    const atEnd = el.scrollWidth - el.scrollLeft - el.clientWidth < 80;
    pausedRef.current = !atEnd;
  };

  const items = ticker.slice(-40);

  return (
    <footer
      className="fixed bottom-0 left-0 right-0 z-30 flex h-9 items-stretch border-t border-line bg-bg-deep/95 backdrop-blur md:left-16"
      aria-label="Live event stream"
    >
      <div className="flex shrink-0 items-center gap-2 border-r border-line/70 px-3">
        <IconPulse size={13} className={status === 'open' ? 'text-emerald' : 'text-amber animate-pulse-status'} />
        <span className="micro hidden text-mute sm:inline">events</span>
        <span className="tabular font-mono text-[10.5px] text-dim">{fmtClock(now)}</span>
      </div>

      <div
        ref={scrollRef}
        onScroll={onScroll}
        onMouseEnter={() => {
          pausedRef.current = true;
        }}
        onMouseLeave={() => {
          pausedRef.current = false;
        }}
        onFocus={() => {
          pausedRef.current = true;
        }}
        onBlur={() => {
          pausedRef.current = false;
        }}
        role="log"
        aria-live="off"
        className="flex flex-1 items-center gap-0 overflow-x-auto whitespace-nowrap scroll-thin"
      >
        {items.length === 0 ? (
          <span className="px-3 font-mono text-[10.5px] text-dim">
            {status === 'open' ? 'stream connected — waiting for events…' : 'waiting for event stream…'}
          </span>
        ) : (
          items.map((item) => {
            const clickable = item.jobId != null || item.workerId != null;
            const target = item.jobId != null ? `/jobs/${item.jobId}` : item.workerId != null ? `/workers/${item.workerId}` : null;
            return (
              <button
                key={item.id}
                type="button"
                disabled={!clickable}
                onClick={() => target != null && navigate(target)}
                title={`${item.kind} · ${item.text} · ${timeAgo(item.at, now)} ago`}
                className={`flex h-full items-center gap-2 border-r border-line/40 px-3 font-mono text-[10.5px] animate-ticker-in ${
                  clickable ? 'cursor-pointer hover:bg-panel-2' : 'cursor-default'
                }`}
              >
                <span className="tabular text-dim">{fmtClock(item.at)}</span>
                <span className={toneClass(item.tone)}>{item.kind}</span>
                <span className="max-w-[280px] truncate text-mute">{item.text}</span>
              </button>
            );
          })
        )}
      </div>
    </footer>
  );
}
