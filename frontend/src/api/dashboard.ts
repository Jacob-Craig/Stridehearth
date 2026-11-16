import { queryOptions } from '@tanstack/react-query';
import { validatedFetch } from './client';
import type { DashboardResponseDto } from './generated/types.gen';
import { zGetDashboardResponse } from './generated/zod.gen';

export async function getDashboard(): Promise<DashboardResponseDto> {
  return validatedFetch(zGetDashboardResponse, '/api/dashboard');
}

/**
 * Keyed by the athlete so a cached **Dashboard** is never served to a different athlete
 * who signs in on the same client.
 */
export const dashboardQueryKey = (userId: number | undefined) => ['dashboard', userId] as const;

export const dashboardQueryOptions = (userId: number | undefined) =>
  queryOptions({
    queryKey: dashboardQueryKey(userId),
    queryFn: getDashboard,
  });
