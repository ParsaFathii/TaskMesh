import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMe, useLogout, useHealth, API_BASE } from '../api/hooks';
import { useAuthStore } from '../stores/auth';
import { useConnectionStore } from '../stores/connection';
import { Panel, PageHead } from '../components/ui/Panel';
import { Button } from '../components/ui/Button';
import { CopyButton } from '../components/ui/CopyButton';
import { ErrorPanel, LinesSkeleton } from '../components/ui/States';
import { IconEye, IconLogout } from '../components/icons';
import { fmtDateTime, timeUntil } from '../lib/format';
import { useNow } from '../hooks/useNow';
import { ROLE_GATES } from '../lib/roles';

export default function SettingsPage() {
  const meQ = useMe();
  const logout = useLogout();
  const navigate = useNavigate();
  const token = useAuthStore((s) => s.token);
  const expiresAt = useAuthStore((s) => s.expiresAt);
  const sessionUser = useAuthStore((s) => s.user);
  const health = useHealth();
  const conn = useConnectionStore();
  const [reveal, setReveal] = useState(false);
  const now = useNow(1000);

  const me = meQ.data ?? null;
  const role = me?.role ?? sessionUser?.role;
  const visible = token ?? '';

  return (
    <div className="space-y-3">
      <PageHead title="Settings" sub="account · session · system" />

      <div className="grid gap-3 lg:grid-cols-2">
        <Panel title="account">
          {meQ.isLoading ? (
            <LinesSkeleton lines={5} />
          ) : meQ.isError ? (
            <ErrorPanel err={meQ.error} onRetry={() => void meQ.refetch()} />
          ) : me == null ? (
            <div className="space-y-2 font-mono text-[11px] text-mute">
              <p>
                signed in as <span className="text-ink">{sessionUser?.username ?? '—'}</span>
              </p>
              <p className="text-dim">profile (GET /api/v1/me) unavailable</p>
            </div>
          ) : (
            <dl className="space-y-2.5 font-mono text-[11.5px]">
              <Row label="username" value={me.username} />
              <Row label="email" value={me.email} />
              <Row label="role" value={me.role} />
              <Row label="user id" value={me.id} copy />
              <Row label="created" value={me.createdAt != null ? fmtDateTime(me.createdAt) : '—'} />
            </dl>
          )}
          {role != null && (
            <p className="mt-3 border-t border-line/60 pt-2.5 font-mono text-[10px] leading-4 text-dim">
              role grants: {ROLE_GATES[role]}
            </p>
          )}
        </Panel>

        <Panel title="session">
          <div className="space-y-3">
            <div>
              <p className="micro mb-1.5 text-dim">bearer token · HS256 JWT, 12h</p>
              <div className="flex items-center gap-2">
                <code className="min-w-0 flex-1 truncate rounded-sm border border-line bg-bg-deep px-2 py-1.5 font-mono text-[10.5px] text-mute">
                  {reveal ? visible : '•'.repeat(Math.min(48, Math.max(24, visible.length)))}
                </code>
                <Button size="sm" variant="secondary" onClick={() => setReveal((r) => !r)} aria-pressed={reveal} icon={<IconEye size={12} />}>
                  {reveal ? 'hide' : 'reveal'}
                </Button>
                <CopyButton value={visible} label="token" size={16} />
              </div>
            </div>
            <div className="flex items-center justify-between gap-2 border-t border-line/60 pt-2.5 font-mono text-[11px]">
              <span className="text-dim">expires in</span>
              <span className="tabular text-mute">
                {expiresAt != null ? timeUntil(expiresAt, now) : '—'}
              </span>
            </div>
            <Button
              variant="danger"
              className="w-full"
              icon={<IconLogout size={13} />}
              loading={logout.isPending}
              onClick={() => logout.mutate(undefined, { onSuccess: () => navigate('/login', { replace: true }) })}
            >
              log out
            </Button>
            <p className="font-mono text-[10px] leading-4 text-dim">
              stored in localStorage under <span className="text-mute">taskmesh.session</span> — clearing it signs you
              out everywhere in this browser
            </p>
          </div>
        </Panel>

        <Panel title="system">
          <dl className="space-y-2.5 font-mono text-[11.5px]">
            <Row label="console version" value="0.1.0" />
            <Row label="api base" value={API_BASE} />
            <Row label="api status" value={health.isLoading ? '…' : health.isError ? 'unreachable' : `UP · v${health.data?.version ?? '?'} · db ${health.data?.db ?? '?'}`} />
            <Row label="event stream" value={`${conn.status}${conn.server ? ` · ${conn.server}` : ''}`} />
          </dl>
        </Panel>

        <Panel title="appearance">
          <div className="space-y-2 font-mono text-[11px] leading-5 text-mute">
            <p>
              theme: <span className="text-ink">graphite dark</span> — single theme by design
            </p>
            <p className="text-dim">
              TaskMesh is an operations console; the dark console identity (graphite panels, amber accent, monospace
              data) is the product's visual language and is intentionally not switchable to a light variant.
            </p>
          </div>
        </Panel>
      </div>
    </div>
  );
}

function Row({ label, value, copy = false }: { label: string; value: string; copy?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-2">
      <dt className="micro shrink-0 text-dim">{label}</dt>
      <dd className="min-w-0 flex items-center gap-1 truncate text-right text-mute" title={value}>
        {value}
        {copy && value !== '—' && <CopyButton value={value} label={label} />}
      </dd>
    </div>
  );
}
