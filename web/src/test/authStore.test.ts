import { beforeEach, describe, expect, it } from 'vitest';
import { useAuthStore } from '../stores/auth';

const SESSION = {
  token: 'jwt-abc123',
  user: { id: 'u-1', username: 'operator', role: 'OPERATOR' as const },
  expiresAt: 1_900_000_000_000,
};

describe('auth store (persisted as taskmesh.session)', () => {
  beforeEach(() => {
    useAuthStore.setState({ token: null, user: null, expiresAt: null, authExpired: false });
    localStorage.clear();
  });

  it('starts unauthenticated', () => {
    expect(useAuthStore.getState().token).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('setSession stores the JWT, user and expiry, and persists to localStorage', () => {
    useAuthStore.getState().setSession(SESSION.token, SESSION.user, SESSION.expiresAt);

    expect(useAuthStore.getState().token).toBe('jwt-abc123');
    expect(useAuthStore.getState().user?.username).toBe('operator');
    expect(useAuthStore.getState().authExpired).toBe(false);

    const raw = localStorage.getItem('taskmesh.session');
    expect(raw).toBeTruthy();
    const parsed = JSON.parse(raw!) as { state: { token: string; user: { role: string }; authExpired?: boolean } };
    expect(parsed.state.token).toBe('jwt-abc123');
    expect(parsed.state.user.role).toBe('OPERATOR');
    // volatile flags are not persisted
    expect(parsed.state.authExpired).toBeUndefined();
  });

  it('logout clears the session and the persisted copy', () => {
    useAuthStore.getState().setSession(SESSION.token, SESSION.user, SESSION.expiresAt);
    useAuthStore.getState().logout();

    expect(useAuthStore.getState().token).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().expiresAt).toBeNull();
    const raw = localStorage.getItem('taskmesh.session');
    expect(JSON.parse(raw!).state.token).toBeNull();
  });

  it('markExpired drops the session and raises the authExpired flag (401 handler)', () => {
    useAuthStore.getState().setSession(SESSION.token, SESSION.user, SESSION.expiresAt);
    useAuthStore.getState().markExpired();

    expect(useAuthStore.getState().token).toBeNull();
    expect(useAuthStore.getState().authExpired).toBe(true);
  });

  it('rehydrates from localStorage via the persist API', () => {
    localStorage.setItem(
      'taskmesh.session',
      JSON.stringify({ state: { token: 'jwt-rehydrated', user: SESSION.user, expiresAt: 1 }, version: 0 }),
    );
    void useAuthStore.persist.rehydrate();

    expect(useAuthStore.getState().token).toBe('jwt-rehydrated');
    expect(useAuthStore.getState().user?.username).toBe('operator');
  });

  it('login flow: setSession then logout yields a clean slate', () => {
    const s = useAuthStore.getState();
    s.setSession(SESSION.token, SESSION.user, SESSION.expiresAt);
    expect(useAuthStore.getState().token).toBe(SESSION.token);
    useAuthStore.getState().logout();
    expect(useAuthStore.getState().token).toBeNull();
    // session can be re-established afterwards
    useAuthStore.getState().setSession('jwt-2', SESSION.user, 2);
    expect(useAuthStore.getState().token).toBe('jwt-2');
    expect(useAuthStore.getState().authExpired).toBe(false);
  });
});
