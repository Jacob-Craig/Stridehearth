import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { match } from 'ts-pattern';
import { useColorScheme } from '../../lib/colorScheme';
import type { MapPerspective } from '../basemap/mapPerspective';
import { PerspectiveToggle } from '../basemap/PerspectiveToggle';
import { loadRouteMap } from './lazyRenderers';
import type { RouteMapHandle } from './maplibreRouteMap';
import { effortRouteCoordinates, type RouteTimeline, type TimeWindow } from './sampleLookup';
import type { TelemetryScrubberBus } from './scrubberBus';
import { type MountedRenderer, useLazyRenderer } from './useLazyRenderer';

type RouteMapModule = typeof import('./maplibreRouteMap');

interface ActivityRouteMapProps {
  /** The full-resolution route, with at least two positions. */
  readonly timeline: RouteTimeline;
  readonly bus: TelemetryScrubberBus;
  /** The selected **Best Effort**'s window, highlighted on the route; `null` when none is selected. */
  readonly highlight: TimeWindow | null;
}

/** The 2D/3D MapLibre route map of an **Activity**, carrying the scrubber marker. */
export function ActivityRouteMap({ timeline, bus, highlight }: ActivityRouteMapProps) {
  const theme = useColorScheme();
  // 2D by default: no pitch or building extrusion to render until asked for.
  const [perspective, setPerspective] = useState<MapPerspective>('2d');
  const container = useRef<HTMLDivElement>(null);
  // The current settings, read when the map is created; later changes are applied to the live map.
  const latestSettings = useRef({ theme, perspective });
  useEffect(() => {
    latestSettings.current = { theme, perspective };
  });

  const mount = useCallback(
    ({ createRouteMap }: RouteMapModule): MountedRenderer<RouteMapHandle> | null => {
      if (!container.current) return null;
      const handle = createRouteMap({
        container: container.current,
        coordinates: timeline.coordinates,
        ...latestSettings.current,
        onRouteVertexHover: (vertex) => bus.scrubRouteVertex(vertex),
      });
      const detachMarker = bus.registerMarker(handle.marker);
      return {
        handle,
        unmount() {
          detachMarker();
          handle.destroy();
        },
      };
    },
    [timeline, bus],
  );
  const map = useLazyRenderer(loadRouteMap, mount);
  const liveMap = map.kind === 'ready' ? map.handle : null;

  const path = useMemo(
    () => (highlight ? effortRouteCoordinates(timeline, highlight) : []),
    [timeline, highlight],
  );

  useEffect(() => {
    liveMap?.setTheme(theme);
  }, [liveMap, theme]);
  useEffect(() => {
    liveMap?.setPerspective(perspective);
  }, [liveMap, perspective]);
  useEffect(() => {
    liveMap?.setHighlight(path);
  }, [liveMap, path]);

  return (
    <div className="overflow-hidden rounded-lg ring-1 ring-slate-200 dark:ring-slate-800 bg-white dark:bg-slate-900 shadow-sm">
      <div className="flex h-11 items-center justify-between px-3">
        <h2 className="text-sm font-semibold">Route</h2>
        <PerspectiveToggle
          perspective={perspective}
          onChange={setPerspective}
          className="flex gap-1 text-xs font-medium"
          buttonClassName="rounded-md px-2.5 py-1 hover:bg-slate-100 dark:hover:bg-slate-800"
        />
      </div>
      <div className="relative h-[420px]">
        {/* Sized, not positioned: MapLibre's lazily loaded `.maplibregl-map { position: relative }`
            overrides any positioning class here, collapsing an `absolute inset-0` map to 0px. */}
        <div ref={container} className="h-full w-full" />
        {match(map)
          .with({ kind: 'failed' }, () => (
            <p className="absolute inset-0 flex items-center justify-center p-6 text-center text-sm text-slate-500 dark:text-slate-400">
              The route map could not be displayed in this browser.
            </p>
          ))
          .with({ kind: 'loading' }, { kind: 'ready' }, () => null)
          .exhaustive()}
      </div>
    </div>
  );
}
