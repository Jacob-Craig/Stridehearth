import { Link, useRouterState } from '@tanstack/react-router';
import { useState } from 'react';
import { useDeleteActivity } from '../api/activities';
import type {
  ActivitySummaryDto,
  PagedResponseDtoActivitySummaryDto,
} from '../api/generated/types.gen';
import { parseApiError } from '../utils/error';
import { ActivityList } from './ActivityList';
import { DeleteActivityDialog } from './DeleteActivityDialog';

interface ActivitiesPageProps {
  userId: number | undefined;
  activityPage: PagedResponseDtoActivitySummaryDto;
  onPageChange: (page: number) => void;
}

export function ActivitiesPage({ userId, activityPage, onPageChange }: ActivitiesPageProps) {
  const notice = useRouterState({ select: (state) => state.location.state.notice });
  const [pendingDelete, setPendingDelete] = useState<ActivitySummaryDto | null>(null);
  const deletion = useDeleteActivity(userId);

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 dark:border-slate-800 pb-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Activities</h1>
          <p className="mt-1 text-slate-600 dark:text-slate-400">
            Every Activity you have recorded, newest first.
          </p>
        </div>
        <Link
          to="/activities/add_activity"
          className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
        >
          + Log Activity
        </Link>
      </header>

      {notice && (
        <output className="block rounded-lg border border-blue-200 dark:border-blue-900 bg-blue-50 dark:bg-blue-950 p-3 text-sm text-blue-800 dark:text-blue-200">
          {notice}
        </output>
      )}

      <ActivityList
        activityPage={activityPage}
        onPageChange={onPageChange}
        onDeleteRequest={(activity) => {
          deletion.reset();
          setPendingDelete(activity);
        }}
      />

      <DeleteActivityDialog
        activity={pendingDelete}
        isDeleting={deletion.isPending}
        error={deletion.error ? parseApiError(deletion.error).generalError : null}
        onConfirm={(activity) =>
          deletion.mutate(activity.id, {
            onSuccess: () => {
              setPendingDelete(null);
              const pageNowEmpty = activityPage.items.length === 1 && activityPage.hasPrevious;
              if (pageNowEmpty) onPageChange(activityPage.page - 1);
            },
          })
        }
        onCancel={() => setPendingDelete(null)}
      />
    </div>
  );
}
