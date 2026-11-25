import { queryOptions } from '@tanstack/react-query';
import { validatedFetch } from './client';
import { dashboardQueryKey } from './dashboard';
import type { CriticalSpeedResponseDto, PeakEffortsResponseDto } from './generated/types.gen';
import { zGetCriticalSpeedResponse, zGetPeakEffortsResponse } from './generated/zod.gen';

/** The athlete's best running **Peak Efforts** per Benchmark Duration, over 90 days and all time. */
export async function getPeakEfforts(): Promise<PeakEffortsResponseDto> {
  return validatedFetch(zGetPeakEffortsResponse, '/api/athlete/peak-efforts');
}

/** The athlete's **Critical Speed** (and Critical Power) model as of today. */
export async function getCriticalSpeed(): Promise<CriticalSpeedResponseDto> {
  return validatedFetch(zGetCriticalSpeedResponse, '/api/athlete/critical-speed');
}

/**
 * Both under the athlete's Dashboard key, so a change to their Activities, which invalidates the Dashboard,
 * also refreshes their **Pace-Duration Curve**, and one athlete's is never served to the next.
 */
export const peakEffortsQueryOptions = (userId: number | undefined) =>
  queryOptions({
    queryKey: [...dashboardQueryKey(userId), 'peak-efforts'] as const,
    queryFn: getPeakEfforts,
  });

export const criticalSpeedQueryOptions = (userId: number | undefined) =>
  queryOptions({
    queryKey: [...dashboardQueryKey(userId), 'critical-speed'] as const,
    queryFn: getCriticalSpeed,
  });
