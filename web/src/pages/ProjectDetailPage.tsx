import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useProject, useJobs, useUpdateProject, useDeleteProject, errMessage } from '../api/hooks';
import { useAuthStore } from '../stores/auth';
import { Panel, PageHead } from '../components/ui/Panel';
import { JobsTable } from '../components/JobsTable';
import { EmptyState, ErrorPanel, LinesSkeleton, TableSkeleton } from '../components/ui/States';
import { Button } from '../components/ui/Button';
import { Modal, ConfirmDialog } from '../components/ui/Modal';
import { TextInput, TextArea } from '../components/ui/Inputs';
import { CopyButton } from '../components/ui/CopyButton';
import { useNow } from '../hooks/useNow';
import { fmtDateTime, timeAgo, parseIso } from '../lib/format';
import { IconFolder, IconStack } from '../components/icons';

export default function ProjectDetailPage() {
  const { id } = useParams<{ id: string }>();
  const projectQ = useProject(id);
  const jobsQ = useJobs({ projectId: id, size: 50 });
  const updateMut = useUpdateProject();
  const deleteMut = useDeleteProject();
  const navigate = useNavigate();
  const now = useNow(5000);
  const role = useAuthStore((s) => s.user?.role);
  const myId = useAuthStore((s) => s.user?.id);
  const canEdit = projectQ.data != null && (projectQ.data.ownerId === myId || role === 'ADMIN');

  const [editOpen, setEditOpen] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const openEdit = () => {
    setName(projectQ.data?.name ?? '');
    setDescription(projectQ.data?.description ?? '');
    setFormError(null);
    setEditOpen(true);
  };

  const submitEdit = async (e: FormEvent) => {
    e.preventDefault();
    if (id == null) return;
    setFormError(null);
    if (name.trim().length === 0) {
      setFormError('name is required');
      return;
    }
    try {
      await updateMut.mutateAsync({ id, name: name.trim(), description: description.trim() });
      setEditOpen(false);
    } catch (err) {
      setFormError(errMessage(err, 'could not update project'));
    }
  };

  const submitDelete = async () => {
    if (id == null) return;
    setDeleteError(null);
    try {
      await deleteMut.mutateAsync(id);
      navigate('/projects', { replace: true });
    } catch (err) {
      setDeleteError(errMessage(err, 'could not delete project'));
    }
  };

  if (projectQ.isError) {
    return (
      <div className="max-w-2xl space-y-3">
        <PageHead title="Project" sub={id} />
        <ErrorPanel err={projectQ.error} onRetry={() => void projectQ.refetch()} />
      </div>
    );
  }

  const project = projectQ.data;

  return (
    <div className="space-y-3">
      <PageHead
        title="Project"
        sub={project != null ? project.name : '…'}
        actions={
          <>
            {canEdit && (
              <Button variant="secondary" onClick={openEdit}>
                edit
              </Button>
            )}
            {canEdit && (
              <Button variant="danger" onClick={() => setConfirmDelete(true)}>
                delete
              </Button>
            )}
            <Link to={`/jobs?projectId=${id ?? ''}`}>
              <Button variant="secondary" icon={<IconStack size={13} />}>
                jobs in project
              </Button>
            </Link>
          </>
        }
      />

      <div className="grid gap-3 lg:grid-cols-[300px_1fr]">
        <Panel title="identity">
          {projectQ.isLoading || project == null ? (
            <LinesSkeleton lines={5} />
          ) : (
            <dl className="space-y-2.5 font-mono text-[11.5px]">
              <div>
                <dt className="micro text-dim">name</dt>
                <dd className="mt-0.5 font-semibold text-ink">{project.name}</dd>
              </div>
              <div>
                <dt className="micro text-dim">description</dt>
                <dd className="mt-0.5 leading-5 text-mute">{project.description || '—'}</dd>
              </div>
              <div>
                <dt className="micro text-dim">project id</dt>
                <dd className="mt-0.5 flex items-center gap-1 break-all text-mute">
                  {project.id}
                  <CopyButton value={project.id} label="project id" />
                </dd>
              </div>
              <div>
                <dt className="micro text-dim">owner</dt>
                <dd className="mt-0.5 text-mute">
                  {project.ownerName ?? 'user'} · <span className="text-dim">{project.ownerId.slice(0, 13)}…</span>
                </dd>
              </div>
              <div>
                <dt className="micro text-dim">created</dt>
                <dd className="mt-0.5 text-mute" title={fmtDateTime(project.createdAt)}>
                  {fmtDateTime(project.createdAt)}
                </dd>
              </div>
              <div>
                <dt className="micro text-dim">updated</dt>
                <dd className="mt-0.5 text-dim" title={fmtDateTime(project.updatedAt)}>
                  {timeAgo(parseIso(project.updatedAt), now)} ago
                </dd>
              </div>
            </dl>
          )}
        </Panel>

        <Panel
          title={`jobs in project · ${jobsQ.data?.total != null ? jobsQ.data.total.toLocaleString('en-US') : '…'}`}
          flush
        >
          {jobsQ.isLoading ? (
            <TableSkeleton rows={6} />
          ) : jobsQ.isError ? (
            <div className="p-3">
              <ErrorPanel err={jobsQ.error} onRetry={() => void jobsQ.refetch()} />
            </div>
          ) : (jobsQ.data?.items.length ?? 0) === 0 ? (
            <EmptyState
              icon={<IconFolder size={22} />}
              title="no jobs in this project"
              hint="submit a job with this project selected"
              action={
                <Link to="/jobs/new">
                  <Button size="sm" variant="primary">
                    new job
                  </Button>
                </Link>
              }
            />
          ) : (
            <JobsTable jobs={jobsQ.data?.items ?? []} now={now} />
          )}
          {jobsQ.data != null && jobsQ.data.total > 50 && (
            <div className="border-t border-line/60 px-3 py-2 text-right">
              <Link to={`/jobs?projectId=${id ?? ''}`} className="font-mono text-[11px] text-amber hover:underline">
                view all {jobsQ.data.total.toLocaleString('en-US')} jobs →
              </Link>
            </div>
          )}
        </Panel>
      </div>

      <Modal open={editOpen} onClose={() => setEditOpen(false)} title="edit project">
        <form onSubmit={submitEdit} className="space-y-3.5" noValidate>
          {formError != null && (
            <p className="rounded-sm border border-rose/40 bg-rose/5 px-2.5 py-1.5 font-mono text-[11px] text-rose" role="alert">
              {formError}
            </p>
          )}
          <TextInput label="name" required value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
          <TextArea label="description" rows={3} value={description} maxLength={2000} onChange={(e) => setDescription(e.target.value)} />
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setEditOpen(false)}>
              dismiss
            </Button>
            <Button type="submit" variant="primary" loading={updateMut.isPending} disabled={name.trim().length === 0}>
              save
            </Button>
          </div>
        </form>
      </Modal>

      <ConfirmDialog
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        onConfirm={submitDelete}
        tone="danger"
        title="delete project"
        confirmLabel="delete"
        busy={deleteMut.isPending}
        error={deleteError}
        message={
          <>
            Permanently delete <span className="text-ink">{project?.name}</span> and its{' '}
            {jobsQ.data?.total != null ? jobsQ.data.total.toLocaleString('en-US') : '…'} jobs? This cannot be undone
            (ON DELETE CASCADE).
          </>
        }
      />
    </div>
  );
}
