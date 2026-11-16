/**
 * Activities longer than this (~4 hours at 1Hz) have their chart series downsampled in a Web Worker;
 * shorter ones are charted at full resolution.
 */
export const LTTB_SAMPLE_THRESHOLD = 15_000;

/** Points kept per downsampled series: about two per device pixel of a wide chart. */
export const LTTB_TARGET_POINTS = 2_000;

export const needsDownsampling = (sampleCount: number): boolean =>
  sampleCount > LTTB_SAMPLE_THRESHOLD;

export interface XySeries {
  readonly x: readonly number[];
  readonly y: readonly (number | null)[];
}

/** A contiguous run of recorded (non-null) samples, `[first, end)` indices into the series. */
interface RecordedRun {
  readonly first: number;
  readonly end: number;
}

function recordedRuns(y: readonly (number | null)[]): RecordedRun[] {
  const runs: RecordedRun[] = [];
  let first: number | null = null;
  for (let i = 0; i <= y.length; i++) {
    const recorded = i < y.length && y[i] != null;
    if (recorded && first == null) first = i;
    if (!recorded && first != null) {
      runs.push({ first, end: i });
      first = null;
    }
  }
  return runs;
}

/**
 * Largest-Triangle-Three-Buckets downsampling of `y` against `x` to about `threshold` original points.
 *
 * Sensor dropouts stay visible: each contiguous run of recorded values is downsampled on its own (with
 * a share of the points proportional to its length, keeping its first and last sample), and every
 * unrecorded stretch is kept as a single `null` at the time it starts, so the chart breaks the line
 * there instead of bridging it. Series already within `threshold` are returned unchanged.
 */
export function lttb(
  x: readonly number[],
  y: readonly (number | null)[],
  threshold: number,
): XySeries {
  if (x.length <= threshold) return { x, y };
  const runs = recordedRuns(y);
  if (runs.length === 0) return { x: [], y: [] };

  const recordedCount = runs.reduce((sum, run) => sum + run.end - run.first, 0);
  const leadingGap = runs[0]!.first > 0 ? 1 : 0;
  const trailingGap = runs.at(-1)!.end < y.length ? 1 : 0;
  const gapCount = runs.length - 1 + leadingGap + trailingGap;
  const pointsForRuns = Math.max(threshold - gapCount, 0);

  const outX: number[] = [];
  const outY: (number | null)[] = [];
  const markGapAt = (index: number) => {
    outX.push(x[index]!);
    outY.push(null);
  };
  let previousEnd = 0;
  for (const run of runs) {
    if (run.first > previousEnd) markGapAt(previousEnd);
    const share = Math.max(2, Math.floor(((run.end - run.first) * pointsForRuns) / recordedCount));
    for (const index of lttbIndices(x, y, run, share)) {
      outX.push(x[index]!);
      outY.push(y[index]!);
    }
    previousEnd = run.end;
  }
  if (previousEnd < y.length) markGapAt(previousEnd);
  return { x: outX, y: outY };
}

/** LTTB over one recorded run: the indices of at most `threshold` of its samples, in order. */
function lttbIndices(
  x: readonly number[],
  y: readonly (number | null)[],
  { first, end }: RecordedRun,
  threshold: number,
): number[] {
  const n = end - first;
  if (threshold >= n) return Array.from({ length: n }, (_, k) => first + k);
  if (threshold < 3) return n === 1 ? [first] : [first, end - 1];

  const xAt = (k: number) => x[first + k]!;
  const yAt = (k: number) => y[first + k]!;
  const kept: number[] = [first];
  const bucketSize = (n - 2) / (threshold - 2);
  let selected = 0;

  for (let bucket = 0; bucket < threshold - 2; bucket++) {
    const start = Math.floor(bucket * bucketSize) + 1;
    const stop = Math.floor((bucket + 1) * bucketSize) + 1;

    // The average of the next bucket is the triangle's third vertex.
    const nextStart = stop;
    const nextEnd = Math.min(Math.floor((bucket + 2) * bucketSize) + 1, n);
    let avgX = 0;
    let avgY = 0;
    for (let k = nextStart; k < nextEnd; k++) {
      avgX += xAt(k);
      avgY += yAt(k);
    }
    const nextCount = nextEnd - nextStart;
    avgX /= nextCount;
    avgY /= nextCount;

    const ax = xAt(selected);
    const ay = yAt(selected);
    let maxArea = -1;
    let chosen = start;
    for (let k = start; k < stop; k++) {
      const area = Math.abs((ax - avgX) * (yAt(k) - ay) - (ax - xAt(k)) * (avgY - ay));
      if (area > maxArea) {
        maxArea = area;
        chosen = k;
      }
    }
    kept.push(first + chosen);
    selected = chosen;
  }

  kept.push(end - 1);
  return kept;
}
