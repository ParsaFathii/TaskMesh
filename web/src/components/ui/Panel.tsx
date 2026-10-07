import type { ReactNode } from 'react';

/** Console panel: thin border, hairline header, amber square marker. */
export function Panel({
  title,
  actions,
  children,
  flush = false,
  className = '',
  bodyClassName = '',
  ariaLabel,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  /** body without padding (tables, lanes, consoles) */
  flush?: boolean;
  className?: string;
  bodyClassName?: string;
  ariaLabel?: string;
}) {
  return (
    <section className={`rounded-md border border-line bg-panel ${className}`} aria-label={ariaLabel}>
      {title != null && (
        <header className="flex min-h-[34px] flex-wrap items-center gap-2 border-b border-line/70 px-3 py-1.5">
          <span className="h-1.5 w-1.5 shrink-0 bg-amber" aria-hidden="true" />
          <h2 className="micro text-mute">{title}</h2>
          {actions != null && <div className="ml-auto flex flex-wrap items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={flush ? bodyClassName : `p-3 ${bodyClassName}`}>{children}</div>
    </section>
  );
}

/** Page heading: monospace uppercase title + optional actions on the right. */
export function PageHead({
  title,
  sub,
  actions,
}: {
  title: string;
  sub?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-3 flex flex-wrap items-end gap-x-4 gap-y-2">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className="h-2 w-2 bg-amber" aria-hidden="true" />
          <h1 className="font-mono text-sm font-bold uppercase tracking-[0.14em] text-ink">{title}</h1>
        </div>
        {sub != null && <div className="mt-1 pl-4 text-[11px] font-mono text-dim">{sub}</div>}
      </div>
      {actions != null && <div className="ml-auto flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
