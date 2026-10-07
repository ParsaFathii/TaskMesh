import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { QueueLanes } from '../components/QueueLanes';
import { JOB_CRITICAL, JOB_LOW, JOB_NORMAL } from './fixtures';

function JobRoute(): string {
  const { id } = useParams<{ id: string }>();
  return id ?? '';
}

describe('QueueLanes', () => {
  it('renders exactly four lanes in claim order (CRITICAL first)', () => {
    const { container } = render(
      <MemoryRouter>
        <QueueLanes jobs={[JOB_CRITICAL, JOB_LOW, JOB_NORMAL]} />
      </MemoryRouter>,
    );

    const lanes = Array.from(container.querySelectorAll('[data-lane]')) as HTMLElement[];
    expect(lanes).toHaveLength(4);
    expect(lanes[0]).toHaveAttribute('data-lane', 'CRITICAL');
    expect(lanes[1]).toHaveAttribute('data-lane', 'HIGH');
    expect(lanes[2]).toHaveAttribute('data-lane', 'NORMAL');
    expect(lanes[3]).toHaveAttribute('data-lane', 'LOW');
  });

  it('places job chips into the lane matching their priority', () => {
    const { container } = render(
      <MemoryRouter>
        <QueueLanes jobs={[JOB_CRITICAL, JOB_LOW, JOB_NORMAL]} />
      </MemoryRouter>,
    );

    const criticalLane = container.querySelector('[data-lane="CRITICAL"]') as HTMLElement;
    const normalLane = container.querySelector('[data-lane="NORMAL"]') as HTMLElement;
    const lowLane = container.querySelector('[data-lane="LOW"]') as HTMLElement;
    const highLane = container.querySelector('[data-lane="HIGH"]') as HTMLElement;

    expect(within(criticalLane).getByText(JOB_CRITICAL.type)).toBeInTheDocument();
    expect(within(normalLane).getByText(JOB_NORMAL.type)).toBeInTheDocument();
    expect(within(lowLane).getByText(JOB_LOW.type)).toBeInTheDocument();
    expect(within(highLane).queryByRole('link')).toBeNull(); // empty lane — no chips

    const chip = within(criticalLane).getByRole('link');
    expect(chip).toHaveAttribute('href', `/jobs/${JOB_CRITICAL.id}`);
  });

  it('shows per-lane counts and prefers authoritative depth from metrics', () => {
    const { container } = render(
      <MemoryRouter>
        <QueueLanes
          jobs={[JOB_CRITICAL, JOB_LOW, JOB_NORMAL]}
          depth={{ CRITICAL: 9, HIGH: 4, NORMAL: 12, LOW: 2 }}
        />
      </MemoryRouter>,
    );

    const counts = container.querySelectorAll('.font-bold');
    expect(counts[0]).toHaveTextContent('9');
    expect(counts[1]).toHaveTextContent('4');
    expect(counts[2]).toHaveTextContent('12');
    expect(counts[3]).toHaveTextContent('2');
  });

  it('navigates to the job detail on chip click', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<QueueLanes jobs={[JOB_CRITICAL]} />} />
          <Route path="/jobs/:id" element={<div data-testid="job-route">{<JobRoute />}</div>} />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('link'));
    expect(screen.getByTestId('job-route')).toHaveTextContent(JOB_CRITICAL.id);
  });

  it('renders an explicit empty state per lane', () => {
    const { container } = render(
      <MemoryRouter>
        <QueueLanes jobs={[]} />
      </MemoryRouter>,
    );
    for (const lane of Array.from(container.querySelectorAll('[data-lane]')) as HTMLElement[]) {
      expect(within(lane).getByText(/lane empty/i)).toBeInTheDocument();
    }
  });
});
