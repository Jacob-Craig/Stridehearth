import { Link } from '@tanstack/react-router';
import type {
  ActivitySummaryDto,
  PagedResponseDtoActivitySummaryDto,
} from '../api/generated/types.gen';
import {
  formatDate,
  formatDistanceKm,
  formatDuration,
  formatOptional,
  formatPacePerKm,
  formatSport,
} from '../lib/format';

interface ActivityListProps {
  activityPage: PagedResponseDtoActivitySummaryDto;
  onPageChange: (page: number) => void;
  onDeleteRequest: (activity: ActivitySummaryDto) => void;
}

export function ActivityList({ activityPage, onPageChange, onDeleteRequest }: ActivityListProps) {
  if (activityPage.totalElements === 0) {
    return (
      <div className="rounded-lg border border-dashed border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 p-8 text-center text-slate-500 dark:text-slate-400">
        <p className="text-lg">No activities yet</p>
        <p className="mt-1 text-sm">
          Log an activity manually or upload a .fit file to get started.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shadow-sm [content-visibility:auto] [contain-intrinsic-size:auto_480px]">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 dark:bg-slate-800/50 text-left text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
            <tr>
              <th scope="col" className="px-4 py-3">
                Sport
              </th>
              <th scope="col" className="px-4 py-3">
                Title
              </th>
              <th scope="col" className="px-4 py-3">
                Date
              </th>
              <th scope="col" className="px-4 py-3 text-right">
                Distance
              </th>
              <th scope="col" className="px-4 py-3 text-right">
                Duration
              </th>
              <th scope="col" className="px-4 py-3 text-right">
                Avg Pace
              </th>
              <th scope="col" className="px-4 py-3 text-right">
                Avg HR
              </th>
              <th scope="col" className="px-4 py-3 text-right">
                Calories
              </th>
              <th scope="col" className="px-4 py-3 text-right">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
            {activityPage.items.map((activity) => (
              <ActivityRow
                key={activity.id}
                activity={activity}
                onDeleteRequest={onDeleteRequest}
              />
            ))}
          </tbody>
        </table>
      </div>
      <Pagination activityPage={activityPage} onPageChange={onPageChange} />
    </div>
  );
}

function ActivityRow({
  activity,
  onDeleteRequest,
}: {
  activity: ActivitySummaryDto;
  onDeleteRequest: (activity: ActivitySummaryDto) => void;
}) {
  const params = { activityId: String(activity.id) };
  return (
    <tr className="hover:bg-slate-50 dark:hover:bg-slate-800/40">
      <td className="px-4 py-3">
        <span className="rounded-full bg-blue-100 dark:bg-blue-900 px-2.5 py-0.5 text-xs font-semibold text-blue-700 dark:text-blue-200">
          {formatSport(activity.sport)}
        </span>
      </td>
      <td className="px-4 py-3 font-medium">{activity.title}</td>
      <td className="px-4 py-3">
        <time dateTime={activity.startedAt} className="tabular-nums">
          {formatDate(activity.startedAt)}
        </time>
      </td>
      <Metric>{formatDistanceKm(activity.distance)}</Metric>
      <Metric>{formatDuration(activity.timeTakenSeconds)}</Metric>
      <Metric>{formatPacePerKm(activity.averagePaceKmh)}</Metric>
      <Metric>{formatOptional(activity.averageHeartRate, 'bpm')}</Metric>
      <Metric>{formatOptional(activity.calories, 'kcal')}</Metric>
      <td className="px-4 py-3">
        <div className="flex justify-end gap-2 font-medium">
          <Link
            to="/activities/$activityId"
            params={params}
            aria-label={`View ${activity.title}`}
            className="text-blue-600 dark:text-blue-400 hover:underline"
          >
            View
          </Link>
          <Link
            to="/activities/$activityId/edit"
            params={params}
            aria-label={`Edit ${activity.title}`}
            className="text-slate-700 dark:text-slate-300 hover:underline"
          >
            Edit
          </Link>
          <button
            type="button"
            aria-label={`Delete ${activity.title}`}
            onClick={() => onDeleteRequest(activity)}
            className="text-red-600 dark:text-red-400 hover:underline"
          >
            Delete
          </button>
        </div>
      </td>
    </tr>
  );
}

function Metric({ children }: { children: string }) {
  return (
    <td className="px-4 py-3 text-right">
      <span className="tabular-nums">{children}</span>
    </td>
  );
}

export function Pagination({
  activityPage,
  onPageChange,
}: Pick<ActivityListProps, 'activityPage' | 'onPageChange'>) {
  const { page, totalPages, hasNext, hasPrevious } = activityPage;
  const buttonClass =
    'rounded-md border border-slate-300 dark:border-slate-700 px-3 py-1.5 font-medium hover:bg-slate-100 dark:hover:bg-slate-800 disabled:opacity-50 disabled:hover:bg-transparent';
  return (
    <nav aria-label="Pagination" className="flex items-center justify-between text-sm">
      <button
        type="button"
        className={buttonClass}
        disabled={!hasPrevious}
        onClick={() => onPageChange(page - 1)}
      >
        Previous
      </button>
      <span className="tabular-nums text-slate-600 dark:text-slate-400">
        Page {page + 1} of {totalPages}
      </span>
      <button
        type="button"
        className={buttonClass}
        disabled={!hasNext}
        onClick={() => onPageChange(page + 1)}
      >
        Next
      </button>
    </nav>
  );
}
