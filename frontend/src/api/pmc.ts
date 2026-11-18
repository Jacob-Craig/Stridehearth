import { queryOptions } from '@tanstack/react-query';
import { validatedFetch } from './client';
import { dashboardQueryKey } from './dashboard';
import type { PmcResponseDto } from './generated/types.gen';
import { zGetPerformanceManagementChartResponse } from './generated/zod.gen';

/** The athlete's whole **Performance Management Chart**, from their first scored day to today. */
export async function getPmc(): Promise<PmcResponseDto> {
  return validatedFetch(zGetPerformanceManagementChartResponse, '/api/athlete/pmc');
}

/**
 * Under the athlete's Dashboard key, so a change to their Activities or Thresholds, which invalidates the
 * Dashboard, also refreshes their PMC, and one athlete's PMC is never served to the next.
 */
export const pmcQueryKey = (userId: number | undefined) =>
  [...dashboardQueryKey(userId), 'pmc'] as const;

export const pmcQueryOptions = (userId: number | undefined) =>
  queryOptions({
    queryKey: pmcQueryKey(userId),
    queryFn: getPmc,
  });
