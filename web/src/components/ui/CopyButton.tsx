import { useCallback, useEffect, useRef, useState } from 'react';
import { IconCheck, IconCopy } from '../icons';

async function writeClipboard(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through to legacy path */
  }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  } catch {
    return false;
  }
}

/** Copy button with a 1.4s "copied" confirmation state. */
export function CopyButton({ value, label = 'copy', size = 13 }: { value: string; label?: string; size?: number }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (timer.current != null) clearTimeout(timer.current);
  }, []);

  const onClick = useCallback(async () => {
    const ok = await writeClipboard(value);
    if (ok) {
      setCopied(true);
      if (timer.current != null) clearTimeout(timer.current);
      timer.current = setTimeout(() => setCopied(false), 1400);
    }
  }, [value]);

  return (
    <button
      type="button"
      onClick={onClick}
      title={`Copy ${value}`}
      aria-label={`Copy ${label}: ${value}`}
      className={`inline-flex h-5 w-5 items-center justify-center rounded-sm border border-transparent ${
        copied ? 'text-emerald' : 'text-dim hover:border-line hover:text-mute'
      }`}
    >
      {copied ? <IconCheck size={size - 2} /> : <IconCopy size={size - 2} />}
    </button>
  );
}
