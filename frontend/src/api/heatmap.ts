import { queryOptions, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { validatedFetch } from './client';
import type {
  HeatmapRendererDto,
  HeatmapRevisionDto,
  HeatmapStartingViewDto,
  HeatmapYearsDto,
} from './generated/types.gen';
import {
  zGetHeatmapRendererResponse,
  zGetHeatmapRevisionResponse,
  zGetHeatmapStartingViewResponse,
  zGetHeatmapYearsResponse,
} from './generated/zod.gen';

/**
 * Every query of the athlete's **Heatmap** that follows their **Activities**, so a change to them can
 * refresh it. Keyed by the athlete, so a cached Heatmap is never served to a different athlete who signs
 * in on the same client.
 */
export const heatmapKey = (userId: number | undefined) => ['heatmap', userId] as const;

/**
 * A **Heatmap Filter** as Heatmap API query parameters, without a leading `?`. Branded so only the filter's
 * own encoding produces one; empty for the default filter.
 */
export type HeatmapFilterParams = string & { readonly __brand: 'HeatmapFilterParams' };

/** The parameters of the default filter (all time, all sports): none at all. */
export const NO_FILTER_PARAMS = '' as HeatmapFilterParams;

/** [path] with [filterParams] as its query string, if there are any. */
export const withFilterParams = (path: string, filterParams: HeatmapFilterParams): string =>
  filterParams ? `${path}?${filterParams}` : path;

const startingViewQuery = (userId: number | undefined, filterParams: HeatmapFilterParams) =>
  queryOptions({
    queryKey: [...heatmapKey(userId), 'starting-view', filterParams] as const,
    queryFn: () =>
      validatedFetch<HeatmapStartingViewDto>(
        zGetHeatmapStartingViewResponse,
        withFilterParams('/api/heatmap/starting-view', filterParams),
      ),
  });

export const heatmapQueries = (userId: number | undefined) => ({
  renderer: queryOptions({
    queryKey: ['heatmap', 'renderer'] as const,
    queryFn: () =>
      validatedFetch<HeatmapRendererDto>(zGetHeatmapRendererResponse, '/api/heatmap/renderer'),
    staleTime: Number.POSITIVE_INFINITY,
  }),
  revision: queryOptions({
    queryKey: [...heatmapKey(userId), 'revision'] as const,
    queryFn: () =>
      validatedFetch<HeatmapRevisionDto>(zGetHeatmapRevisionResponse, '/api/heatmap/revision'),
  }),
  /** The starting view of the full Heatmap, which the map opens on. */
  startingView: startingViewQuery(userId, NO_FILTER_PARAMS),
  /** The starting view under a Heatmap Filter; its bounds are null when nothing matches. */
  filteredStartingView: (filterParams: HeatmapFilterParams) =>
    startingViewQuery(userId, filterParams),
  /** The years with an Activity on the Heatmap, counted in [timeZone], for the year shortcuts. */
  years: (timeZone: string) =>
    queryOptions({
      queryKey: [...heatmapKey(userId), 'years', timeZone] as const,
      queryFn: () =>
        validatedFetch<HeatmapYearsDto>(
          zGetHeatmapYearsResponse,
          `/api/heatmap/years?${new URLSearchParams({ timeZone })}`,
        ),
    }),
});

/**
 * Keeps an open **Heatmap** current. An Import Batch in another tab, or a delete, changes the athlete's
 * revision while the page is in the background, so regaining window focus checks it. A new revision
 * also refreshes the starting view, as whether the routes match the Heatmap Filter may have changed.
 * The live map follows `revision` to new tiles; nothing here moves its camera.
 *
 * A window `focus` listener rather than `refetchOnWindowFocus`: TanStack Query watches only
 * `visibilitychange`, which misses switching back from another window while this tab stayed visible.
 */
export function useHeatmapFreshness(userId: number | undefined, revision: string): void {
  const queryClient = useQueryClient();

  useEffect(() => {
    const checkRevision = () =>
      void queryClient.refetchQueries({ queryKey: heatmapQueries(userId).revision.queryKey });
    window.addEventListener('focus', checkRevision);
    return () => window.removeEventListener('focus', checkRevision);
  }, [queryClient, userId]);

  const revisionSeen = useRef(revision);
  useEffect(() => {
    if (revisionSeen.current === revision) return;
    revisionSeen.current = revision;
    void queryClient.invalidateQueries({ queryKey: heatmapQueries(userId).startingView.queryKey });
  }, [queryClient, revision, userId]);
}
