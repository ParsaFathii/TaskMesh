import { fmtDateTime, fmtDuration, parseIso } from '../lib/format';

export interface Step {
  key: string;
  label: string;
  at?: string | null;
  note?: string;
  tone?: 'amber' | 'emerald' | 'rose' | 'orange' | 'mute' | 'yellow' | 'teal';
  /** active head of the timeline (pulsing node) */
  active?: boolean;
}

const TONES: Record<NonNullable<Step['tone']>, string> = {
  amber: 'bg-amber border-amber text-amber',
  emerald: 'bg-emerald border-emerald text-emerald',
  rose: 'bg-rose border-rose text-rose',
  orange: 'bg-orange border-orange text-orange',
  yellow: 'bg-yellow border-yellow text-yellow',
  mute: 'bg-mute border-mute text-mute',
  teal: 'bg-teal border-teal text-teal',
};

/**
 * TimelineStepper — vertical execution timeline with real timestamps and
 * inter-step durations. Pending steps render dimmed with dashed rails.
 */
export function TimelineStepper({ steps }: { steps: Step[] }) {
  let prevAt: number | null = null;

  return (
    <ol className="relative" aria-label="Execution timeline">
      {steps.map((step, i) => {
        const at = parseIso(step.at);
        const delta = at != null && prevAt != null ? at - prevAt : null;
        if (at != null) prevAt = at;
        const done = at != null;
        const isLast = i === steps.length - 1;
        const nodeCls = done
          ? TONES[step.tone ?? 'mute']
          : 'bg-transparent border-dashed border-line-bright text-dim';

        return (
          <li key={step.key} className="relative flex gap-3 pb-4 last:pb-0">
            <div className="flex flex-col items-center">
              <span
                className={`mt-1 h-3 w-3 shrink-0 rounded-[3px] border ${nodeCls} ${
                  step.active && done ? 'animate-pulse-status' : ''
                }`}
                aria-hidden="true"
              />
              {!isLast && (
                <span
                  className={`mt-0.5 w-px flex-1 ${done ? 'bg-line-bright' : 'border-l border-dashed border-line'}`}
                  aria-hidden="true"
                />
              )}
            </div>
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
                <span className={`micro ${done ? 'text-ink' : 'text-dim'}`}>{step.label}</span>
                {done && step.at != null && (
                  <span className="tabular font-mono text-[11px] text-mute">{fmtDateTime(step.at)}</span>
                )}
                {delta != null && delta > 0 && (
                  <span className="tabular font-mono text-[10.5px] text-dim">+{fmtDuration(delta)}</span>
                )}
              </div>
              {done && step.note != null && (
                <p className="mt-0.5 font-mono text-[10.5px] leading-4 text-dim">{step.note}</p>
              )}
              {!done && (
                <p className="mt-0.5 font-mono text-[10.5px] text-dim">
                  {step.note ?? 'pending'}
                </p>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
