import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { IconAlert, IconRefresh, IconLock, IconSearch } from '../icons';
import { Button } from './Button';
import type { UserRole } from '../../types';

/** Deterministic skeleton rows for dense tables. */
export function TableSkeleton({ rows = 6, cols = 6 }: { rows?: number; cols?: number }) {
  const widths = ['w-24', 'w-28', 'w-16', 'w-20', 'w-14', 'w-16', 'w-20', 'w-12'];
  return (
    <div className="divide-y divide-line/50" aria-hidden="true">
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} className="flex items-center gap-3 px-3 py-2.5">
          {Array.from({ length: cols }).map((_, c) => (
            <div key={c} className={`h-3 animate-pulse rounded-sm bg-panel-2 ${widths[c % widths.length]}`} />
          ))}
        </div>
      ))}
    </div>
  );
}

export function LinesSkeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="space-y-2" aria-hidden="true">
      {Array.from({ length: lines }).map((_, i) => (
        <div key={i} className="h-3 animate-pulse rounded-sm bg-panel-2" style={{ width: `${70 + ((i * 13) % 28)}%` }} />
      ))}
    </div>
  );
}

export function EmptyState({
  icon,
  title,
  hint,
  action,
  children,
}: {
  icon?: ReactNode;
  title: string;
  hint?: ReactNode;
  action?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-4 py-10 text-center">
      <span className="text-dim" aria-hidden="true">
        {icon ?? <IconSearch size={22} />}
      </span>
      <p className="font-mono text-[12px] text-mute">{title}</p>
      {hint != null && <p className="max-w-md font-mono text-[10.5px] leading-4 text-dim">{hint}</p>}
      {children}
      {action}
    </div>
  );
}

/** Error panel with a retry button; renders nothing when err == null. */
export function ErrorPanel({
  err,
  onRetry,
  compact = false,
}: {
  err: unknown;
  onRetry?: () => void;
  compact?: boolean;
}) {
  if (err == null) return null;
  const message = err instanceof Error ? err.message : 'Something went wrong';
  const code = (err as { code?: string }).code;
  const status = (err as { status?: number }).status;
  return (
    <div
      role="alert"
      className={`flex flex-wrap items-center gap-3 rounded-sm border border-rose/40 bg-rose/5 ${compact ? 'px-3 py-2' : 'px-4 py-3'}`}
    >
      <IconAlert size={16} className="shrink-0 text-rose" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="micro text-rose">request failed</span>
          {code != null && (
            <span className="rounded-sm border border-rose/40 px-1 py-0.5 font-mono text-[10px] uppercase text-rose">
              {code}
              {status ? ` · ${status}` : ''}
            </span>
          )}
        </div>
        <p className="mt-0.5 font-mono text-[11.5px] leading-4 text-mute break-words">{message}</p>
      </div>
      {onRetry != null && (
        <Button size="sm" variant="secondary" onClick={onRetry} icon={<IconRefresh size={12} />}>
          retry
        </Button>
      )}
    </div>
  );
}

/** Role gate notice — UI-only guard; the backend always enforces authorization. */
export function ForbiddenPanel({ required, current }: { required: UserRole[]; current: UserRole }) {
  return (
    <div className="panel-403 mx-auto max-w-xl px-4 py-10 text-center">
      <div className="mx-auto flex h-10 w-10 items-center justify-center rounded-sm border border-line-bright text-mute" aria-hidden="true">
        <IconLock size={18} />
      </div>
      <h2 className="mt-3 font-mono text-sm font-bold uppercase tracking-[0.12em] text-ink">access restricted</h2>
      <p className="mt-2 font-mono text-[11.5px] leading-5 text-mute">
        This section requires the <span className="text-amber">{required.join(' or ')}</span> role.
      </p>
      <p className="mt-1 font-mono text-[10.5px] text-dim">
        Your role is <span className="text-mute">{current}</span>. Authorization is enforced by the API — this is not
        merely hidden in the UI.
      </p>
      <Link
        to="/"
        className="mt-4 inline-flex h-8 items-center rounded-sm border border-line px-3 font-mono text-[11px] font-semibold uppercase tracking-[0.08em] text-ink hover:border-line-bright hover:bg-panel-2"
      >
        back to operations
      </Link>
    </div>
  );
}
