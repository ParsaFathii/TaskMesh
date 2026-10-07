import { NavLink } from 'react-router-dom';
import { IconChart, IconCpu, IconFolder, IconLanes, IconPlus, IconSliders, IconStack } from '../icons';

const ITEMS = [
  { to: '/', label: 'Ops', icon: IconLanes, end: true },
  { to: '/projects', label: 'Projects', icon: IconFolder },
  { to: '/jobs', label: 'Jobs', icon: IconStack, end: true },
  { to: '/jobs/new', label: 'New', icon: IconPlus },
  { to: '/workers', label: 'Workers', icon: IconCpu },
  { to: '/metrics', label: 'Metrics', icon: IconChart, end: true },
  { to: '/settings', label: 'Settings', icon: IconSliders, end: true },
];

/** Mobile: the icon rail becomes a scrollable bottom tab bar. */
export function MobileTabBar() {
  return (
    <nav
      aria-label="Primary navigation"
      className="fixed bottom-9 left-0 right-0 z-40 flex h-14 items-stretch overflow-x-auto border-t border-line bg-bg-deep/95 backdrop-blur md:hidden scroll-thin"
    >
      {ITEMS.map(({ to, label, icon: Icon, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          aria-label={label}
          className="flex min-w-[76px] flex-1 flex-col items-center justify-center gap-1 border-r border-line/40 px-2 text-mute aria-[current=page]:text-amber"
        >
          <Icon size={17} />
          <span className="micro">{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
