import { create } from 'zustand';
import type { JobLog, TickerItem, TickerTone } from '../types';

const TICKER_CAP = 240;
const LOG_CAP = 500;

export interface EventsState {
  ticker: TickerItem[];
  /** jobId → epoch ms of the last job.updated flash */
  flashedAt: Record<string, number>;
  /** live log lines appended by WS job.log frames, per job */
  logsByJob: Record<string, JobLog[]>;
  pushTicker: (item: Omit<TickerItem, 'id'>) => void;
  flashJob: (jobId: string) => void;
  appendJobLog: (log: JobLog) => void;
  clearJobLogs: (jobId: string) => void;
}

let tickerSeq = 0;

export const initialEventsState: EventsState = {
  ticker: [],
  flashedAt: {},
  logsByJob: {},
  pushTicker: () => undefined,
  flashJob: () => undefined,
  appendJobLog: () => undefined,
  clearJobLogs: () => undefined,
};

export const useEventsStore = create<EventsState>((set, get) => ({
  ticker: [],
  flashedAt: {},
  logsByJob: {},
  pushTicker: (item) => {
    tickerSeq += 1;
    const next: TickerItem = { ...item, id: tickerSeq };
    const ticker = [...get().ticker, next];
    set({ ticker: ticker.length > TICKER_CAP ? ticker.slice(ticker.length - TICKER_CAP) : ticker });
  },
  flashJob: (jobId) => {
    set({ flashedAt: { ...get().flashedAt, [jobId]: Date.now() } });
  },
  appendJobLog: (log) => {
    const existing = get().logsByJob[log.jobId] ?? [];
    const merged = [...existing, log];
    set({
      logsByJob: {
        ...get().logsByJob,
        [log.jobId]: merged.length > LOG_CAP ? merged.slice(merged.length - LOG_CAP) : merged,
      },
    });
  },
  clearJobLogs: (jobId) => {
    set({ logsByJob: { ...get().logsByJob, [jobId]: [] } });
  },
}));

/** Selector helper: live log lines for one job (stable array reference). */
export function selectJobLogs(jobId: string): (s: EventsState) => JobLog[] {
  return (s) => s.logsByJob[jobId] ?? EMPTY_LOGS;
}

const EMPTY_LOGS: JobLog[] = [];

export function toneClass(tone: TickerTone): string {
  switch (tone) {
    case 'amber': return 'text-amber';
    case 'emerald': return 'text-emerald';
    case 'teal': return 'text-teal';
    case 'rose': return 'text-rose';
    case 'yellow': return 'text-yellow';
    case 'mute': return 'text-mute';
    default: return 'text-dim';
  }
}
