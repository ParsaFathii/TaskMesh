import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/auth';
import { useUiStore, type ContextChip } from '../../stores/ui';
import { ConnectionLed } from './ConnectionLed';
import { IconChevronRight, IconLogout, IconSliders, IconUser } from '../icons';
import { useLogout } from '../../api/hooks';

interface Crumb {
  label: string;
  to?: string;
}

const ROUTE_LABELS: Record<string, string> = {
  projects: 'Projects',
  jobs: 'Jobs',
  workers: 'Workers',
  logs: 'Audit',
  metrics: 'Metrics',
  settings: 'Settings',
  new: 'New job',
};

const CHIP_TONES: Record<ContextChip['tone'], string> = {
  amber: 'border-amber/40 text-amber',
  emerald: 'border-emerald/40 text-emerald',
  teal: 'border-teal/40 text-teal',
  rose: 'border-rose/40 text-rose',
  yellow: 'border-yellow/40 text-yellow',
  mute: 'border-mute/40 text-mute',
};

function crumbsFor(pathname: string): Crumb[] {
  const segs = pathname.split('/').filter(Boolean);
  if (segs.length === 0) return [{ label: 'Operations' }];
  const out: Crumb[] = [{ label: 'Ops', to: '/' }];
  let acc = '';
  segs.forEach((seg, i) => {
    acc += `/${seg}`;
    const label = ROUTE_LABELS[seg] ?? (seg.length > 12 ? `${seg.slice(0, 10)}…` : seg);
    const isLast = i === segs.length - 1;
    out.push({ label, to: isLast ? undefined : acc });
  });
  return out;
}

function UserMenu() {
  const user = useAuthStore((s) => s.user);
  const logout = useLogout();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (ref.current != null && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (!user) return null;

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex h-7 items-center gap-2 rounded-sm border border-line px-2 font-mono text-[11px] text-ink hover:border-line-bright hover:bg-panel-2"
      >
        <span className="h-1.5 w-1.5 rounded-[1px] bg-teal" aria-hidden="true" />
        <span className="max-w-[120px] truncate">{user.username}</span>
        <span className="hidden rounded-[2px] border border-amber/40 bg-amber/5 px-1 text-[9px] uppercase tracking-[0.08em] text-amber sm:inline">
          {user.role}
        </span>
      </button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 top-9 z-50 w-44 rounded-sm border border-line-bright bg-panel py-1 shadow-[0_8px_28px_rgba(0,0,0,0.5)]"
        >
          <div className="border-b border-line/60 px-3 py-1.5">
            <p className="font-mono text-[11px] text-ink">{user.username}</p>
            <p className="micro text-dim">{user.id.slice(0, 13)}…</p>
          </div>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              navigate('/settings');
            }}
            className="flex w-full items-center gap-2 px-3 py-1.5 font-mono text-[11px] text-mute hover:bg-panel-2 hover:text-ink"
          >
            <IconSliders size={13} /> settings
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) });
            }}
            className="flex w-full items-center gap-2 px-3 py-1.5 font-mono text-[11px] text-mute hover:bg-rose/10 hover:text-rose"
          >
            <IconLogout size={13} /> log out
          </button>
        </div>
      )}
    </div>
  );
}

/** Top context bar: breadcrumb + page context chips + connection LED + user menu. */
export function TopBar() {
  const { pathname } = useLocation();
  const crumbs = crumbsFor(pathname);
  const { chipPath, chips } = useUiStore();
  const visibleChips = chipPath === pathname ? chips : [];

  return (
    <header className="fixed left-0 right-0 top-0 z-40 flex h-12 items-center gap-2 border-b border-line bg-bg-deep/90 px-3 backdrop-blur md:left-16">
      <nav aria-label="Breadcrumb" className="min-w-0 flex-1">
        <ol className="flex min-w-0 items-center gap-1 font-mono text-[10.5px] uppercase tracking-[0.08em]">
          {crumbs.map((c, i) => (
            <li key={`${c.label}-${i}`} className="flex min-w-0 items-center gap-1">
              {i > 0 && <IconChevronRight size={10} className="shrink-0 text-dim" />}
              {c.to != null ? (
                <Link to={c.to} className="truncate text-mute hover:text-ink">
                  {c.label}
                </Link>
              ) : (
                <span className="truncate text-ink" aria-current="page">
                  {c.label}
                </span>
              )}
            </li>
          ))}
        </ol>
      </nav>

      {visibleChips.length > 0 && (
        <div className="hidden items-center gap-1.5 md:flex" aria-label="Page context">
          {visibleChips.map((chip) => (
            <span
              key={chip.label}
              className={`max-w-[200px] truncate rounded-sm border bg-panel px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-[0.06em] ${CHIP_TONES[chip.tone]}`}
            >
              {chip.label}
            </span>
          ))}
        </div>
      )}

      <ConnectionLed />
      <UserMenu />
      <span className="sr-only">
        <IconUser size={1} />
      </span>
    </header>
  );
}
