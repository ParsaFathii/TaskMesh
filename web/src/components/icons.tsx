import type { SVGProps } from 'react';

type IconProps = Omit<SVGProps<SVGSVGElement>, 'd'> & { size?: number };

/** Square-capped stroke icons — matches the console's sharp geometry. */
function Svg({ d, size = 18, ...rest }: IconProps & { d?: string | string[] }) {
  const paths = Array.isArray(d) ? d : d ? [d] : [];
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="square"
      strokeLinejoin="miter"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {paths.map((p, i) => (
        <path key={i} d={p} />
      ))}
    </svg>
  );
}

export const IconLanes = (p: IconProps) => (
  <Svg {...p} d={['M4 5h16', 'M4 12h16', 'M4 19h16', 'M8 3.2v3.6', 'M16 10.2v3.6', 'M11 17.2v3.6']} />
);

export const IconFolder = (p: IconProps) => <Svg {...p} d={['M3 6h6l2 2h10v11H3z', 'M3 6v13']} />;

export const IconStack = (p: IconProps) => (
  <Svg {...p} d={['M4 4h16v4H4z', 'M4 10h16v4H4z', 'M4 16h16v4H4z', 'M7 6h.01', 'M7 12h.01', 'M7 18h.01']} />
);

export const IconPlus = (p: IconProps) => <Svg {...p} d={['M12 5v14', 'M5 12h14']} />;

export const IconCpu = (p: IconProps) => (
  <Svg {...p} d={['M7 7h10v10H7z', 'M4 10h3', 'M4 14h3', 'M17 10h3', 'M17 14h3', 'M10 4v3', 'M14 4v3', 'M10 17v3', 'M14 17v3', 'M10.5 10.5h3v3h-3z']} />
);

export const IconTerminal = (p: IconProps) => <Svg {...p} d={['M3 5h18v14H3z', 'M7 9l3 3-3 3', 'M13 15h4']} />;

export const IconChart = (p: IconProps) => <Svg {...p} d={['M4 20V4', 'M4 20h16', 'M8 16v-5', 'M12 16V8', 'M16 16v-8', 'M20 16v-3']} />;

export const IconSliders = (p: IconProps) => <Svg {...p} d={['M5 6h14', 'M5 12h14', 'M5 18h14', 'M9 4v4', 'M15 10v4', 'M8 16v4']} />;

export const IconUser = (p: IconProps) => <Svg {...p} d={['M12 4a3.5 3.5 0 100 7 3.5 3.5 0 000-7z', 'M5 20c1.2-3.5 3.8-5 7-5s5.8 1.5 7 5']} />;

export const IconLogout = (p: IconProps) => <Svg {...p} d={['M10 4H4v16h6', 'M14 8l4 4-4 4', 'M8 12h10']} />;

export const IconX = (p: IconProps) => <Svg {...p} d={['M6 6l12 12', 'M18 6L6 18']} />;

export const IconCheck = (p: IconProps) => <Svg {...p} d={['M5 13l5 5L20 7']} />;

export const IconCopy = (p: IconProps) => <Svg {...p} d={['M9 9h11v11H9z', 'M5 15H4V4h11v1']} />;

export const IconChevronDown = (p: IconProps) => <Svg {...p} d={['M6 9l6 6 6-6']} />;
export const IconChevronRight = (p: IconProps) => <Svg {...p} d={['M9 6l6 6-6 6']} />;
export const IconChevronLeft = (p: IconProps) => <Svg {...p} d={['M15 6l-6 6 6 6']} />;

export const IconRefresh = (p: IconProps) => <Svg {...p} d={['M20 12a8 8 0 11-2.34-5.66', 'M20 4v4h-4']} />;

export const IconDownload = (p: IconProps) => <Svg {...p} d={['M12 4v10', 'M8 11l4 3 4-3', 'M4 19h16']} />;

export const IconAlert = (p: IconProps) => <Svg {...p} d={['M12 4l9 16H3z', 'M12 10v4', 'M12 17.2v.01']} />;

export const IconInfo = (p: IconProps) => <Svg {...p} d={['M12 4a8 8 0 100 16 8 8 0 000-16z', 'M12 11v5', 'M12 8.2v.01']} />;

export const IconPulse = (p: IconProps) => <Svg {...p} d={['M3 12h4l2-6 4 12 2-6h6']} />;

export const IconClock = (p: IconProps) => <Svg {...p} d={['M12 4a8 8 0 100 16 8 8 0 000-16z', 'M12 8v4l3 2']} />;

export const IconSearch = (p: IconProps) => <Svg {...p} d={['M11 4a7 7 0 100 14 7 7 0 000-14z', 'M16 16l4 4']} />;

export const IconFilter = (p: IconProps) => <Svg {...p} d={['M4 5h16l-6 7v6l-4 2v-8z']} />;

export const IconEye = (p: IconProps) => <Svg {...p} d={['M2 12s4-6 10-6 10 6 10 6-4 6-10 6-10-6-10-6z', 'M12 9.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5z']} />;

export const IconRetry = (p: IconProps) => <Svg {...p} d={['M4 12a8 8 0 112.34 5.66', 'M4 20v-4h4']} />;

export const IconStop = (p: IconProps) => <Svg {...p} d={['M7 7h10v10H7z']} />;

export const IconLock = (p: IconProps) => <Svg {...p} d={['M6 11h12v9H6z', 'M9 11V8a3 3 0 016 0v3', 'M12 14.5v2']} />;

export const IconGrid = (p: IconProps) => <Svg {...p} d={['M4 4h7v7H4z', 'M13 4h7v7h-7z', 'M4 13h7v7H4z', 'M13 13h7v7h-7z']} />;

export const IconTable = (p: IconProps) => <Svg {...p} d={['M3 5h18v14H3z', 'M3 10h18', 'M3 15h18', 'M9 5v14', 'M15 5v14']} />;

/** TaskMesh mark — 3×3 mesh of squares with an amber diagonal lane. */
export function LogoMark({ size = 20, ...rest }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" focusable="false" {...rest}>
      <g stroke="#3e4753" strokeWidth={1.4} fill="none">
        <rect x={5} y={5} width={6} height={6} />
        <rect x={21} y={5} width={6} height={6} />
        <rect x={5} y={21} width={6} height={6} />
        <rect x={21} y={21} width={6} height={6} />
      </g>
      <g fill="#f5a623">
        <rect x={13} y={5} width={6} height={6} opacity={0.55} />
        <rect x={5} y={13} width={6} height={6} opacity={0.55} />
        <rect x={21} y={13} width={6} height={6} opacity={0.55} />
        <rect x={13} y={21} width={6} height={6} opacity={0.55} />
        <rect x={13} y={13} width={6} height={6} />
      </g>
    </svg>
  );
}
