import { queryOptions } from '@tanstack/react-query';
import type { ActivitySource, ActivitySport } from '../lib/format';
import { activitiesKey, useActivityMutation } from './activities';
import { validatedFetch } from './client';
import type {
  BatchMutationResultDto,
  BestEffortBackfillResultDto,
  PagedResponseDtoActivitySummaryDto,
} from './generated/types.gen';
import {
  zBackfillBestEffortsResponse,
  zBatchDeleteResponse,
  zBatchUpdateResponse,
  zListActivitiesResponse,
} from './generated/zod.gen';

/** Page sizes of a filtered listing; `all` returns every matching **Activity** in one page. */
export type ActivityListSize = number | 'all';

/** Criteria for one filtered **Activity** listing request. */
export interface ActivityListQuery {
  /** Case-insensitive substring of the title (sent as the `q` query parameter). */
  readonly titleContains?: string;
  readonly sport?: ActivitySport;
  readonly source?: ActivitySource;
  readonly size: ActivityListSize;
  readonly page: number;
}

/** Lists the athlete's **Activities** matching [filters]; `size: 'all'` returns every match in one page. */
export async function listFilteredActivities(
  filters: ActivityListQuery,
): Promise<PagedResponseDtoActivitySummaryDto> {
  const query = new URLSearchParams();
  if (filters.titleContains) query.set('q', filters.titleContains);
  if (filters.sport) query.set('sport', filters.sport);
  if (filters.source) query.set('source', filters.source);
  query.set('size', String(filters.size));
  query.set('page', String(filters.page));
  return validatedFetch(zListActivitiesResponse, `/api/activities?${query}`);
}

export interface BatchUpdate {
  readonly activityIds: readonly number[];
  /** Blank keeps each Activity's title. */
  readonly title: string;
  /** Null keeps each Activity's sport. */
  readonly sport: ActivitySport | null;
}

/** Applies a new title and/or sport to several **Activities**, recorded as `BATCH_EDITED` history. */
export async function batchUpdateActivities(update: BatchUpdate): Promise<BatchMutationResultDto> {
  const title = update.title.trim();
  return validatedFetch(zBatchUpdateResponse, '/api/activities/batch', {
    method: 'PATCH',
    body: JSON.stringify({
      activityIds: update.activityIds,
      ...(title ? { title } : {}),
      ...(update.sport ? { sport: update.sport } : {}),
    }),
  });
}

/** Permanently deletes several **Activities**; the server recalibrates **Personal Records**. */
export async function batchDeleteActivities(
  activityIds: readonly number[],
): Promise<BatchMutationResultDto> {
  return validatedFetch(zBatchDeleteResponse, '/api/activities/batch-delete', {
    method: 'POST',
    body: JSON.stringify({ activityIds }),
  });
}

/** Detects **Best Efforts** for the athlete's historical runs that have none yet. */
export async function backfillBestEfforts(): Promise<BestEffortBackfillResultDto> {
  return validatedFetch(zBackfillBestEffortsResponse, '/api/activities/backfill-best-efforts', {
    method: 'POST',
  });
}

/** Applies a new title and/or sport to several **Activities** at once. */
export const useBatchUpdateActivities = (userId: number | undefined) =>
  useActivityMutation(userId, batchUpdateActivities);

/** Permanently deletes several **Activities**, given their ids. */
export const useBatchDeleteActivities = (userId: number | undefined) =>
  useActivityMutation(userId, batchDeleteActivities, {
    deletedBy: (activityIds: readonly number[]) => activityIds,
  });

/** Detects missing **Best Efforts**, which may recalibrate **Personal Records**. */
export const useBackfillBestEfforts = (userId: number | undefined) =>
  useActivityMutation(userId, backfillBestEfforts);

export const filteredActivitiesQueryOptions = (
  userId: number | undefined,
  filters: ActivityListQuery,
) =>
  queryOptions({
    queryKey: [...activitiesKey(userId), 'filtered', filters] as const,
    queryFn: () => listFilteredActivities(filters),
  });
