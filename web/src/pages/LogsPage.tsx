import { Fragment, useMemo, useState } from 'react';
import { useAudit } from '../api/hooks';
import { RoleGate } from '../components/layout/RequireAuth';
import { Panel, PageHead } from '../components/ui/Panel';
import { Pagination } from '../components/ui/Pagination';
import { EmptyState, ErrorPanel, TableSkeleton } from '../components/ui/States';
import { TextInput, SelectInput } from '../components/ui/Inputs';
import { JsonInspector } from '../components/ui/JsonInspector';
import { fmtDateTime, shortId } from '../lib/format';
import type { AuditLog } from '../types';

export default function LogsPage() {
  return (
    <RoleGate roles={['OPERATOR', 'ADMIN']}>
      <LogsContent />
    </RoleGate>
  );
}

function LogsContent() {
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [result, setResult] = useState<'' | 'SUCCESS' | 'FAILURE'>('');
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});

  const filters = useMemo(
    () => ({
      actor: actor.trim() || undefined,
      action: action.trim() || undefined,
      result: result === '' ? undefined : result,
      page,
      size: 25,
    }),
    [actor, action, result, page],
  );
  const auditQ = useAudit(filters);

  const hasFilters = actor.trim() !== '' || action.trim() !== '' || result !== '';

  return (
    <div className="space-y-3">
      <PageHead
        title="Audit logs"
        sub="who did what · auth.login / job.create / job.cancel / job.retry · OPERATOR+"
      />

      <Panel
        title={`events · ${auditQ.data?.total != null ? auditQ.data.total.toLocaleString('en-US') : '…'}`}
        flush
      >
        <div className="flex flex-wrap items-end gap-2.5 border-b border-line/60 px-3 py-2.5">
          <div className="w-[160px]">
            <TextInput
              label="actor"
              placeholder="username or id"
              value={actor}
              spellCheck={false}
              onChange={(e) => {
                setActor(e.target.value);
                setPage(0);
              }}
            />
          </div>
          <div className="w-[160px]">
            <TextInput
              label="action"
              placeholder="job.create"
              value={action}
              spellCheck={false}
              onChange={(e) => {
                setAction(e.target.value);
                setPage(0);
              }}
            />
          </div>
          <div className="w-[130px]">
            <SelectInput
              label="result"
              value={result}
              onChange={(e) => {
                setResult(e.target.value as '' | 'SUCCESS' | 'FAILURE');
                setPage(0);
              }}
              options={[
                { value: '', label: 'all' },
                { value: 'SUCCESS', label: 'success' },
                { value: 'FAILURE', label: 'failure' },
              ]}
            />
          </div>
          {hasFilters && (
            <button
              type="button"
              onClick={() => {
                setActor('');
                setAction('');
                setResult('');
                setPage(0);
              }}
              className="h-8 rounded-sm border border-transparent px-2 font-mono text-[11px] uppercase tracking-[0.08em] text-mute hover:border-line hover:text-ink"
            >
              reset
            </button>
          )}
        </div>

        {auditQ.isLoading ? (
          <TableSkeleton rows={8} cols={5} />
        ) : auditQ.isError ? (
          <div className="p-3">
            <ErrorPanel err={auditQ.error} onRetry={() => void auditQ.refetch()} />
          </div>
        ) : (auditQ.data?.items.length ?? 0) === 0 ? (
          <EmptyState title="no audit events match" hint={hasFilters ? 'adjust or reset the filters above' : 'actions appear here as users work'} />
        ) : (
          <div className="overflow-x-auto scroll-thin">
            <table className="w-full min-w-[720px] text-left font-mono text-[11px]">
              <thead>
                <tr className="border-b border-line">
                  {['time', 'actor', 'action', 'resource', 'result', 'meta'].map((h) => (
                    <th key={h} scope="col" className="micro px-3 py-2 text-mute">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(auditQ.data?.items ?? []).map((log: AuditLog) => {
                  const open = expanded[log.id] === true;
                  return (
                    <Fragment key={log.id}>
                      <tr className="border-b border-line/50 hover:bg-panel-2/70">
                        <td className="tabular whitespace-nowrap px-3 py-2 text-mute" title={fmtDateTime(log.createdAt)}>
                          {fmtDateTime(log.createdAt)}
                        </td>
                        <td className="max-w-[160px] truncate px-3 py-2 text-ink" title={log.actorId ?? undefined}>
                          {log.actorName ?? (log.actorId != null ? shortId(log.actorId, 8) : 'system')}
                        </td>
                        <td className="whitespace-nowrap px-3 py-2 text-amber">{log.action}</td>
                        <td className="max-w-[200px] truncate px-3 py-2 text-mute">
                          {log.resourceType != null || log.resourceId != null ? (
                            <>
                              {log.resourceType ?? '—'}
                              {log.resourceId != null ? ` · ${shortId(log.resourceId, 8)}` : ''}
                            </>
                          ) : (
                            <span className="text-dim">—</span>
                          )}
                        </td>
                        <td className="whitespace-nowrap px-3 py-2">
                          <span
                            className={`inline-flex items-center gap-1.5 rounded-sm border px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-[0.06em] ${
                              log.result === 'SUCCESS'
                                ? 'border-emerald/45 bg-emerald/5 text-emerald'
                                : 'border-rose/45 bg-rose/5 text-rose'
                            }`}
                          >
                            {log.result}
                          </span>
                        </td>
                        <td className="px-3 py-2">
                          <button
                            type="button"
                            onClick={() => setExpanded((prev) => ({ ...prev, [log.id]: !prev[log.id] }))}
                            aria-expanded={open}
                            className={`rounded-sm border px-1.5 py-0.5 text-[9px] uppercase tracking-[0.06em] ${
                              open ? 'border-amber/45 text-amber' : 'border-line text-mute hover:text-ink'
                            }`}
                          >
                            {'{ }'}
                          </button>
                        </td>
                      </tr>
                      {open && (
                        <tr className="border-b border-line/50 bg-bg-deep/40">
                          <td colSpan={6} className="px-3 py-2">
                            <JsonInspector data={log.metadata ?? null} name="metadata" defaultOpen={1} emptyLabel="no metadata recorded" />
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {auditQ.data != null && (
          <Pagination
            page={auditQ.data.page}
            size={auditQ.data.size}
            total={auditQ.data.total}
            onPage={setPage}
            loading={auditQ.isFetching}
          />
        )}
      </Panel>
    </div>
  );
}
