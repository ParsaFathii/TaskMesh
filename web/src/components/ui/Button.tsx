import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { IconRefresh } from '../icons';

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost';
type Size = 'sm' | 'md';

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-amber text-[#161006] border border-amber hover:bg-amber-deep hover:border-amber-deep disabled:hover:bg-amber',
  secondary: 'bg-transparent text-ink border border-line hover:border-line-bright hover:bg-panel-2',
  danger: 'bg-transparent text-rose border border-rose/50 hover:bg-rose/10 hover:border-rose',
  ghost: 'bg-transparent text-mute border border-transparent hover:text-ink hover:bg-panel-2',
};

const SIZES: Record<Size, string> = {
  sm: 'h-7 px-2 gap-1 text-[10px]',
  md: 'h-8 px-3 gap-1.5 text-[11px]',
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  icon?: ReactNode;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading = false, icon, children, className = '', disabled, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={rest.type ?? 'button'}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center rounded-sm font-mono font-semibold uppercase tracking-[0.08em] transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${VARIANTS[variant]} ${SIZES[size]} ${className}`}
      {...rest}
    >
      {loading ? <IconRefresh size={13} className="animate-spin" /> : icon}
      {children}
    </button>
  );
});
