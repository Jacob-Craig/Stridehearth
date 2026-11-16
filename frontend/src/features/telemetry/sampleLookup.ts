/** A `[longitude, latitude]` position on the route. */
export type LngLat = readonly [lon: number, lat: number];

/**
 * Reads the route of the **Activity Details** as `[longitude, latitude]` positions (ignoring any
 * altitude), or `null` when it has fewer than two positions or a malformed one, so it is not drawn.
 */
export function parseRoute(coordinates: readonly (readonly number[])[]): LngLat[] | null {
  if (coordinates.length < 2) return null;
  const route: LngLat[] = [];
  for (const [lon, lat] of coordinates) {
    if (lon === undefined || lat === undefined) return null;
    route.push([lon, lat]);
  }
  return route;
}

/**
 * The full-resolution route of the **Activity Details**: `routeIndex[i]` is the index into
 * `coordinates` of the GPS fix for sample `i` (recorded at elapsed `t[i]`), or `null` without one.
 */
export interface RouteTimeline {
  readonly coordinates: readonly LngLat[];
  readonly t: readonly number[];
  readonly routeIndex: readonly (number | null)[] | null;
}

/** A span of elapsed time, such as a **Best Effort**'s `EffortWindow`. */
export interface TimeWindow {
  readonly startTimeSeconds: number;
  readonly endTimeSeconds: number;
}

/**
 * The index of the sample recorded nearest `seconds` (elapsed time is ascending), or `null` when
 * there are no samples. Charts may plot downsampled series, so cursors and **Best Effort** windows are
 * resolved back to full-resolution samples by time rather than by chart index.
 */
export function sampleIndexAtTime(t: readonly number[], seconds: number): number | null {
  if (t.length === 0) return null;
  let low = 0;
  let high = t.length - 1;
  while (low < high) {
    const mid = (low + high) >>> 1;
    if (t[mid]! < seconds) low = mid + 1;
    else high = mid;
  }
  // `low` is the first sample at or after `seconds`; the one before it may be nearer.
  if (low > 0 && seconds - t[low - 1]! <= t[low]! - seconds) return low - 1;
  return low;
}

const vertexAt = (timeline: RouteTimeline, sample: number): number | null =>
  timeline.routeIndex?.[sample] ?? null;

function vertexOf(timeline: RouteTimeline, sample: number): LngLat | null {
  const vertex = vertexAt(timeline, sample);
  return vertex == null ? null : (timeline.coordinates[vertex] ?? null);
}

/** The route position of the sample recorded nearest `seconds`, or `null` when it had no GPS fix. */
export function routeCoordinateAtTime(timeline: RouteTimeline, seconds: number): LngLat | null {
  const sample = sampleIndexAtTime(timeline.t, seconds);
  return sample == null ? null : vertexOf(timeline, sample);
}

/** The first sample from `from` (stepping by `step`) at a route vertex other than `vertex`. */
function neighbouringPosition(
  timeline: RouteTimeline,
  from: number,
  step: 1 | -1,
  vertex: number,
): LngLat | null {
  for (let sample = from; sample >= 0 && sample < timeline.t.length; sample += step) {
    const other = vertexAt(timeline, sample);
    if (other != null && other !== vertex) return vertexOf(timeline, sample);
  }
  return null;
}

/**
 * The distinct route positions recorded inside `window`, in order: the highlighted route path. A
 * GeoJSON LineString needs at least two positions, so a window that stays on one route vertex is
 * widened to the next vertex reached (or, at the end of the route, the previous one); a route with
 * nowhere to widen to has no path.
 */
export function effortRouteCoordinates(timeline: RouteTimeline, window: TimeWindow): LngLat[] {
  const first = sampleIndexAtTime(timeline.t, window.startTimeSeconds);
  const last = sampleIndexAtTime(timeline.t, window.endTimeSeconds);
  if (first == null || last == null) return [];
  const path: LngLat[] = [];
  let previousVertex: number | null = null;
  for (let sample = first; sample <= last; sample++) {
    const vertex = vertexAt(timeline, sample);
    if (vertex == null || vertex === previousVertex) continue;
    const position = timeline.coordinates[vertex];
    if (position) path.push(position);
    previousVertex = vertex;
  }
  if (path.length !== 1 || previousVertex == null) return path;
  const next = neighbouringPosition(timeline, last + 1, 1, previousVertex);
  if (next) return [...path, next];
  const previous = neighbouringPosition(timeline, first - 1, -1, previousVertex);
  return previous ? [previous, ...path] : [];
}
