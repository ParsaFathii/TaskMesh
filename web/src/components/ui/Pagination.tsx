import { IconChevronLeft, IconChevronRight } from '../icons';
import { Button } from './Button';

/** Server-side pagination bar: PREV / page x of y / NEXT + range summary. */
export function Pagination({
  page,
  size,
  total,
  onPage,
  loading = false,
}: {
  page: number;
  size: number;
  total: number;
  onPage: (page: number) => void;
  loading?: boolean;
}) {
  const pages = Math.max(1, Math.ceil(total / Math.max(1, size)));
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  const prevDisabled = page <= 0 || loading;
  const nextDisabled = page >= pages - 1 || loading;

  return (
    <nav
      aria-label="Pagination"
      className="flex flex-wrap items-center gap-3 border-t border-line/70 px-3 py-2 font-mono text-[11px] text-mute"
    >
      <span className="tabular">
        {from.toLocaleString('en-US')}–{to.toLocaleString('en-US')} / {total.toLocaleString('en-US')}
      </span>
      <span className="tabular ml-auto">
        page <span className="text-ink">{page + 1}</span>/{pages}
      </span>
      <div className="flex items-center gap-1.5">
        <Button size="sm" variant="secondary" onClick={() => onPage(page - 1)} disabled={prevDisabled} aria-label="Previous page">
          <IconChevronLeft size={12} />
          prev
        </Button>
        <Button size="sm" variant="secondary" onClick={() => onPage(page + 1)} disabled={nextDisabled} aria-label="Next page">
          next
          <IconChevronRight size={12} />
        </Button>
      </div>
    </nav>
  );
}
