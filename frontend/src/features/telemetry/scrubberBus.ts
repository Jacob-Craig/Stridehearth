import { type FrameClock, animationFrameClock, coalescePerFrame } from './frameCoalescer';
import { type LngLat, type RouteTimeline, routeCoordinateAtTime } from './sampleLookup';

/** A chart whose crosshair the bus moves imperatively, without re-rendering React. */
export interface ScrubbableChart {
  /** Moves the cursor to elapsed `seconds` without echoing it back to the bus; `null` hides it. */
  setCursorTime(seconds: number | null): void;
}

/** The route map's scrubber marker. */
export interface ScrubberMarker {
  moveTo(position: LngLat): void;
  hide(): void;
}

export interface TelemetryScrubberBus {
  /** Subscribes a chart to scrubbing; returns its unsubscribe. */
  registerChart(chart: ScrubbableChart): () => void;
  /** Attaches the route marker; returns its detach. */
  registerMarker(marker: ScrubberMarker): () => void;
  /**
   * Scrubs every chart except `source` and the route marker to elapsed `seconds` (`null` when the
   * pointer leaves), on the next animation frame. Moves within one frame coalesce to the latest.
   */
  scrub(seconds: number | null, source?: ScrubbableChart): void;
  /** Scrubs every chart to the first sample recorded at a hovered route vertex. */
  scrubRouteVertex(vertex: number | null): void;
  /** Cancels any pending frame. */
  dispose(): void;
}

export interface TelemetryScrubberOptions {
  /** The full-resolution route, or `null` when the **Activity Details** have no route. */
  readonly timeline: RouteTimeline | null;
  readonly clock?: FrameClock;
}

/**
 * The high-frequency scrubber bus linking the sensor charts and the route marker. It works in elapsed
 * time, not chart indices, so charts of downsampled series stay exactly aligned with the full-resolution
 * route.
 */
export function createTelemetryScrubber({
  timeline,
  clock = animationFrameClock,
}: TelemetryScrubberOptions): TelemetryScrubberBus {
  const charts = new Set<ScrubbableChart>();
  let marker: ScrubberMarker | null = null;

  // Inverse of routeIndex: route vertex -> first sample recorded there. Built on the first route hover.
  let firstSampleAtVertex: Map<number, number> | null = null;
  const sampleAtVertex = (vertex: number): number | undefined => {
    if (!firstSampleAtVertex) {
      firstSampleAtVertex = new Map();
      timeline?.routeIndex?.forEach((sampleVertex, sample) => {
        if (sampleVertex != null && !firstSampleAtVertex!.has(sampleVertex)) {
          firstSampleAtVertex!.set(sampleVertex, sample);
        }
      });
    }
    return firstSampleAtVertex.get(vertex);
  };

  const scrubs = coalescePerFrame<{
    readonly seconds: number | null;
    readonly source: ScrubbableChart | undefined;
  }>(({ seconds, source }) => {
    for (const chart of charts) {
      if (chart !== source) chart.setCursorTime(seconds);
    }
    if (!marker) return;
    const position = seconds == null || !timeline ? null : routeCoordinateAtTime(timeline, seconds);
    if (position) marker.moveTo(position);
    else marker.hide();
  }, clock);

  const scrub = (seconds: number | null, source?: ScrubbableChart) => {
    scrubs.push({ seconds, source });
  };

  return {
    registerChart(chart) {
      charts.add(chart);
      return () => {
        charts.delete(chart);
      };
    },
    registerMarker(target) {
      marker = target;
      return () => {
        if (marker === target) marker = null;
      };
    },
    scrub,
    scrubRouteVertex(vertex) {
      const sample = vertex == null ? undefined : sampleAtVertex(vertex);
      const seconds = sample == null ? undefined : timeline?.t[sample];
      scrub(seconds ?? null);
    },
    dispose() {
      scrubs.cancel();
    },
  };
}
