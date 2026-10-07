import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useAuthStore } from '../stores/auth';
import { useConnectionStore } from '../stores/connection';
import { EventSocket, wsEventsUrl } from './EventSocket';
import { dispatchFrame } from './dispatch';

/**
 * Connect the app to /ws/v1/events while a session exists.
 * Mount exactly once (inside the authenticated AppShell).
 * Reconnect/backoff/stale logic lives in EventSocket.
 */
export function useEvents(): void {
  const qc = useQueryClient();
  const token = useAuthStore((s) => s.token);

  useEffect(() => {
    if (!token) return;
    const socket = new EventSocket(wsEventsUrl(token, window.location), {
      onFrame: (frame) => dispatchFrame(frame, qc),
      onStatus: (status, attempt) => useConnectionStore.getState().set(status, attempt),
    });
    socket.connect();
    return () => socket.close();
  }, [token, qc]);
}
