/**
 * Pure helpers for the **Personal Record Progression** chart: which Benchmark Distance it opens on,
 * the step points it plots, and the labels of its date axis.
 */
import type { BenchmarkProgressionDto, PersonalRecordStepDto } from '../../api/generated/types.gen';

/** Height of the chart's canvas; the React card reserves the same slot. */
export const PROGRESSION_PLOT_HEIGHT_PX = 220;

export type BenchmarkDistance = BenchmarkProgressionDto['benchmarkDistance'];

/** `x` in epoch seconds (uPlot's time scale), `y` in elapsed seconds. */
export interface StepPoints {
  readonly x: number[];
  readonly y: number[];
}

/** The longest Benchmark Distance that has a record, or `null` when none has. */
export function defaultBenchmark(
  progressions: readonly BenchmarkProgressionDto[],
): BenchmarkDistance | null {
  const withRecords = progressions.filter((p) => p.records.length > 0);
  if (withRecords.length === 0) return null;
  return withRecords.reduce((longest, p) =>
    p.distanceMeters > longest.distanceMeters ? p : longest,
  ).benchmarkDistance;
}

/**
 * One point per record at its Activity's start, plus a last point at `nowMs` repeating the current
 * record, so the final step reaches today.
 */
export function progressionSteps(
  records: readonly PersonalRecordStepDto[],
  nowMs: number,
): StepPoints {
  const x = records.map((r) => Date.parse(r.activityStartedAt) / 1000);
  const y = records.map((r) => r.elapsedTimeSeconds);
  const lastX = x.at(-1);
  const lastY = y.at(-1);
  const now = nowMs / 1000;
  if (lastX !== undefined && lastY !== undefined && now > lastX) {
    x.push(now);
    y.push(lastY);
  }
  return { x, y };
}

/** Ticks closer together than this are days or weeks apart, so they are labelled by day. */
const DAY_TICK_SPACING_SECONDS = 28 * 24 * 60 * 60;

/**
 * Labels for the chart's time ticks (epoch seconds), formatted in `timeZone` (the viewer's when
 * omitted, which is the zone uPlot places its ticks in): `2 Nov` when ticks are under a month apart,
 * otherwise `Mar 2026`.
 */
export function progressionTickLabels(ticks: readonly number[], timeZone?: string): string[] {
  const first = ticks[0];
  const second = ticks[1];
  const byDay =
    first !== undefined && second !== undefined && second - first < DAY_TICK_SPACING_SECONDS;
  const format = new Intl.DateTimeFormat(
    'en-GB',
    byDay
      ? { day: 'numeric', month: 'short', timeZone }
      : { month: 'short', year: 'numeric', timeZone },
  );
  return ticks.map((tick) => format.format(new Date(tick * 1000)));
}
