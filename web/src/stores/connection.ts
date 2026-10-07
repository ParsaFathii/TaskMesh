import { create } from 'zustand';

export type ConnStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface ConnectionState {
  status: ConnStatus;
  /** failed connect attempts since last success (0 while open) */
  attempt: number;
  /** epoch ms of last received frame */
  lastEventAt: number | null;
  /** server identification from the hello frame */
  server: string | null;
  set: (status: ConnStatus, attempt: number) => void;
  noteEvent: () => void;
  setServer: (server: string) => void;
}

export const initialConnectionState: ConnectionState = {
  status: 'idle',
  attempt: 0,
  lastEventAt: null,
  server: null,
  set: () => undefined,
  noteEvent: () => undefined,
  setServer: () => undefined,
};

export const useConnectionStore = create<ConnectionState>((set) => ({
  status: 'idle',
  attempt: 0,
  lastEventAt: null,
  server: null,
  set: (status, attempt) => set({ status, attempt }),
  noteEvent: () => set({ lastEventAt: Date.now() }),
  setServer: (server) => set({ server }),
}));
