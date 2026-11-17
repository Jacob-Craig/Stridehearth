/**
 * Pure helpers for the **Personal Record Progression** chart: which Benchmark Distance it opens on,
 * and the step points it plots.
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
