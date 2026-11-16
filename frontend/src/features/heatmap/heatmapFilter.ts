/**
 * The **Heatmap Filter**: a date range that may be open at either end, plus a set of sports. A year is a
 * shorthand for a date range, not a separate criterion.
 *
 * Dates are calendar days in the browser's timezone. They become instants only when the filter is sent, so
 * a run at 23:30 local time on 31 December counts in that year.
 */
import type { HeatmapFilterParams } from '../../api/heatmap';
import { ACTIVITY_SPORTS, type ActivitySport } from '../../lib/format';

export interface HeatmapFilter {
  /** First day included, as `YYYY-MM-DD`; `null` leaves the range open at the start. */
  readonly from: string | null;
  /** Last day included, as `YYYY-MM-DD`; `null` leaves the range open at the end. */
  readonly to: string | null;
  /** The sports included; empty matches nothing. */
  readonly sports: readonly ActivitySport[];
}

/** All time and all sports. */
export const DEFAULT_HEATMAP_FILTER: HeatmapFilter = {
  from: null,
  to: null,
  sports: ACTIVITY_SPORTS,
};

/** The date range a year shortcut stands for. */
export function yearRange(year: number): Pick<HeatmapFilter, 'from' | 'to'> {
  return { from: `${year}-01-01`, to: `${year}-12-31` };
}

/** Whether [filter]'s date range is exactly [year]. */
export function isYear(filter: HeatmapFilter, year: number): boolean {
  const range = yearRange(year);
  return filter.from === range.from && filter.to === range.to;
}

const hasEverySport = (sports: readonly ActivitySport[]) =>
  ACTIVITY_SPORTS.every((sport) => sports.includes(sport));

/** Whether [filter]'s date range is open at both ends. */
export function isAllTime(filter: HeatmapFilter): boolean {
  return filter.from === null && filter.to === null;
}

/** Whether [filter] has an empty sport set, which matches nothing. */
export function hasNoSports(filter: HeatmapFilter): boolean {
  return filter.sports.length === 0;
}

/** Whether [filter]'s From day is after its To day, a back-to-front range that matches nothing. */
export function isInvertedRange(filter: HeatmapFilter): boolean {
  // `YYYY-MM-DD` strings sort as the days they name.
  return filter.from !== null && filter.to !== null && filter.from > filter.to;
}

/** Whether [filter] is all time and all sports. */
export function isDefaultFilter(filter: HeatmapFilter): boolean {
  return isAllTime(filter) && hasEverySport(filter.sports);
}

/** Local midnight at the start of `YYYY-MM-DD` [day], [plusDays] later, as an ISO instant. */
function localMidnight(day: string, plusDays = 0): string {
  const [year, month, date] = day.split('-').map(Number) as [number, number, number];
  return new Date(year, month - 1, date + plusDays).toISOString();
}

/**
 * The filter as Heatmap API query parameters, without a leading `?`: `from` inclusive and `to` exclusive, as
 * instants of local midnight; `sports` repeated, or empty to match nothing, and left out for every sport. The
 * default filter is the empty string, so its tile URLs carry no parameters.
 */
export function toParams(filter: HeatmapFilter): HeatmapFilterParams {
  const params = new URLSearchParams();
  if (filter.from) params.set('from', localMidnight(filter.from));
  // The To day is included in full, so the range ends at the next local midnight.
  if (filter.to) params.set('to', localMidnight(filter.to, 1));
  if (hasNoSports(filter)) params.set('sports', '');
  else if (!hasEverySport(filter.sports))
    for (const sport of ACTIVITY_SPORTS.filter((s) => filter.sports.includes(s)))
      params.append('sports', sport);
  return params.toString() as HeatmapFilterParams;
}

/** The browser's IANA timezone, which year shortcuts are counted in. */
export const browserTimeZone = (): string => Intl.DateTimeFormat().resolvedOptions().timeZone;
