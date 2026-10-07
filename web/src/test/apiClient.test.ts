import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, qs } from '../api/client';
import { ApiError } from '../api/client';
import { useAuthStore } from '../stores/auth';
import { OPERATOR_SESSION, jsonResponse, mockFetch } from './fixtures';

describe('apiClient', () => {
  beforeEach(() => {
    useAuthStore.setState({ ...OPERATOR_SESSION, authExpired: false });
  });

  it('injects the bearer token from the session store', async () => {
    const { fn } = mockFetch({ 'GET /me': { id: 'u1', username: 'op', role: 'OPERATOR' } });

    await apiFetch('/me');

    expect(fn).toHaveBeenCalledTimes(1);
    const [input, init] = fn.mock.calls[0]!;
    expect(String(input)).toBe('/api/v1/me');
    const headers = new Headers(init?.headers);
    expect(headers.get('Authorization')).toBe('Bearer test-token-op');
  });

  it('adds JSON content-type only when a body is sent', async () => {
    const { fn } = mockFetch({ 'POST /jobs': { id: 'j1' } });
    await apiFetch('/jobs', { method: 'POST', body: JSON.stringify({ type: 'x' }) });
    const headers = new Headers(fn.mock.calls[0]![1]?.headers);
    expect(headers.get('Content-Type')).toBe('application/json');
  });

  it('parses successful JSON responses', async () => {
    mockFetch({ 'GET /health': { status: 'UP', db: 'UP', version: '0.1.0' } });
    const health = await apiFetch<{ status: string }>('/health');
    expect(health.status).toBe('UP');
  });

  it('normalizes SPEC error bodies to ApiError {code, message}', async () => {
    mockFetch({ 'GET /jobs/missing': [404, { error: { code: 'NOT_FOUND', message: 'job does not exist' } }] });
    const err = await apiFetch('/jobs/missing').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(404);
    expect((err as ApiError).code).toBe('NOT_FOUND');
    expect((err as ApiError).message).toBe('job does not exist');
  });

  it('derives a code from the status when the body is not JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('gateway exploded', { status: 502, headers: { 'content-type': 'text/plain' } })),
    );
    const err = await apiFetch('/jobs').catch((e: unknown) => e);
    expect((err as ApiError).status).toBe(502);
    expect((err as ApiError).code).toBe('BAD_GATEWAY');
  });

  it('throws a NETWORK ApiError when fetch rejects', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('failed to fetch'))));
    const err = await apiFetch('/health').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(0);
    expect((err as ApiError).code).toBe('NETWORK');
  });

  it('drops the session and flags authExpired on 401 from protected endpoints', async () => {
    mockFetch({ 'GET /jobs': [401, { error: { code: 'UNAUTHORIZED', message: 'token expired' } }] });
    await expect(apiFetch('/jobs')).rejects.toBeInstanceOf(ApiError);
    expect(useAuthStore.getState().token).toBeNull();
    expect(useAuthStore.getState().authExpired).toBe(true);
  });

  it('keeps the session on 401 from the login endpoint (bad credentials)', async () => {
    mockFetch({ 'POST /auth/login': [401, { error: { code: 'UNAUTHORIZED', message: 'bad credentials' } }] });
    await expect(apiFetch('/auth/login', { method: 'POST', body: '{}' })).rejects.toBeInstanceOf(ApiError);
    expect(useAuthStore.getState().token).toBe('test-token-op');
    expect(useAuthStore.getState().authExpired).toBe(false);
  });

  it('passes 403 through as a normal error (role enforcement)', async () => {
    mockFetch({ 'GET /workers': [403, { error: { code: 'FORBIDDEN', message: 'requires OPERATOR' } }] });
    const err = await apiFetch('/workers').catch((e: unknown) => e);
    expect((err as ApiError).isAuthError).toBe(true);
    expect(useAuthStore.getState().token).toBe('test-token-op');
  });
});

describe('qs', () => {
  it('repeats array params and drops empty values', () => {
    const out = qs({
      status: ['QUEUED', 'RETRYING'],
      page: 2,
      size: 25,
      type: undefined,
      priority: '',
    });
    expect(out).toBe('?status=QUEUED&status=RETRYING&page=2&size=25');
  });

  it('returns an empty string when nothing remains', () => {
    expect(qs({ a: undefined, b: '' })).toBe('');
  });

  it('encodes values (URLSearchParams form encoding)', () => {
    expect(qs({ actor: 'ada lovelace' })).toBe('?actor=ada+lovelace');
  });
});

describe('login response shape', () => {
  it('accepts the SPEC login contract', async () => {
    mockFetch({
      'POST /auth/login': jsonResponse(200, {
        token: 'jwt',
        tokenType: 'Bearer',
        expiresIn: 43200,
        user: { id: 'u1', username: 'admin', role: 'ADMIN' },
      }),
    });
    const res = await apiFetch<{ token: string; user: { role: string } }>('/auth/login', { method: 'POST' });
    expect(res.token).toBe('jwt');
    expect(res.user.role).toBe('ADMIN');
  });
});
