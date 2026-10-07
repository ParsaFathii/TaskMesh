import { NavLink, Link } from 'react-router-dom';
import { useAuthStore } from '../../stores/auth';
import {
  IconCpu,
  IconChart,
  IconFolder,
  IconLanes,
  IconLogout,
  IconPlus,
  IconSliders,
  IconStack,
  IconTerminal,
  LogoMark,
} from '../icons';
import { useLogout } from '../../api/hooks';
import { useNavigate } from 'react-router-dom';

interface RailItem {
  to: string;
  label: string;
  icon: typeof IconLanes;
  end?: boolean;
}

const PRIMARY: RailItem[] = [
  { to: '/', label: 'Operations', icon: IconLanes, end: true },
  { to: '/projects', label: 'Projects', icon: IconFolder },
  { to: '/jobs', label: 'Jobs', icon: IconStack, end: true },
  { to: '/jobs/new', label: 'New job', icon: IconPlus },
  { to: '/workers', label: 'Workers', icon: IconCpu },
  { to: '/logs', label: 'Audit logs', icon: IconTerminal, end: true },
  { to: '/metrics', label: 'Metrics', icon: IconChart, end: true },
];

/** Slim icon rail (desktop). Tooltips appear to the right on hover/focus. */
export function IconRail() {
  const user = useAuthStore((s) => s.user);
  const logout = useLogout();
  const navigate = useNavigate();

  return (
    <nav
      aria-label="Primary navigation"
      className="fixed left-0 top-0 z-40 flex h-screen w-16 flex-col items-center border-r border-line bg-bg-deep/95 backdrop-blur"
    >
      <Link
        to="/"
        aria-label="TaskMesh home"
        className="mt-3 flex h-10 w-10 items-center justify-center rounded-sm hover:bg-panel-2"
      >
        <LogoMark size={22} />
      </Link>

      <div className="mt-4 flex w-full flex-1 flex-col items-center gap-1 border-t border-line/60 pt-3">
        {PRIMARY.map(({ to, label, icon: Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            aria-label={label}
            className="group relative flex h-10 w-10 items-center justify-center rounded-sm text-mute hover:bg-panel-2 hover:text-ink aria-[current=page]:text-amber"
          >
            {({ isActive }) => (
              <>
                <span
                  className={`absolute left-0 top-1/2 h-6 w-0.5 -translate-y-1/2 rounded-r-sm bg-amber ${
                    isActive ? 'opacity-100' : 'opacity-0'
                  }`}
                  aria-hidden="true"
                />
                <Icon size={19} />
                <span className="pointer-events-none absolute left-[52px] z-50 hidden whitespace-nowrap rounded-sm border border-line bg-panel px-2 py-1 font-mono text-[10px] uppercase tracking-[0.08em] text-ink shadow-[0_4px_16px_rgba(0,0,0,0.45)] group-focus-visible:block group-hover:block">
                  {label}
                </span>
              </>
            )}
          </NavLink>
        ))}
      </div>

      <div className="mb-3 flex flex-col items-center gap-2">
        <NavLink
          to="/settings"
          aria-label="Settings"
          className="group relative flex h-10 w-10 items-center justify-center rounded-sm text-mute hover:bg-panel-2 hover:text-ink aria-[current=page]:text-amber"
        >
          <IconSliders size={19} />
          <span className="pointer-events-none absolute left-[52px] z-50 hidden whitespace-nowrap rounded-sm border border-line bg-panel px-2 py-1 font-mono text-[10px] uppercase tracking-[0.08em] text-ink shadow-[0_4px_16px_rgba(0,0,0,0.45)] group-focus-visible:block group-hover:block">
            Settings
          </span>
        </NavLink>
        <button
          type="button"
          aria-label={`Log out ${user?.username ?? ''}`}
          onClick={() => {
            logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) });
          }}
          className="flex h-10 w-10 items-center justify-center rounded-sm text-mute hover:bg-rose/10 hover:text-rose"
        >
          <IconLogout size={19} />
        </button>
      </div>
    </nav>
  );
}
