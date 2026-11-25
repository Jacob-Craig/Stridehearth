import type { XySeries } from './lttb';
import { sampleIndexAtTime, type TimeWindow } from './sampleLookup';

/**
 * Silences up to this long are drawn as a continuous line. Watches using "smart recording" log some
 * sensors (often heart rate) only every few seconds, between samples that have no reading.
 */
const BRIDGED_SILENCE_SECONDS = 30;

/**
 * A downsampled series has no points inside a dropout, and its points are spread several seconds
 * apart, so a silence only counts as a dropout when it is well beyond that spacing too.
 */
const SPACING_MULTIPLE = 2.5;

/**
 * The sensor dropouts of a charted series: the silences between consecutive readings that are too
 * long to bridge with a line. Works at full resolution (nulls) and downsampled (missing points).
 */
export function sensorDropouts({ x, y }: XySeries): TimeWindow[] {
  const recorded: number[] = [];
  for (let i = 0; i < x.length; i++) {
    const seconds = x[i];
    if (y[i] != null && seconds !== undefined) recorded.push(seconds);
  }
  const first = recorded[0];
  const last = recorded.at(-1);
  if (first === undefined || last === undefined || recorded.length < 2) return [];

  const meanSpacing = (last - first) / (recorded.length - 1);
  const longestBridged = Math.max(BRIDGED_SILENCE_SECONDS, SPACING_MULTIPLE * meanSpacing);

  const dropouts: TimeWindow[] = [];
  let previous = first;
  for (const seconds of recorded.slice(1)) {
    if (seconds - previous > longestBridged) {
      dropouts.push({ startTimeSeconds: previous, endTimeSeconds: seconds });
    }
    previous = seconds;
  }
  return dropouts;
}

/**
 * The reading shown at `seconds`: the nearest recorded sample, as the line bridges sparse readings,
 * or `null` inside a sensor dropout or when nothing was recorded.
 */
export function readingAt(
  { x, y }: XySeries,
  seconds: number,
  dropouts: readonly TimeWindow[],
): number | null {
  const inDropout = dropouts.some(
    ({ startTimeSeconds, endTimeSeconds }) =>
      seconds > startTimeSeconds && seconds < endTimeSeconds,
  );
  const start = inDropout ? null : sampleIndexAtTime(x, seconds);
  if (start == null) return null;

  let best: { distance: number; value: number } | null = null;
  for (const step of [-1, 1]) {
    for (let i = start; i >= 0 && i < x.length; i += step) {
      const value = y[i];
      if (value == null) continue;
      const distance = Math.abs(x[i]! - seconds);
      if (best == null || distance < best.distance) best = { distance, value };
      break;
    }
  }
  return best?.value ?? null;
}
