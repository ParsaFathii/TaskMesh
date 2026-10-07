import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';
import { useAuthStore } from '../stores/auth';
import { initialConnectionState, useConnectionStore } from '../stores/connection';
import { initialEventsState, useEventsStore } from '../stores/events';
import { useUiStore } from '../stores/ui';

afterEach(() => {
  cleanup();
  // reset module state between tests
  useAuthStore.setState({ token: null, user: null, expiresAt: null, authExpired: false });
  useConnectionStore.setState({ ...initialConnectionState, set: useConnectionStore.getState().set, noteEvent: useConnectionStore.getState().noteEvent, setServer: useConnectionStore.getState().setServer }, true);
  useEventsStore.setState(
    {
      ...initialEventsState,
      pushTicker: useEventsStore.getState().pushTicker,
      flashJob: useEventsStore.getState().flashJob,
      appendJobLog: useEventsStore.getState().appendJobLog,
      clearJobLogs: useEventsStore.getState().clearJobLogs,
    },
    true,
  );
  useUiStore.setState({ chipPath: null, chips: [] });
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
