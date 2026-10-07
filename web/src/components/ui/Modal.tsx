import { useEffect, useRef, type ReactNode } from 'react';
import { IconX } from '../icons';
import { Button } from './Button';

/** Minimal accessible modal: Esc closes, backdrop click closes, focus returns. */
export function Modal({
  open,
  onClose,
  title,
  children,
  width = 'max-w-md',
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  width?: string;
}) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) return;
    returnFocusRef.current = document.activeElement as HTMLElement | null;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
      returnFocusRef.current?.focus?.();
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onClick={onClose}
      data-testid="modal-backdrop"
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={`w-full ${width} rounded-md border border-line-bright bg-panel shadow-[0_8px_40px_rgba(0,0,0,0.5)]`}
        onClick={(e) => e.stopPropagation()}
      >
        <header className="flex items-center gap-2 border-b border-line px-3 py-2">
          <span className="h-1.5 w-1.5 bg-amber" aria-hidden="true" />
          <h2 className="micro text-mute">{title}</h2>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label="Close dialog"
            className="ml-auto text-mute hover:text-ink"
          >
            <IconX size={14} />
          </button>
        </header>
        <div className="p-4">{children}</div>
      </div>
    </div>
  );
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  message,
  confirmLabel = 'confirm',
  tone = 'primary',
  busy = false,
  error,
}: {
  open: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  message: ReactNode;
  confirmLabel?: string;
  tone?: 'primary' | 'danger';
  busy?: boolean;
  error?: string | null;
}) {
  return (
    <Modal open={open} onClose={onClose} title={title} width="max-w-md">
      <div className="font-mono text-[12px] leading-5 text-mute">{message}</div>
      {error != null && (
        <p className="mt-3 rounded-sm border border-rose/40 bg-rose/5 px-2 py-1.5 font-mono text-[11px] text-rose" role="alert">
          {error}
        </p>
      )}
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" onClick={onClose} disabled={busy}>
          dismiss
        </Button>
        <Button variant={tone === 'danger' ? 'danger' : 'primary'} onClick={onConfirm} loading={busy}>
          {confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}
