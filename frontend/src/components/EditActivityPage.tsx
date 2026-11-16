import { useNavigate } from '@tanstack/react-router';
import { useUpdateActivity } from '../api/activities';
import type { ActivitySummaryDto } from '../api/generated/types.gen';
import {
  formValuesFromActivity,
  toCreateRequest,
  toMetadataUpdateRequest,
} from '../lib/activityForm';
import { ActivityForm } from './ActivityForm';

export function EditActivityPage({
  userId,
  activity,
}: {
  userId: number | undefined;
  activity: ActivitySummaryDto;
}) {
  const navigate = useNavigate();
  const update = useUpdateActivity(userId, activity.id);

  return (
    <div className="space-y-6">
      <header className="border-b border-slate-200 dark:border-slate-800 pb-4">
        <h1 className="text-2xl font-bold tracking-tight">Edit Activity</h1>
        <p className="mt-1 text-slate-600 dark:text-slate-400">{activity.title}</p>
      </header>
      <section className="max-w-3xl rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-6 shadow-sm">
        <ActivityForm
          initialValues={formValuesFromActivity(activity)}
          protectedMetricsLocked={activity.hasProtectedMetrics}
          submitLabel="Update activity"
          convert={activity.hasProtectedMetrics ? toMetadataUpdateRequest : toCreateRequest}
          onSubmit={async (request) => {
            await update.mutateAsync(request);
            await navigate({
              to: '/activities/$activityId',
              params: { activityId: String(activity.id) },
            });
          }}
        />
      </section>
    </div>
  );
}
