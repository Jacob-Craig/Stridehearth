import { type ReactNode, useMemo } from 'react';
import type {
  ActivityBestEffortDto,
  ActivityTelemetryResponseDto,
} from '../../api/generated/types.gen';
import { ActivityRouteMap } from '../telemetry/ActivityRouteMap';
import { parseRoute, type RouteTimeline } from '../telemetry/sampleLookup';
import { selectChartSeries } from '../telemetry/sensorStreams';
import { TelemetryChartGrid } from '../telemetry/TelemetryChartGrid';
import { useTelemetryScrubber } from '../telemetry/useTelemetryScrubber';

export interface ActivityVisualizationProps {
  /** The **Activity Details** preloaded by the route loader. */
  readonly telemetry: ActivityTelemetryResponseDto;
  /**
   * The **Best Effort** selected in the Best Efforts table, whose `window` is highlighted on the
   * route and every chart; `null` when none is selected.
   */
  readonly selectedEffort: ActivityBestEffortDto | null;
}

/** Stands in for a panel the **Activity Details** have nothing to show in. */
function EmptyPanel({ children }: { readonly children: ReactNode }) {
  return <p className="text-sm text-slate-500 dark:text-slate-400">{children}</p>;
}

/**
 * The route map and synchronized sensor charts of an **Activity**, linked by one scrubber bus.
 * Map and chart panels have fixed heights, so nothing shifts as they render.
 */
export function ActivityVisualization({ telemetry, selectedEffort }: ActivityVisualizationProps) {
  const { route, telemetry: streams, derivedStreams } = telemetry;
  const timeline = useMemo<RouteTimeline | null>(() => {
    const coordinates = route && parseRoute(route.coordinates);
    return coordinates ? { coordinates, t: streams.t, routeIndex: streams.routeIndex } : null;
  }, [route, streams]);
  const series = useMemo(
    () => selectChartSeries(streams, derivedStreams),
    [streams, derivedStreams],
  );
  const bus = useTelemetryScrubber(timeline);
  const highlight = selectedEffort?.window ?? null;

  return (
    <section aria-label="Route map and sensor charts" className="space-y-3">
      {timeline ? (
        <ActivityRouteMap timeline={timeline} bus={bus} highlight={highlight} />
      ) : (
        <EmptyPanel>No route was recorded in this Activity's Activity Details.</EmptyPanel>
      )}
      {series.length > 0 ? (
        <TelemetryChartGrid t={streams.t} series={series} bus={bus} highlight={highlight} />
      ) : (
        <EmptyPanel>
          No sensor readings were recorded in this Activity's Activity Details.
        </EmptyPanel>
      )}
    </section>
  );
}
