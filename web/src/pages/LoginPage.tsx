import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useLogin, useHealth, ApiError } from '../api/hooks';
import { useAuthStore } from '../stores/auth';
import { Button } from '../components/ui/Button';
import { TextInput } from '../components/ui/Inputs';
import { LogoMark } from '../components/icons';
import { IconAlert } from '../components/icons';

export default function LoginPage() {
  const token = useAuthStore((s) => s.token);
  const setSession = useAuthStore((s) => s.setSession);
  const authExpired = useAuthStore((s) => s.authExpired);
  const clearAuthExpired = useAuthStore((s) => s.clearAuthExpired);
  const login = useLogin();
  const health = useHealth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  if (token) return <Navigate to={from} replace />;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const res = await login.mutateAsync({ username: username.trim(), password });
      setSession(res.token, res.user, Date.now() + res.expiresIn * 1000);
      navigate(from, { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('Invalid username or password');
      } else if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError('Cannot reach the TaskMesh API — is the backend running?');
      }
    }
  };

  const healthColor =
    health.isError ? 'text-rose' : health.isLoading ? 'text-dim' : 'text-emerald';

  return (
    <div className="flex min-h-dvh items-center justify-center bg-bg px-4">
      <main className="w-full max-w-sm" aria-label="Sign in">
        <div className="mb-6 flex flex-col items-center gap-3">
          <LogoMark size={44} />
          <div className="text-center">
            <h1 className="font-mono text-lg font-bold tracking-[0.2em] text-ink">TASKMESH</h1>
            <p className="micro mt-1 text-dim">operations console</p>
          </div>
        </div>

        <section className="rounded-md border border-line bg-panel">
          <header className="flex items-center gap-2 border-b border-line/70 px-4 py-2.5">
            <span className="h-1.5 w-1.5 bg-amber" aria-hidden="true" />
            <h2 className="micro text-mute">sign in</h2>
            <span className={`ml-auto micro ${healthColor}`} title="GET /api/v1/health">
              {health.isError
                ? 'api unreachable'
                : health.isLoading
                  ? 'api …'
                  : `api up · v${health.data?.version ?? '?'}`}
            </span>
          </header>

          <form onSubmit={submit} className="space-y-3.5 p-4" noValidate>
            {authExpired && (
              <p
                className="rounded-sm border border-amber/40 bg-amber/5 px-2.5 py-1.5 font-mono text-[11px] text-amber"
                role="status"
              >
                session expired — sign in again
              </p>
            )}
            {error != null && (
              <p
                className="flex items-center gap-2 rounded-sm border border-rose/40 bg-rose/5 px-2.5 py-1.5 font-mono text-[11px] text-rose"
                role="alert"
              >
                <IconAlert size={13} className="shrink-0" />
                {error}
              </p>
            )}
            <TextInput
              label="username"
              autoComplete="username"
              spellCheck={false}
              required
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
                if (authExpired) clearAuthExpired();
              }}
            />
            <TextInput
              label="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (authExpired) clearAuthExpired();
              }}
            />
            <Button
              type="submit"
              variant="primary"
              className="w-full"
              loading={login.isPending}
              disabled={username.trim().length === 0 || password.length === 0}
            >
              sign in
            </Button>
          </form>
        </section>

        <p className="mt-4 text-center font-mono text-[10px] leading-4 text-dim">
          local dev accounts: admin · operator · user
          <br />
          passwords are set via backend .env (see README)
        </p>
      </main>
    </div>
  );
}
