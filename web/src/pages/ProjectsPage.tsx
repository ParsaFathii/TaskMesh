import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useProjects, useCreateProject, ApiError, errMessage } from '../api/hooks';
import { Panel, PageHead } from '../components/ui/Panel';
import { Pagination } from '../components/ui/Pagination';
import { EmptyState, ErrorPanel, TableSkeleton } from '../components/ui/States';
import { Button } from '../components/ui/Button';
import { Modal } from '../components/ui/Modal';
import { TextInput, TextArea } from '../components/ui/Inputs';
import { CopyButton } from '../components/ui/CopyButton';
import { IconFolder, IconPlus } from '../components/icons';
import { fmtDateTime, shortId, timeAgo, parseIso } from '../lib/format';
import { useNow } from '../hooks/useNow';

export default function ProjectsPage() {
  const [page, setPage] = useState(0);
  const projectsQ = useProjects(page, 25);
  const createMut = useCreateProject();
  const navigate = useNavigate();
  const now = useNow(30_000);

  const [dialogOpen, setDialogOpen] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  const submitCreate = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    if (name.trim().length === 0) {
      setFormError('name is required');
      return;
    }
    try {
      const project = await createMut.mutateAsync({ name: name.trim(), description: description.trim() });
      setDialogOpen(false);
      setName('');
      setDescription('');
      if (project?.id) navigate(`/projects/${project.id}`);
    } catch (err) {
      setFormError(errMessage(err, 'could not create project'));
    }
  };

  return (
    <div className="space-y-3">
      <PageHead
        title="Projects"
        sub="job containers · owners see their own, operators see all"
        actions={
          <Button variant="primary" icon={<IconPlus size={13} />} onClick={() => setDialogOpen(true)}>
            new project
          </Button>
        }
      />

      <Panel
        title={`projects · ${projectsQ.data?.total != null ? projectsQ.data.total.toLocaleString('en-US') : '…'}`}
        flush
      >
        {projectsQ.isLoading ? (
          <TableSkeleton rows={6} cols={4} />
        ) : projectsQ.isError ? (
          <div className="p-3">
            <ErrorPanel err={projectsQ.error} onRetry={() => void projectsQ.refetch()} />
          </div>
        ) : (projectsQ.data?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<IconFolder size={22} />}
            title="no projects yet"
            hint="projects group jobs and scope idempotency keys"
            action={
              <Button size="sm" variant="primary" onClick={() => setDialogOpen(true)}>
                create project
              </Button>
            }
          />
        ) : (
          <div className="overflow-x-auto scroll-thin">
            <table className="w-full min-w-[560px] text-left font-mono text-[11.5px]">
              <thead>
                <tr className="border-b border-line">
                  {['name', 'description', 'owner', 'created'].map((h) => (
                    <th key={h} scope="col" className="micro px-3 py-2 text-mute">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(projectsQ.data?.items ?? []).map((project) => (
                  <tr key={project.id} className="border-b border-line/50 hover:bg-panel-2/70">
                    <td className="px-3 py-2 whitespace-nowrap">
                      <Link to={`/projects/${project.id}`} className="font-semibold text-amber hover:underline">
                        {project.name}
                      </Link>
                    </td>
                    <td className="max-w-[380px] truncate px-3 py-2 text-mute" title={project.description}>
                      {project.description || <span className="text-dim">—</span>}
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap text-mute" title={project.ownerId}>
                      {project.ownerName ?? shortId(project.ownerId, 8)}
                      <span className="ml-1 inline-flex translate-y-px align-middle">
                        <CopyButton value={project.ownerId} label="owner id" />
                      </span>
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap text-dim" title={fmtDateTime(project.createdAt)}>
                      {timeAgo(parseIso(project.createdAt), now)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {projectsQ.data != null && projectsQ.data.total > projectsQ.data.size && (
          <Pagination
            page={projectsQ.data.page}
            size={projectsQ.data.size}
            total={projectsQ.data.total}
            onPage={setPage}
            loading={projectsQ.isFetching}
          />
        )}
      </Panel>

      <Modal open={dialogOpen} onClose={() => setDialogOpen(false)} title="create project">
        <form onSubmit={submitCreate} className="space-y-3.5" noValidate>
          {formError != null && (
            <p className="rounded-sm border border-rose/40 bg-rose/5 px-2.5 py-1.5 font-mono text-[11px] text-rose" role="alert">
              {formError}
              {createMut.error instanceof ApiError && ` · ${createMut.error.code}`}
            </p>
          )}
          <TextInput
            label="name"
            required
            autoFocus
            value={name}
            maxLength={120}
            spellCheck={false}
            onChange={(e) => setName(e.target.value)}
            placeholder="etl-pipeline"
          />
          <TextArea
            label="description"
            rows={3}
            value={description}
            maxLength={2000}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="nightly imports and exports"
          />
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setDialogOpen(false)}>
              dismiss
            </Button>
            <Button type="submit" variant="primary" loading={createMut.isPending} disabled={name.trim().length === 0}>
              create
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
