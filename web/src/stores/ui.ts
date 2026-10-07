import { useEffect } from 'react';
import { create } from 'zustand';

export interface ContextChip {
  label: string;
  tone: 'amber' | 'emerald' | 'teal' | 'rose' | 'yellow' | 'mute';
  mono?: boolean;
}

interface UiState {
  /** pathname the chips belong to; stale chips from other pages are ignored */
  chipPath: string | null;
  chips: ContextChip[];
  setChips: (path: string, chips: ContextChip[]) => void;
}

export const useUiStore = create<UiState>((set) => ({
  chipPath: null,
  chips: [],
  setChips: (path, chips) => set({ chipPath: path, chips }),
}));

/**
 * Set the top-bar context chips for the current page. Chips are ignored once
 * the user navigates elsewhere (keyed by pathname), so no manual cleanup is
 * needed. Call with the current pathname and chips; re-runs when deps change.
 */
export function usePageChips(pathname: string, chips: ContextChip[], deps: readonly unknown[]): void {
  const setChips = useUiStore((s) => s.setChips);
  useEffect(() => {
    setChips(pathname, chips);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [setChips, pathname, ...deps]);
}
