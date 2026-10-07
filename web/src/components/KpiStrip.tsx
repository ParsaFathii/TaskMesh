import type { ReactNode } from 'react';

/** KPI tile: colored left rail, big monospace number, micro label. */
export function KpiTile({
  label,
  value,
  tone = 'amber',
  sub,
  loading = false,
  error = false,
  title,
}: {
  label: string;
  value: ReactNode;
  tone?: 'amber' | 'emerald' | 'rose' | 'teal' | 'yellow' | 'mute';
  sub?: ReactNode;
  loading?: boolean;
  error?: boolean;
  title?: string;
}) {
  const rails: Record<string, string> = {
    amber: 'bg-amber',
    emerald: 'bg-emerald',
    rose: 'bg-rose',
    teal: 'bg-teal',
    yellow: 'bg-yellow',
    mute: 'bg-mute',
  };
  return (
    <div title={title} className="relative min-w-0 overflow-hidden rounded-md border border-line bg-panel px-3 py-2.5">
      <span className={`absolute inset-y-1.5 left-0 w-[3px] rounded-r-sm ${rails[tone]}`} aria-hidden="true" />
      <p className="micro text-mute">{label}</p>
      <p className="tabular mt-1 font-mono text-[22px] font-bold leading-6 text-ink">
        {loading ? <span className="text-dim">…</span> : error ? <span className="text-rose">?</span> : value}
      </p>
      {sub != null && <p className="mt-0.5 font-mono text-[10px] text-dim">{sub}</p>}
    </div>
  );
}

/** Horizontal bar row used across the Metrics page. */
export function BarRow({
  label,
  value,
  max,
  tone,
  suffix,
}: {
  label: string;
  value: number;
  max: number;
  tone: 'amber' | 'emerald' | 'rose' | 'teal' | 'yellow' | 'orange' | 'mute' | 'dim';
  suffix?: string;
}) {
  const fills: Record<string, string> = {
    amber: 'bg-amber',
    emerald: 'bg-emerald',
    rose: 'bg-rose',
    teal: 'bg-teal',
    yellow: 'bg-yellow',
    orange: 'bg-orange',
    mute: 'bg-mute',
    dim: 'bg-dim',
  };
  const pct = max > 0 ? Math.max(value > 0 ? 3 : 0, Math.round((value / max) * 100)) : 0;
  return (
    <div className="flex items-center gap-3 py-1.5">
      <span className="micro w-[86px] shrink-0 text-mute">{label}</span>
      <div className="h-2 min-w-0 flex-1 overflow-hidden rounded-[2px] border border-line bg-bg-deep" role="presentation">
        <div className={`h-full ${fills[tone]}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="tabular w-12 shrink-0 text-right font-mono text-[12px] font-semibold text-ink">
        {value.toLocaleString('en-US')}
        {suffix}
      </span>
    </div>
  );
}

/** Hand-rolled SVG sparkline (throughput per hour). No chart library. */
export function Sparkline({ points, height = 64 }: { points: number[]; height?: number }) {
  if (points.length < 2) {
    return <p className="py-4 text-center font-mono text-[11px] text-dim">insufficient data for a trend</p>;
  }
  const width = 640;
  const max = Math.max(...points, 1);
  const stepX = width / (points.length - 1);
  const coords = points.map((p, i) => [i * stepX, height - 6 - (p / max) * (height - 14)] as const);
  const line = coords.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `${line} L${width},${height} L0,${height} Z`;
  const last = coords[coords.length - 1];

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className="w-full"
      role="img"
      aria-label={`Throughput trend, max ${max} per hour`}
    >
      <path d={area} fill="var(--color-teal)" opacity={0.08} />
      <path d={line} fill="none" stroke="var(--color-teal)" strokeWidth={1.6} strokeLinejoin="round" />
      <rect x={last[0] - 2.5} y={last[1] - 2.5} width={5} height={5} fill="var(--color-amber)" />
    </svg>
  );
}
