import { Fragment, useMemo, useState } from 'react';
import { IconChevronDown, IconChevronRight } from '../icons';
import { CopyButton } from './CopyButton';

/**
 * Collapsible JSON tree for payloads / results / metadata.
 * Keys are ink, strings teal, numbers amber, booleans rose/emerald, null dim.
 * TREE ⇄ RAW toggle; every node row is a keyboard-focusable button.
 */

type Json = unknown;

function typeOf(value: Json): 'object' | 'array' | 'string' | 'number' | 'boolean' | 'null' {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  switch (typeof value) {
    case 'object': return 'object';
    case 'string': return 'string';
    case 'number': return 'number';
    case 'boolean': return 'boolean';
    default: return 'null';
  }
}

function PrimitiveValue({ value }: { value: Json }) {
  const t = typeOf(value);
  if (t === 'string') {
    const s = value as string;
    return <span className="break-all text-teal">"{s.length > 400 ? `${s.slice(0, 400)}… (${s.length} chars)` : s}"</span>;
  }
  if (t === 'number') return <span className="tabular text-amber">{String(value)}</span>;
  if (t === 'boolean') return <span className={value ? 'text-emerald' : 'text-rose'}>{String(value)}</span>;
  return <span className="text-dim">null</span>;
}

function TreeNode({ name, value, depth, defaultOpen }: { name?: string; value: Json; depth: number; defaultOpen: number }) {
  const t = typeOf(value);
  const openable = t === 'object' || t === 'array';
  const [open, setOpen] = useState(depth < defaultOpen);

  const entries: [string, Json][] | [number, Json][] = useMemo(() => {
    if (t === 'object') return Object.entries(value as Record<string, Json>);
    if (t === 'array') return (value as Json[]).map((v, i) => [i, v] as [number, Json]);
    return [];
  }, [t, value]);

  if (!openable) {
    return (
      <div className="flex gap-1.5 py-px pl-4 font-mono text-[11.5px] leading-5">
        {name != null && <span className="text-ink">{name}:</span>}
        <PrimitiveValue value={value} />
      </div>
    );
  }

  const count = entries.length;
  const kind = t === 'array' ? `[${count}]` : `{${count}}`;

  return (
    <div className="pl-4">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="flex w-full items-start gap-1 py-px text-left font-mono text-[11.5px] leading-5 hover:bg-panel-2/60"
      >
        <span className="mt-1 shrink-0 text-dim" aria-hidden="true">
          {open ? <IconChevronDown size={11} /> : <IconChevronRight size={11} />}
        </span>
        {name != null && <span className="text-ink">{name}:</span>}
        <span className="text-mute">{kind}</span>
      </button>
      {open && (
        <div className="border-l border-line/70 pl-1">
          {count === 0 && <div className="py-px pl-4 font-mono text-[11.5px] text-dim">empty</div>}
          {entries.map(([k, v]) => (
            <TreeNode key={String(k)} name={String(k)} value={v} depth={depth + 1} defaultOpen={defaultOpen} />
          ))}
        </div>
      )}
    </div>
  );
}

export function JsonInspector({
  data,
  name,
  defaultOpen = 2,
  emptyLabel = 'empty',
  className = '',
}: {
  data: Json;
  name?: string;
  defaultOpen?: number;
  emptyLabel?: string;
  className?: string;
}) {
  const [mode, setMode] = useState<'tree' | 'raw'>('tree');
  const raw = useMemo(() => {
    try {
      return JSON.stringify(data, null, 2) ?? '';
    } catch {
      return String(data);
    }
  }, [data]);

  const isPrimitive = typeOf(data) !== 'object' && typeOf(data) !== 'array';

  return (
    <div className={`rounded-sm border border-line bg-bg-deep/60 ${className}`}>
      <div className="flex items-center gap-2 border-b border-line/60 px-2 py-1">
        <span className="micro text-dim">{name ?? 'json'}</span>
        <div className="ml-auto flex items-center gap-1" role="group" aria-label="JSON view mode">
          {(['tree', 'raw'] as const).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMode(m)}
              aria-pressed={mode === m}
              className={`rounded-sm border px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-[0.06em] ${
                mode === m ? 'border-amber/50 text-amber' : 'border-transparent text-dim hover:text-mute'
              }`}
            >
              {m}
            </button>
          ))}
        </div>
        <CopyButton value={raw} label="JSON" />
      </div>
      <div className="max-h-72 overflow-y-auto p-2 scroll-thin">
        {data == null ? (
          <p className="font-mono text-[11.5px] text-dim">{emptyLabel}</p>
        ) : mode === 'raw' ? (
          <pre className="whitespace-pre-wrap break-all font-mono text-[11.5px] leading-5 text-mute">{raw}</pre>
        ) : isPrimitive ? (
          <PrimitiveValue value={data} />
        ) : (
          <Fragment>
            <TreeNode value={data} depth={0} defaultOpen={defaultOpen} />
          </Fragment>
        )}
      </div>
    </div>
  );
}
