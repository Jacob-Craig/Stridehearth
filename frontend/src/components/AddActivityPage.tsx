import { useNavigate } from '@tanstack/react-router';
import { match } from 'ts-pattern';
import { useCreateActivity } from '../api/activities';
import type { SingleFileUploadResponseDto } from '../api/generated/types.gen';
import { emptyActivityFormValues, toCreateRequest } from '../lib/activityForm';
import { ActivityForm } from './ActivityForm';
import { FitUploadForm } from './FitUploadForm';

const cardClass =
  'rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-6 shadow-sm';

export function AddActivityPage({ userId }: { userId: number | undefined }) {
  const navigate = useNavigate();
  const creation = useCreateActivity(userId);

  const showActivity = (activityId: number) =>
    navigate({ to: '/activities/$activityId', params: { activityId: String(activityId) } });

  const showNotice = (notice: string) => navigate({ to: '/activities', state: { notice } });

  const handleUploaded = async (result: SingleFileUploadResponseDto) => {
    await match(result)
      .with({ kind: 'CREATED' }, { kind: 'UPGRADED' }, ({ activityId }) => showActivity(activityId))
      .with({ kind: 'MULTI_PROCESSED' }, ({ processedCount }) =>
        showNotice(`Processed ${processedCount} activities`),
      )
      .with({ kind: 'DUPLICATE' }, () => showNotice('That activity was already imported'))
      .exhaustive();
  };

  return (
    <div className="space-y-6">
      <header className="border-b border-slate-200 dark:border-slate-800 pb-4">
        <h1 className="text-2xl font-bold tracking-tight">Add Activity</h1>
        <p className="mt-1 text-slate-600 dark:text-slate-400">
          Log an Activity by hand, or upload the file your device recorded.
        </p>
      </header>
      <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
        <section aria-labelledby="manual-activity-heading" className={cardClass}>
          <h2 id="manual-activity-heading" className="mb-4 text-lg font-bold">
            Log manually
          </h2>
          <ActivityForm
            initialValues={emptyActivityFormValues()}
            protectedMetricsLocked={false}
            submitLabel="Save activity"
            convert={toCreateRequest}
            onSubmit={async (request) => {
              const created = await creation.mutateAsync(request);
              await showActivity(created.id);
            }}
          />
        </section>
        <section className={cardClass}>
          <FitUploadForm userId={userId} onUploaded={handleUploaded} />
        </section>
      </div>
    </div>
  );
}
