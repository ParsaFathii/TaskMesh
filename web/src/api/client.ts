import { useAuthStore } from '../stores/auth';

export const API_BASE: string =
  (import.meta.env.VITE_API_BASE as string | undefined)?.replace(/\/+$/, '') || '/api/v1';

export function apiUrl(path: string): string {
  return `${API_BASE}${path}`;
}

/**
 * Build a query string from a filter object. Arrays are repeated
 * (?status=QUEUED&status=RETRYING) per the SPEC pagination convention;
 * undefined / empty values are dropped.
 */
export function qs(
  params: Record<string, string | number | boolean | undefined | string[]>,
): string {
  const sp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === false) continue;
    if (Array.isArray(value)) {
      for (const v of value) if (v !== '' && v != null) sp.append(key, v);
    } else {
      sp.append(key, String(value));
    }
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

const STATUS_CODES: Record<number, string> = {
  400: 'VALIDATION',
  401: 'UNAUTHORIZED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'METHOD_NOT_ALLOWED',
  409: 'CONFLICT',
  422: 'VALIDATION',
  429: 'RATE_LIMITED',
  500: 'INTERNAL',
  502: 'BAD_GATEWAY',
  503: 'UNAVAILABLE',
};

/** Normalize an error Response to the SPEC error shape. */
export async function toApiError(res: Response): Promise<ApiError> {
  let code = STATUS_CODES[res.status] ?? `HTTP_${res.status}`;
  let message = `${res.status} ${res.statusText || 'request failed'}`.trim();
  let details: unknown;
  try {
    const body: unknown = await res.json();
    if (body && typeof body === 'object') {
      const err = (body as { error?: unknown }).error;
      if (err && typeof err === 'object') {
        const e = err as { code?: unknown; message?: unknown; details?: unknown };
        if (typeof e.code === 'string' && e.code) code = e.code;
        if (typeof e.message === 'string' && e.message) message = e.message;
        details = e.details;
      } else if (typeof (body as { message?: unknown }).message === 'string') {
        message = (body as { message: string }).message;
      }
    }
  } catch {
    /* non-JSON body — keep status-derived defaults */
  }
  return new ApiError(res.status, code, message, details);
}

/**
 * Fetch wrapper for the TaskMesh REST API (SPEC §8).
 * - baseURL /api/v1 (override with VITE_API_BASE)
 * - injects Authorization: Bearer <JWT> when a session exists
 * - normalizes errors to the SPEC shape {"error":{"code","message"}}
 * - 401 on non-auth endpoints drops the session (RequireAuth redirects)
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = useAuthStore.getState().token;
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init.body != null && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

  let res: Response;
  try {
    res = await fetch(apiUrl(path), { ...init, headers });
  } catch (err) {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the TaskMesh API — is the backend running?', err);
  }

  if (res.status === 401 && !path.startsWith('/auth/')) {
    useAuthStore.getState().markExpired();
  }

  if (!res.ok) throw await toApiError(res);

  if (res.status === 204) return undefined as T;
  const ct = res.headers.get('content-type') ?? '';
  if (ct.includes('application/json')) return (await res.json()) as T;
  return (await res.text()) as unknown as T;
}

/** Raw fetch with token injection (for binary responses such as job result files). */
export async function apiRaw(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = useAuthStore.getState().token;
  if (token) headers.set('Authorization', `Bearer ${token}`);
  let res: Response;
  try {
    res = await fetch(apiUrl(path), { ...init, headers });
  } catch (err) {
    throw new ApiError(0, 'NETWORK', 'Cannot reach the TaskMesh API — is the backend running?', err);
  }
  if (res.status === 401 && !path.startsWith('/auth/')) {
    useAuthStore.getState().markExpired();
  }
  return res;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: unknown;

  constructor(status: number, code: string, message: string, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }

  /** True when the API answered with an authorization failure. */
  get isAuthError(): boolean {
    return this.status === 401 || this.status === 403;
  }
}

export function errMessage(err: unknown, fallback = 'Something went wrong'): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof Error) return err.message;
  return fallback;
}
