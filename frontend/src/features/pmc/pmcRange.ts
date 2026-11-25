import type { PmcDayDto } from '../../api/generated/types.gen';

/** How much of the **Performance Management Chart** is in view, always ending at its last day. */
export type PmcRange = '90d' | '1y' | 'all';

/** The ranges the athlete picks from, in picker order, with how many trailing days each shows. */
export const PMC_RANGES: ReadonlyArray<{
  readonly id: PmcRange;
  readonly label: string;
  readonly days: number;
}> = [
  { id: '90d', label: '90 days', days: 90 },
  { id: '1y', label: '1 year', days: 365 },
  { id: 'all', label: 'All', days: Number.POSITIVE_INFINITY },
];

export const DEFAULT_PMC_RANGE: PmcRange = '90d';

/** Inclusive indices of the first and last day in view. */
export interface DayWindow {
  readonly fromIndex: number;
  readonly toIndex: number;
}

/**
 * The trailing days a range shows, clamped to the series: a range longer than the data starts at the
 * first day. `null` without days.
 */
export function visibleWindow(days: readonly PmcDayDto[], range: PmcRange): DayWindow | null {
  if (days.length === 0) return null;
  const span = PMC_RANGES.find((option) => option.id === range)?.days ?? Number.POSITIVE_INFINITY;
  const toIndex = days.length - 1;
  return { fromIndex: Math.max(0, days.length - span), toIndex };
}
