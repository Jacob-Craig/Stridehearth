import { Link, useNavigate } from '@tanstack/react-router';
import { type ReactNode, useState } from 'react';
import { useDeleteActivity } from '../../api/activities';
import type {
  ActivityDetailDto,
  ActivityTelemetryResponseDto,
} from '../../api/generated/types.gen';
import { DeleteActivityDialog } from '../../components/DeleteActivityDialog';
import {
  formatDate,
  formatDistanceKm,
  formatDuration,
  formatEfficiencyFactor,
  formatNgpPerKm,
  formatOptional,
  formatPacePerKm,
  formatSport,
  formatStaminaPercent,
} from '../../lib/format';
import type { BenchmarkDistance } from '../../types/domain';
import { parseApiError } from '../../utils/error';
import { ActivityHistoryDialog } from './ActivityHistoryDialog';
import { ActivityVisualization } from './ActivityVisualization';
import { BestEffortsTable } from './BestEffortsTable';
import { ConditionsMetrics } from './ConditionsMetrics';
import { DecouplingBadge } from './DecouplingBadge';
import { LapsTable } from './LapsTable';
import { TrainingLoadValue } from './TrainingLoadValue';
import { Vo2maxValue } from './Vo2maxValue';
import { SECONDARY_BUTTON_CLASS } from './styles';

/** The sports whose route can be planned at even Grade-Adjusted effort, as the API allows. */
const SPLIT_PLAN_SPORTS: ReadonlySet<ActivityDetailDto['sport']> = new Set(['RUN', 'WALK', 'HIKE']);

/** A **Race Split Plan** needs a Run, Walk or Hike with a recorded route. */
function hasSplitPlan(
  activity: ActivityDetailDto,
  telemetry: ActivityTelemetryResponseDto | null,
): boolean {
  return SPLIT_PLAN_SPORTS.has(activity.sport) && telemetry?.route != null;
}

/** A single device Lap is the whole Activity again, so Laps are only listed from two upwards. */
const MIN_LAPS_SHOWN = 2;

interface ActivityDetailPageProps {
  readonly userId: number | undefined;
  readonly activity: ActivityDetailDto;
  /** The **Activity Details**, or `null` when the Activity has none (e.g. it was logged manually). */
  readonly telemetry: ActivityTelemetryResponseDto | null;
}

export function ActivityDetailPage({ userId, activity, telemetry }: ActivityDetailPageProps) {
  const navigate = useNavigate();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  // Lifted here so the Best Efforts table and the visualization share one selection.
  const [selectedBenchmarkDistance, setSelectedBenchmarkDistance] =
    useState<BenchmarkDistance | null>(null);
  const selectedEffort =
    activity.bestEfforts.find((effort) => effort.benchmarkDistance === selectedBenchmarkDistance) ??
    null;

  const deletion = useDeleteActivity(userId);

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 dark:border-slate-800 pb-4">
        <div>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight">{activity.title}</h1>
            <span className="rounded-full bg-blue-100 dark:bg-blue-900 px-2.5 py-0.5 text-xs font-semibold text-blue-700 dark:text-blue-200">
              {formatSport(activity.sport)}
            </span>
          </div>
          <p className="mt-1 flex flex-wrap gap-x-2 text-slate-600 dark:text-slate-400">
            <time dateTime={activity.startedAt} className="tabular-nums">
              {formatDate(activity.startedAt)}
            </time>
            <span aria-hidden="true">·</span>
            <span>{`by ${activity.ownerUsername}`}</span>
          </p>
        </div>
        <div className="flex flex-wrap gap-2 text-sm font-medium">
          <ActivityHistoryDialog history={activity.history} />
          {hasSplitPlan(activity, telemetry) && (
            <Link
              to="/activities/$activityId/split-plan"
              params={{ activityId: String(activity.id) }}
              className={SECONDARY_BUTTON_CLASS}
            >
              Split plan
            </Link>
          )}
          <Link
            to="/activities/$activityId/edit"
            params={{ activityId: String(activity.id) }}
            className={SECONDARY_BUTTON_CLASS}
          >
            Edit
          </Link>
          <button
            type="button"
            onClick={() => {
              deletion.reset();
              setConfirmingDelete(true);
            }}
            className="rounded-md border border-red-600 px-3 py-1.5 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950"
          >
            Delete
          </button>
        </div>
      </header>

      <SummaryMetrics activity={activity} />

      <ConditionsMetrics conditions={activity.conditions} />

      {telemetry && <ActivityVisualization telemetry={telemetry} selectedEffort={selectedEffort} />}

      {activity.bestEfforts.length > 0 && (
        <BestEffortsTable
          bestEfforts={activity.bestEfforts}
          selected={selectedBenchmarkDistance}
          onToggle={(benchmarkDistance) =>
            setSelectedBenchmarkDistance((current) =>
              current === benchmarkDistance ? null : benchmarkDistance,
            )
          }
        />
      )}

      {activity.laps.length >= MIN_LAPS_SHOWN && <LapsTable laps={activity.laps} />}

      <DeleteActivityDialog
        activity={confirmingDelete ? activity : null}
        isDeleting={deletion.isPending}
        error={deletion.error ? parseApiError(deletion.error).generalError : null}
        onConfirm={() =>
          deletion.mutate(activity.id, {
            onSuccess: async () => {
              setConfirmingDelete(false);
              await navigate({ to: '/activities' });
            },
          })
        }
        onCancel={() => setConfirmingDelete(false)}
      />
    </div>
  );
}

/**
 * The summary tiles, five per row on wide screens: the recorded metrics, then a Run's derived
 * metrics (NGP, Efficiency Factor, Aerobic Decoupling, VO2max Estimate) when it has them, then the Training Load
 * once it has been computed (a value, or the reason there is none), then the lowest **Stamina** a
 * Run reached when it has a Stamina stream.
 */
function SummaryMetrics({ activity }: { activity: ActivityDetailDto }) {
  const { runMetrics, trainingLoad, trainingLoadUnavailableReason, minStaminaPct } = activity;
  const metrics: ReadonlyArray<readonly [label: string, value: ReactNode]> = [
    ['Distance', formatDistanceKm(activity.distance)],
    ['Duration', formatDuration(activity.timeTakenSeconds)],
    ['Avg Pace', formatPacePerKm(activity.averagePaceKmh)],
    ['Avg Heart Rate', formatOptional(activity.averageHeartRate, 'bpm')],
    ['Calories', formatOptional(activity.calories, 'kcal')],
    ...(runMetrics
      ? ([
          ['NGP', formatNgpPerKm(runMetrics.ngpMetersPerSecond)],
          ['Efficiency Factor', formatEfficiencyFactor(runMetrics.efficiencyFactor)],
          ['Aerobic Decoupling', <DecouplingBadge key="decoupling" runMetrics={runMetrics} />],
          ['VO2max', <Vo2maxValue key="vo2max" runMetrics={runMetrics} />],
        ] as const)
      : []),
    ...(trainingLoad || trainingLoadUnavailableReason
      ? ([
          ['Training Load', <TrainingLoadValue key="training-load" activity={activity} />],
        ] as const)
      : []),
    ...(minStaminaPct != null
      ? ([['Min Stamina', formatStaminaPercent(minStaminaPct)]] as const)
      : []),
  ];
  return (
    <ul
      aria-label="Summary metrics"
      className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5"
    >
      {metrics.map(([label, value]) => (
        <li
          key={label}
          className="rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 shadow-sm"
        >
          <span className="block text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">
            {label}
          </span>
          <span className="mt-1 block text-xl font-bold tabular-nums">{value}</span>
        </li>
      ))}
    </ul>
  );
}
