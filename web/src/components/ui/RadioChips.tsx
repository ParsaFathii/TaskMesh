import { useRef, type KeyboardEvent } from 'react';

/** Chip-style radio group with roving tabindex + arrow-key navigation. */
export function RadioChips<T extends string>({
  label,
  value,
  options,
  onChange,
  chipClass,
  id,
}: {
  label: string;
  value: T;
  options: readonly T[];
  onChange: (value: T) => void;
  /** function option → chip classes */
  chipClass: (option: T) => string;
  id: string;
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight' && e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
    e.preventDefault();
    const current = options.indexOf(value);
    const delta = e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 1;
    const next = options[(current + delta + options.length) % options.length];
    onChange(next);
    const idx = options.indexOf(next);
    refs.current[idx]?.focus();
  };

  return (
    <div role="radiogroup" aria-label={label} onKeyDown={onKeyDown} className="flex flex-wrap gap-1.5">
      {options.map((opt, i) => {
        const selected = opt === value;
        return (
          <button
            key={opt}
            id={`${id}-${opt}`}
            ref={(el) => {
              refs.current[i] = el;
            }}
            type="button"
            role="radio"
            aria-checked={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(opt)}
            className={`h-7 rounded-sm border px-2.5 font-mono text-[10.5px] font-semibold uppercase tracking-[0.08em] transition-colors ${
              selected ? chipClass(opt) : 'border-line text-mute hover:border-line-bright hover:text-ink'
            }`}
          >
            {opt}
          </button>
        );
      })}
    </div>
  );
}
