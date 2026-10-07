import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import type { SessionUser } from '../types';

export interface AuthState {
  token: string | null;
  user: SessionUser | null;
  /** epoch ms when the JWT expires */
  expiresAt: number | null;
  /** set when a REST call returned 401 and the session was dropped */
  authExpired: boolean;
  setSession: (token: string, user: SessionUser, expiresAt: number) => void;
  logout: () => void;
  markExpired: () => void;
  clearAuthExpired: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      user: null,
      expiresAt: null,
      authExpired: false,
      setSession: (token, user, expiresAt) => set({ token, user, expiresAt, authExpired: false }),
      logout: () => set({ token: null, user: null, expiresAt: null, authExpired: false }),
      markExpired: () => set({ token: null, user: null, expiresAt: null, authExpired: true }),
      clearAuthExpired: () => set({ authExpired: false }),
    }),
    {
      name: 'taskmesh.session',
      storage: createJSONStorage(() => localStorage),
      partialize: (s) => ({ token: s.token, user: s.user, expiresAt: s.expiresAt }),
    },
  ),
);
