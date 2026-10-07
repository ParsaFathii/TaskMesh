import { useEffect } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useEvents } from '../../ws/useEvents';
import { IconRail } from './IconRail';
import { MobileTabBar } from './MobileTabBar';
import { TopBar } from './TopBar';
import { ReconnectBanner } from './ReconnectBanner';
import { EventTicker } from '../EventTicker';

/**
 * Authenticated application shell:
 * icon rail (left, desktop) · top context bar · content outlet · event ticker (bottom).
 */
export function AppShell() {
  useEvents();
  const { pathname } = useLocation();

  useEffect(() => {
    window.scrollTo({ top: 0 });
  }, [pathname]);

  return (
    <div className="min-h-dvh">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-2 focus:top-2 focus:z-50 focus:rounded-sm focus:border focus:border-amber focus:bg-panel focus:px-3 focus:py-1.5 focus:font-mono focus:text-[11px] focus:text-ink"
      >
        skip to content
      </a>
      <IconRail />
      <MobileTabBar />
      <TopBar />
      <ReconnectBanner />
      <main
        id="main-content"
        className="mx-auto w-full max-w-[1560px] px-3 pb-28 pt-16 sm:px-4 md:pl-[76px] md:pr-5 md:pb-16"
      >
        <Outlet />
      </main>
      <EventTicker />
    </div>
  );
}
