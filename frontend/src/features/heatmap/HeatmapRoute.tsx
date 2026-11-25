import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { match } from 'ts-pattern';
import type { HeatmapStartingViewDto } from '../../api/generated/types.gen';
import { type HeatmapFilterParams, heatmapQueries, useHeatmapFreshness } from '../../api/heatmap';
import { isTilted, type MapPerspective } from '../basemap/mapPerspective';
import { PerspectiveToggle } from '../basemap/PerspectiveToggle';
import { type MountedRenderer, useLazyRenderer } from '../telemetry/useLazyRenderer';
import { HeatmapFilterControls } from './HeatmapFilterControls';
import {
  browserTimeZone,
  DEFAULT_HEATMAP_FILTER,
  type HeatmapFilter,
  hasNoSports,
  isDefaultFilter,
  isInvertedRange,
  toParams,
} from './heatmapFilter';
import {
  type HeatmapLayerHandle,
  type HeatmapLayerModule,
  type HeatmapTileKey,
  heatmapLayerLoaders,
} from './heatmapLayers';
import { readRememberedView, rememberView } from './rememberedView';
import { rememberThreeDNoteShown, THREE_D_NOTE, wasThreeDNoteShown } from './threeDNote';

const authenticatedApi = getRouteApi('/authenticated');

const unsupported = (): Promise<never> =>
  Promise.reject(new Error('No HeatmapLayer adapter for this renderer'));

/** The athlete's full-screen **Heatmap**: every Activity with a route, on one world map. */
export function HeatmapRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const userId = user.id;
  const queries = heatmapQueries(userId);
  const { data: renderer } = useSuspenseQuery(queries.renderer);
  const { data: revision } = useSuspenseQuery(queries.revision);
  const { data: startingView } = useSuspenseQuery(queries.startingView);
  const [filter, setFilter] = useState(DEFAULT_HEATMAP_FILTER);
  const filterParams = toParams(filter);
  const { data: years } = useQuery(queries.years(browserTimeZone()));
  const emptyState = useEmptyState(queries, startingView, filter, filterParams);
  const tileKey = useMemo<HeatmapTileKey>(
    () => ({ revision: revision.revision, filterParams }),
    [revision.revision, filterParams],
  );
  // 2D by default: the fastest Map Perspective, with no tilt or buildings to load until asked for.
  const [perspective, setPerspective] = useState<MapPerspective>('2d');
  const [showThreeDNote, setShowThreeDNote] = useState(false);
  const container = useRef<HTMLDivElement>(null);
  // Read when the map is created. Later tile keys (a new revision or filter) and perspectives are
  // applied to the live map, and a new starting view only updates the empty state, so none of them
  // recreates the map and loses the camera.
  const latest = useRef({ tileKey, perspective, startingBounds: startingView.bounds });
  useEffect(() => {
    latest.current = { tileKey, perspective, startingBounds: startingView.bounds };
  });

  const choosePerspective = (next: MapPerspective) => {
    setPerspective(next);
    if (!isTilted(next)) {
      setShowThreeDNote(false);
    } else if (next !== perspective && !wasThreeDNoteShown()) {
      // Remembered once shown; if storage is blocked, only for the rest of this visit.
      rememberThreeDNoteShown();
      setShowThreeDNote(true);
    }
  };

  const mount = useCallback(
    ({ createHeatmapLayer }: HeatmapLayerModule): MountedRenderer<HeatmapLayerHandle> | null => {
      if (!container.current) return null;
      const handle = createHeatmapLayer({
        container: container.current,
        renderer,
        tileKey: latest.current.tileKey,
        perspective: latest.current.perspective,
        startingBounds: latest.current.startingBounds,
        // With no routes to show, the world view and the empty state beat any remembered area.
        view: latest.current.startingBounds ? readRememberedView(userId) : null,
        // Only an area with routes is worth reopening: a remembered world view would otherwise beat the
        // starting view on the first visit after the athlete's first import.
        onViewChange: (view) => {
          if (latest.current.startingBounds) rememberView(userId, view);
        },
      });
      return { handle, unmount: () => handle.destroy() };
    },
    [renderer, userId],
  );
  const layer = useLazyRenderer(heatmapLayerLoaders[renderer.id] ?? unsupported, mount);
  const liveLayer = layer.kind === 'ready' ? layer.handle : null;

  useEffect(() => {
    liveLayer?.setTileKey(tileKey);
  }, [liveLayer, tileKey]);
  useEffect(() => {
    liveLayer?.setPerspective(perspective);
  }, [liveLayer, perspective]);

  useHeatmapFreshness(userId, revision.revision);

  return (
    // Full screen below the 4rem app header, outside the page's padded column.
    <section aria-label="Heatmap" className="fixed inset-x-0 top-16 bottom-0 bg-slate-950">
      {/* Sized, not positioned: MapLibre's `.maplibregl-map { position: relative }` would override it. */}
      <div ref={container} className="h-full w-full" />
      {/* One column on the right, clear of the zoom buttons on a narrow screen: the filter, then the Map Perspective toggle. */}
      <div className="absolute top-3 right-3 left-14 flex flex-col items-end gap-2 sm:left-auto">
        <HeatmapFilterControls filter={filter} years={years?.years} onChange={setFilter} />
        <PerspectiveToggle
          perspective={perspective}
          onChange={choosePerspective}
          className="flex gap-1 rounded-md bg-slate-900/90 p-1 text-xs font-medium text-slate-200 shadow"
          buttonClassName="rounded px-2.5 py-1 hover:bg-slate-800"
        />
        {showThreeDNote && (
          <output className="flex items-center gap-2 rounded-md bg-slate-900/90 py-1.5 pr-1.5 pl-3 text-xs text-slate-200 shadow">
            {THREE_D_NOTE}
            <button
              type="button"
              aria-label="Dismiss the 3D note"
              onClick={() => setShowThreeDNote(false)}
              className="rounded px-1.5 text-slate-400 hover:bg-slate-800 hover:text-slate-100"
            >
              ×
            </button>
          </output>
        )}
      </div>
      {match(emptyState)
        .with({ kind: 'noRoutes' }, () => (
          <EmptyState>No Activities with a route yet. Import some to see your Heatmap.</EmptyState>
        ))
        .with({ kind: 'invertedRange' }, () => (
          <EmptyState>The From date is after the To date.</EmptyState>
        ))
        .with({ kind: 'noMatch' }, () => (
          <EmptyState>No Activities with routes match this filter.</EmptyState>
        ))
        .with({ kind: 'none' }, () => null)
        .exhaustive()}
      {match(layer)
        .with({ kind: 'failed' }, () => (
          <p className="absolute inset-0 flex items-center justify-center p-6 text-center text-sm text-slate-300">
            The Heatmap cannot be displayed in this browser.
          </p>
        ))
        .with({ kind: 'loading' }, { kind: 'ready' }, () => null)
        .exhaustive()}
    </section>
  );
}

/** Why the map shows nothing, if it does. */
type EmptyStateKind =
  | { readonly kind: 'noRoutes' }
  | { readonly kind: 'invertedRange' }
  | { readonly kind: 'noMatch' }
  | { readonly kind: 'none' };

/**
 * Why the map under [filter] is empty: the athlete has no routes at all, the date range is back to front, or
 * the filter matches none of their routes. An empty sport set or a back-to-front range never matches, so
 * neither needs a request; otherwise the filter matches nothing when its starting view has no bounds.
 */
function useEmptyState(
  queries: ReturnType<typeof heatmapQueries>,
  startingView: HeatmapStartingViewDto,
  filter: HeatmapFilter,
  filterParams: HeatmapFilterParams,
): EmptyStateKind {
  const asksServer = !isDefaultFilter(filter) && !hasNoSports(filter) && !isInvertedRange(filter);
  const { data } = useQuery({
    ...queries.filteredStartingView(filterParams),
    enabled: asksServer,
    // While the next filter loads, keep the answer for the filter just left, so the message doesn't flicker
    // between filters. Only that filter's own answer: never one carried through a filter that asked nothing.
    placeholderData: (_previous, previousQuery) => previousQuery?.state.data,
  });
  if (startingView.bounds === null) return { kind: 'noRoutes' };
  if (isInvertedRange(filter)) return { kind: 'invertedRange' };
  if (hasNoSports(filter) || (asksServer && data?.bounds === null)) return { kind: 'noMatch' };
  return { kind: 'none' };
}

function EmptyState({ children }: { readonly children: string }) {
  return (
    <p className="pointer-events-none absolute inset-x-0 bottom-8 mx-auto w-fit max-w-[calc(100%-2rem)] rounded-md bg-slate-900/90 px-4 py-2 text-center text-sm text-slate-200 shadow">
      {children}
    </p>
  );
}
