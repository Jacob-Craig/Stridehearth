import { useEffect, useMemo } from 'react';
import type { RouteTimeline } from './sampleLookup';
import { type TelemetryScrubberBus, createTelemetryScrubber } from './scrubberBus';

/**
 * The scrubber bus shared by the route map and every sensor chart of one **Activity Details**.
 * Scrubbing flows through it imperatively, once per animation frame, and never through React state.
 *
 * @param timeline the full-resolution route, or `null` when the **Activity Details** have no route.
 */
export function useTelemetryScrubber(timeline: RouteTimeline | null): TelemetryScrubberBus {
  const bus = useMemo(() => createTelemetryScrubber({ timeline }), [timeline]);
  useEffect(() => () => bus.dispose(), [bus]);
  return bus;
}
