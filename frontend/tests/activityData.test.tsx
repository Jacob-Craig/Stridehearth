import { QueryClient, QueryClientProvider, type QueryKey } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  activitiesPageQueryOptions,
  activityQueryOptions,
  activityTelemetryQueryOptions,
  useCreateActivity,
  useDeleteActivity,
  useUpdateActivity,
  useUploadActivityFile,
} from '../src/api/activities';
import {
  useBackfillBestEfforts,
  useBatchDeleteActivities,
  useBatchUpdateActivities,
} from '../src/api/activityManagement';
import { dashboardQueryOptions } from '../src/api/dashboard';
import type { ActivitySummaryDto, ImportBatchSummaryDto } from '../src/api/generated/types.gen';
import { heatmapQueries } from '../src/api/heatmap';
import { useImportBatch } from '../src/api/importBatches';

const userId = 1;
const otherActivityId = 42;

const summary: ActivitySummaryDto = {
  id: 9,
  title: 'Morning Run',
  sport: 'RUN',
  startedAt: '2026-03-01T07:00:00Z',
  distance: 5,
  timeTakenSeconds: 1500,
  averagePaceKmh: 12,
  averageHeartRate: null,
  calories: null,
  source: 'MANUAL',
  hasProtectedMetrics: false,
  workoutType: null,
};

function importBatch(status: ImportBatchSummaryDto['status']): ImportBatchSummaryDto {
  return {
    id: 7,
    status,
    totalFiles: 2,
    processedFiles: 2,
    successfulFiles: 1,
    duplicateFiles: 0,
    upgradedFiles: 1,
    failedFiles: 0,
    errorMessage: null,
    itemErrors: [],
    itemUpgrades: [],
    createdAt: '2026-03-01T10:00:00Z',
    completedAt: '2026-03-01T10:01:00Z',
  };
}

/** Answers every request with [body]; 204 when it is `undefined`. */
function stubFetch(body: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      body === undefined
        ? new Response(null, { status: 204 })
        : new Response(JSON.stringify(body), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
    ),
  );
}

/** A client already holding every query derived from the athlete's **Activities**. */
function seededClient() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const derived: Record<string, QueryKey> = {
    'activity list': activitiesPageQueryOptions(userId, 0).queryKey,
    'Activity detail': activityQueryOptions(userId, otherActivityId).queryKey,
    'Activity Details': activityTelemetryQueryOptions(userId, otherActivityId).queryKey,
    Dashboard: dashboardQueryOptions(userId).queryKey,
    'Heatmap revision': heatmapQueries(userId).revision.queryKey,
    'Heatmap starting view': heatmapQueries(userId).startingView.queryKey,
  };
  for (const key of Object.values(derived)) queryClient.setQueryData(key, {});
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const staleQueries = () =>
    Object.entries(derived)
      .filter(([, key]) => queryClient.getQueryState(key)?.isInvalidated)
      .map(([name]) => name);
  return { queryClient, wrapper, staleQueries, names: Object.keys(derived) };
}

interface ActivityChange {
  readonly change: string;
  readonly response: unknown;
  /** Performs the change through the data module and resolves once it has finished. */
  readonly perform: (wrapper: (props: { children: ReactNode }) => ReactNode) => Promise<void>;
}

const changes: readonly ActivityChange[] = [
  {
    change: 'logging an Activity by hand',
    response: summary,
    perform: async (wrapper) => {
      const { result } = renderHook(() => useCreateActivity(userId), { wrapper });
      await result.current.mutateAsync({
        title: 'Morning Run',
        sport: 'RUN',
        startedAt: '2026-03-01T07:00:00Z',
        distance: 5,
        timeTakenSeconds: 1500,
      });
    },
  },
  {
    change: 'editing an Activity',
    response: summary,
    perform: async (wrapper) => {
      const { result } = renderHook(() => useUpdateActivity(userId, summary.id), { wrapper });
      await result.current.mutateAsync({ title: 'Renamed', sport: 'RUN' });
    },
  },
  {
    change: 'deleting an Activity',
    response: undefined,
    perform: async (wrapper) => {
      const { result } = renderHook(() => useDeleteActivity(userId), { wrapper });
      await result.current.mutateAsync(otherActivityId);
    },
  },
  {
    change: 'uploading an activity file',
    response: { kind: 'UPGRADED', activityId: otherActivityId },
    perform: async (wrapper) => {
      const { result } = renderHook(() => useUploadActivityFile(userId), { wrapper });
      await result.current.mutateAsync(new File(['fit'], 'run.fit'));
    },
  },
  {
    change: 'batch editing Activities',
    response: { affectedCount: 2, matchedCount: 2 },
    perform: async (wrapper) => {
      const { result } = renderHook(() => useBatchUpdateActivities(userId), { wrapper });
      await result.current.mutateAsync({ activityIds: [9, 10], title: 'Tempo', sport: null });
    },
  },
  {
    change: 'batch deleting Activities',
    response: { affectedCount: 2, matchedCount: 2 },
    perform: async (wrapper) => {
      const { result } = renderHook(() => useBatchDeleteActivities(userId), { wrapper });
      await result.current.mutateAsync([otherActivityId, 10]);
    },
  },
  {
    change: 'backfilling Best Efforts',
    response: { activitiesProcessed: 3, bestEffortsCreated: 5 },
    perform: async (wrapper) => {
      const { result } = renderHook(() => useBackfillBestEfforts(userId), { wrapper });
      await result.current.mutateAsync();
    },
  },
  ...(['COMPLETED', 'CANCELLED', 'INTERRUPTED', 'FAILED'] as const).map(
    (status): ActivityChange => ({
      change: `an Import Batch ends ${status}`,
      response: importBatch(status),
      perform: async (wrapper) => {
        const { result } = renderHook(() => useImportBatch({ userId, batchId: 7 }), { wrapper });
        await waitFor(() => expect(result.current.terminal).toBe(true));
      },
    }),
  ),
];

afterEach(() => vi.unstubAllGlobals());

describe('the Activity data module', () => {
  it.each(changes)(
    'refreshes every Activity query, the Dashboard and the Heatmap after $change',
    async ({ response, perform }) => {
      stubFetch(response);
      const { wrapper, staleQueries, names } = seededClient();

      await perform(wrapper);

      await waitFor(() => expect(staleQueries()).toEqual(names));
    },
  );
});
