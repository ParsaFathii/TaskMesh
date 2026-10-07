import { describe, expect, it, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import JobDetailPage from '../pages/JobDetailPage';
import { useAuthStore } from '../stores/auth';
import {
  ATTEMPTS,
  JOB_FAILED,
  JOB_RUNNING,
  JOB_SUCCEEDED,
  LOGS,
  OPERATOR_SESSION,
  mockFetch,
} from './fixtures';

function makeClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

function renderJob(job: typeof JOB_RUNNING, routeId = job.id) {
  const fetchMock = mockFetch({
    // live backend wraps GET /jobs/{id} as {"job": …, "worker": …}
    [`GET /jobs/${job.id}`]: {
      job: { ...job, worker: undefined },
      worker: job.worker ?? null,
    },
    [`GET /jobs/${job.id}/attempts`]: ATTEMPTS,
    [`GET /jobs/${job.id}/logs`]: LOGS,
    [`GET /jobs/${job.id}/result`]: [200, { ok: true }],
    'GET /job-types': [],
  });
  const client = makeClient();
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/jobs/${routeId}`]}>
        <Routes>
          <Route path="/jobs/:id" element={<JobDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return fetchMock;
}

beforeEach(() => {
  useAuthStore.setState({ ...OPERATOR_SESSION, authExpired: false });
});

describe('JobDetails page', () => {
  it('renders the execution timeline with real timestamps from the fixture', async () => {
    renderJob(JOB_RUNNING);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /job 11111111/i })).toBeInTheDocument();
    });

    // timeline steps (scoped to the timeline list — the attempts table also has a "started" column)
    const timeline = screen.getByLabelText('Execution timeline');
    expect(within(timeline).getByText('created')).toBeInTheDocument();
    expect(within(timeline).getByText('queued')).toBeInTheDocument();
    expect(within(timeline).getByText('started')).toBeInTheDocument();
    // timestamps rendered in the panel
    expect(screen.getByText(/2026-01-01 10:00:00/)).toBeInTheDocument();
    expect(screen.getByText(/2026-01-01 10:00:01/)).toBeInTheDocument();
    expect(screen.getByText(/2026-01-01 10:00:05/)).toBeInTheDocument();

    // header facts
    expect(screen.getAllByText('RUNNING').length).toBeGreaterThan(0);
    expect(screen.getByText('HIGH')).toBeInTheDocument();
    expect(screen.getByText('cpu_benchmark')).toBeInTheDocument();
    expect(screen.getByText('42%')).toBeInTheDocument();
    expect(screen.getByText('0/3')).toBeInTheDocument();
  });
  it('shows CANCEL (not RETRY) for a RUNNING job owned or operated by the user', async () => {
    renderJob(JOB_RUNNING);

    const cancel = await screen.findByRole('button', { name: /cancel job/i });
    expect(cancel).toBeVisible();
    expect(screen.queryByRole('button', { name: /retry job/i })).not.toBeInTheDocument();
  });

  it('renders no actions for a SUCCEEDED job (terminal success — SPEC §5 forbids retry)', async () => {
    renderJob(JOB_SUCCEEDED);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /job 22222222/i })).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /cancel job/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /retry job/i })).not.toBeInTheDocument();
  });

  it('shows RETRY and the last error for a FAILED job', async () => {
    renderJob(JOB_FAILED);

    expect(await screen.findByRole('button', { name: /retry job/i })).toBeInTheDocument();
    expect(screen.getAllByText(/workload crashed: simulated fault/i).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /cancel job/i })).not.toBeInTheDocument();
  });

  it('renders attempt history from /attempts', async () => {
    renderJob(JOB_RUNNING);

    await waitFor(() => {
      expect(screen.getByText('attempts · 2')).toBeInTheDocument();
    });
    expect(screen.getByText(/transient connection reset/i)).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
  });

  it('renders the log console with level filters and log lines', async () => {
    renderJob(JOB_RUNNING);

    await waitFor(() => {
      expect(screen.getByText(/claimed job, starting workload/i)).toBeInTheDocument();
    });
    expect(screen.getByText(/cpu thermal throttling detected/i)).toBeInTheDocument();
    for (const level of ['ALL', 'DEBUG', 'INFO', 'WARN', 'ERROR']) {
      expect(screen.getByRole('button', { name: new RegExp(`^${level} `, 'i') })).toBeInTheDocument();
    }
  });

  it('renders payload JSON and links the worker for operators', async () => {
    const fetchMock = renderJob(JOB_RUNNING);
    await waitFor(() => {
      expect(screen.getByText('"primes"')).toBeInTheDocument();
    });

    const link = screen.getByRole('link', { name: 'worker-01' });
    expect(link).toHaveAttribute('href', '/workers/w-11111111-1111-1111-1111-111111111111');

    // result is not fetched while the job is not SUCCEEDED
    expect(fetchMock.calls.some((c) => c.url.includes('/result'))).toBe(false);
  });

  it('does not render action buttons for a non-owner USER (backend enforces anyway)', async () => {
    useAuthStore.setState({
      token: 'user-token',
      user: { id: 'someone-else', username: 'plain-user', role: 'USER' },
      expiresAt: Date.now() + 1000,
      authExpired: false,
    });
    renderJob(JOB_RUNNING);

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /job 11111111/i })).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /cancel job/i })).not.toBeInTheDocument();
  });
});

describe('JobDetails cancel dialog', () => {
  it('opens a confirmation before POSTing the cancel endpoint', async () => {
    const fetchMock = mockFetch({
      [`GET /jobs/${JOB_RUNNING.id}`]: {
        job: { ...JOB_RUNNING, worker: undefined },
        worker: JOB_RUNNING.worker ?? null,
      },
      [`GET /jobs/${JOB_RUNNING.id}/attempts`]: ATTEMPTS,
      [`GET /jobs/${JOB_RUNNING.id}/logs`]: LOGS,
      [`POST /jobs/${JOB_RUNNING.id}/cancel`]: { ...JOB_RUNNING, status: 'CANCELLED' },
    });
    const { userEvent } = await import('@testing-library/user-event');
    const user = userEvent.setup();

    const client = makeClient();
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/jobs/${JOB_RUNNING.id}`]}>
          <Routes>
            <Route path="/jobs/:id" element={<JobDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await user.click(await screen.findByRole('button', { name: /cancel job/i }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(/cancellation request/i)).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: /cancel job/i }));
    await waitFor(() => {
      expect(fetchMock.calls.some((c) => c.url.includes('/cancel') && c.init?.method === 'POST')).toBe(true);
    });
  });
});

describe('JobDetails role gating of worker links', () => {
  it('hides worker deep links for USER role (403 endpoint)', async () => {
    useAuthStore.setState({
      token: 'user-token',
      user: { id: 'someone-else', username: 'plain-user', role: 'USER' },
      expiresAt: Date.now() + 1000,
      authExpired: false,
    });
    renderJob(JOB_RUNNING);
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /job 11111111/i })).toBeInTheDocument();
    });
    expect(screen.queryByRole('link', { name: 'worker-01' })).not.toBeInTheDocument();
    expect(screen.getByText('worker-01')).toBeInTheDocument();
  });
});
