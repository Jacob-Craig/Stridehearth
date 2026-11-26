import {
  type Query,
  type QueryClient,
  queryOptions,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query';
import { ApiError, apiClient, validatedFetch } from './client';
import { dashboardQueryKey } from './dashboard';
import type {
  ActivityCreateRequestDto,
  ActivityDetailDto,
  ActivitySummaryDto,
  ActivityTelemetryResponseDto,
  ActivityUpdateRequestDto,
  PagedResponseDtoActivitySummaryDto,
  SingleFileUploadResponseDto,
  WorkoutType,
} from './generated/types.gen';
import {
  zCreateActivityResponse,
  zGetActivityResponse,
  zGetActivityTelemetryResponse,
  zListActivitiesResponse,
  zUpdateActivityResponse,
  zUploadActivityFileResponse,
} from './generated/zod.gen';
import { heatmapKey } from './heatmap';

export const ACTIVITY_PAGE_SIZE = 10;

export async function listActivities(
  page: number,
  size = ACTIVITY_PAGE_SIZE,
  workoutType?: WorkoutType,
): Promise<PagedResponseDtoActivitySummaryDto> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (workoutType) query.set('workoutType', workoutType);
  return validatedFetch(zListActivitiesResponse, `/api/activities?${query}`);
}

export async function getActivity(activityId: number): Promise<ActivityDetailDto> {
  return validatedFetch(zGetActivityResponse, `/api/activities/${activityId}`);
}

/**
 * The **Activity Details** of an **Activity**, or `null` when it has none (e.g. a manual **Activity**):
 * the endpoint answers `404` for those just as for an **Activity** that doesn't exist, which
 * {@link getActivity} reports.
 */
export async function getActivityTelemetry(
  activityId: number,
): Promise<ActivityTelemetryResponseDto | null> {
  try {
    return await validatedFetch(
      zGetActivityTelemetryResponse,
      `/api/activities/${activityId}/telemetry`,
    );
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

export async function createActivity(body: ActivityCreateRequestDto): Promise<ActivitySummaryDto> {
  return validatedFetch(zCreateActivityResponse, '/api/activities', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export async function updateActivity(
  activityId: number,
  body: ActivityUpdateRequestDto,
): Promise<ActivitySummaryDto> {
  return validatedFetch(zUpdateActivityResponse, `/api/activities/${activityId}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export async function deleteActivity(activityId: number): Promise<void> {
  await apiClient<void>(`/api/activities/${activityId}`, { method: 'DELETE' });
}

export async function uploadActivityFile(file: File): Promise<SingleFileUploadResponseDto> {
  const body = new FormData();
  body.append('file', file);
  return validatedFetch(zUploadActivityFileResponse, '/api/activities/upload', {
    method: 'POST',
    body,
  });
}

/** Every **Activity** query is keyed by the athlete so cached data never leaks between athletes. */
export const activitiesKey = (userId: number | undefined) => ['activities', userId] as const;

export const activitiesPageQueryOptions = (
  userId: number | undefined,
  page: number,
  workoutType?: WorkoutType,
) =>
  queryOptions({
    queryKey: [...activitiesKey(userId), 'page', page, workoutType ?? null] as const,
    queryFn: () => listActivities(page, ACTIVITY_PAGE_SIZE, workoutType),
  });

/**
 * An **Activity** and its **Activity Details** only change when the athlete edits, deletes or re-imports
 * it, so preloaded copies stay fresh long enough to open instantly. Every change made through this
 * module refreshes them, as does an **Import Batch** seen reaching a terminal status. The exception:
 * an **Import Batch** that finishes after the athlete leaves its progress view, whose upgraded
 * **Activity Details** can be served stale for up to this long.
 */
const ACTIVITY_STALE_TIME_MS = 5 * 60 * 1000;
const ACTIVITY_DETAILS_GC_TIME_MS = 15 * 60 * 1000;

export const activityQueryOptions = (userId: number | undefined, activityId: number) =>
  queryOptions({
    queryKey: [...activitiesKey(userId), 'detail', activityId] as const,
    queryFn: () => getActivity(activityId),
    staleTime: ACTIVITY_STALE_TIME_MS,
  });

export const activityTelemetryQueryOptions = (userId: number | undefined, activityId: number) =>
  queryOptions({
    queryKey: [...activitiesKey(userId), 'telemetry', activityId] as const,
    queryFn: () => getActivityTelemetry(activityId),
    staleTime: ACTIVITY_STALE_TIME_MS,
    gcTime: ACTIVITY_DETAILS_GC_TIME_MS,
  });

/** Whether [query] is the detail or **Activity Details** of one of the [deleted] **Activities**. */
function isDeletedActivityQuery(query: Query, deleted: ReadonlySet<number>): boolean {
  const [, , kind, activityId] = query.queryKey;
  return (kind === 'detail' || kind === 'telemetry') && deleted.has(activityId as number);
}

/**
 * Refreshes everything derived from the athlete's **Activities** after a change, including the
 * **Dashboard**, whose **Personal Records** may have been recalibrated, and the **Heatmap**'s revision and
 * starting view. Queries of [deleted]
 * **Activities** are marked stale but not refetched, since they would only answer `404`.
 *
 * Internal to `src/api`: pages change **Activities** through the mutation hooks, which call this.
 */
export async function refreshActivityData(
  queryClient: QueryClient,
  userId: number | undefined,
  deleted: readonly number[] = [],
) {
  const deletedIds = new Set(deleted);
  await Promise.all([
    queryClient.invalidateQueries({
      queryKey: activitiesKey(userId),
      predicate: (query) => !isDeletedActivityQuery(query, deletedIds),
    }),
    queryClient.invalidateQueries({
      queryKey: activitiesKey(userId),
      predicate: (query) => isDeletedActivityQuery(query, deletedIds),
      refetchType: 'none',
    }),
    queryClient.invalidateQueries({ queryKey: dashboardQueryKey(userId) }),
    queryClient.invalidateQueries({ queryKey: heatmapKey(userId) }),
  ]);
}

interface ActivityMutationOptions<TData, TVariables> {
  /** The **Activities** a successful call deleted. */
  readonly deletedBy?: (variables: TVariables) => readonly number[];
  /**
   * Runs after the refresh, as part of the mutation: it stays pending until this settles, and a
   * rejection becomes its error.
   */
  readonly onSuccess?: ((data: TData) => unknown) | undefined;
}

/**
 * A mutation that changes the athlete's **Activities**: once it succeeds, everything derived from them
 * is refreshed before `mutateAsync` resolves or any `mutate` callback runs.
 *
 * Internal to `src/api`, which exports one hook per kind of **Activity** change.
 */
export function useActivityMutation<TData, TVariables = void>(
  userId: number | undefined,
  mutationFn: (variables: TVariables) => Promise<TData>,
  { deletedBy, onSuccess }: ActivityMutationOptions<TData, TVariables> = {},
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: async (data, variables) => {
      await refreshActivityData(queryClient, userId, deletedBy?.(variables));
      await onSuccess?.(data);
    },
  });
}

/** Logs an **Activity** by hand. */
export const useCreateActivity = (userId: number | undefined) =>
  useActivityMutation(userId, createActivity);

/** Edits one **Activity**; only its title and sport when it has **Protected Metrics**. */
export const useUpdateActivity = (userId: number | undefined, activityId: number) =>
  useActivityMutation(userId, (body: ActivityUpdateRequestDto) => updateActivity(activityId, body));

/** Permanently deletes one **Activity**, given its id. */
export const useDeleteActivity = (userId: number | undefined) =>
  useActivityMutation(userId, deleteActivity, {
    deletedBy: (activityId: number) => [activityId],
  });

/**
 * Imports one activity file, which may create or upgrade **Activities**; [onUploaded] runs as part of
 * the upload, once the refresh is done.
 */
export const useUploadActivityFile = (
  userId: number | undefined,
  onUploaded?: (result: SingleFileUploadResponseDto) => unknown,
) => useActivityMutation(userId, uploadActivityFile, { onSuccess: onUploaded });
