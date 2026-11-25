import { Link } from '@tanstack/react-router';
import type {
  CriticalSpeedResponseDto,
  PeakEffortsResponseDto,
  PersonalRecordDto,
  PersonalRecordSlotDto,
} from '../api/generated/types.gen';
import { PaceDurationCurve, type QueryState } from '../features/pace-duration/PaceDurationCurve';
import { PmcPanel, type PmcQueryState } from '../features/pmc/PmcPanel';
import {
  PersonalRecordProgression,
  type ProgressionState,
} from '../features/pr-progression/PersonalRecordProgression';
import {
  averageSpeedKmh,
  formatDate,
  formatDistanceMeters,
  formatDuration,
  formatPacePerKm,
} from '../lib/format';

interface DashboardProps {
  personalRecordSlots: readonly PersonalRecordSlotDto[];
  pmc: PmcQueryState;
  progression: ProgressionState;
  peakEfforts: QueryState<PeakEffortsResponseDto>;
  criticalSpeed: QueryState<CriticalSpeedResponseDto>;
}

export function Dashboard({
  personalRecordSlots,
  pmc,
  progression,
  peakEfforts,
  criticalSpeed,
}: DashboardProps) {
  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 dark:border-slate-800 pb-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Dashboard</h1>
          <p className="mt-1 text-slate-600 dark:text-slate-400">
            Your all-time Personal Records and training overview.
          </p>
        </div>
        <nav className="flex gap-2 text-sm font-medium" aria-label="Dashboard actions">
          <Link
            to="/activities"
            className="rounded-md border border-slate-300 dark:border-slate-700 px-3 py-1.5 hover:bg-slate-100 dark:hover:bg-slate-800"
          >
            All Activities
          </Link>
          <Link
            to="/activities/import"
            className="rounded-md border border-blue-600 px-3 py-1.5 text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950"
          >
            Bulk Import
          </Link>
          <Link
            to="/activities/add_activity"
            className="rounded-md bg-blue-600 px-3 py-1.5 text-white hover:bg-blue-700"
          >
            + Log Activity
          </Link>
        </nav>
      </header>

      <section aria-labelledby="running-pr-heading">
        <div className="mb-3 flex items-center justify-between">
          <h2 id="running-pr-heading" className="text-lg font-bold">
            Running Personal Records
          </h2>
          <span className="rounded-full bg-blue-100 dark:bg-blue-900 px-2.5 py-0.5 text-xs font-semibold text-blue-700 dark:text-blue-200">
            Run
          </span>
        </div>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {personalRecordSlots.map((slot) => (
            <PersonalRecordCard key={slot.benchmarkDistance} slot={slot} />
          ))}
        </div>
        <PersonalRecordProgression progression={progression} />
      </section>

      <PmcPanel pmc={pmc} />

      <PaceDurationCurve peakEfforts={peakEfforts} criticalSpeed={criticalSpeed} />

      <section aria-label="Training summaries">
        <div className="grid gap-4 md:grid-cols-2">
          <PlaceholderCard
            title="Weekly Activity Summary (Coming Soon)"
            description="Aggregate weekly distance, duration, and elevation gain across your activities will appear here."
          />
        </div>
      </section>
    </div>
  );
}

function PersonalRecordCard({ slot }: { slot: PersonalRecordSlotDto }) {
  const headingId = `pr-card-${slot.benchmarkDistance}`;
  return (
    <article
      aria-labelledby={headingId}
      className="flex flex-col gap-3 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-5 shadow-sm"
    >
      <div className="flex items-baseline justify-between gap-2">
        <h3
          id={headingId}
          className="text-sm font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400"
        >
          {slot.benchmarkName}
        </h3>
        <span className="tabular-nums text-xs text-slate-500 dark:text-slate-400">
          {formatDistanceMeters(slot.distanceMeters)}
        </span>
      </div>
      {slot.personalRecord ? (
        <AchievedRecord slot={slot} record={slot.personalRecord} />
      ) : (
        <div className="py-2 text-slate-500 dark:text-slate-400">
          <p className="text-lg">No record yet</p>
          <p className="mt-1 text-sm">
            Import a running activity with telemetry covering this distance to set a record.
          </p>
        </div>
      )}
    </article>
  );
}

function AchievedRecord({
  slot,
  record,
}: {
  slot: PersonalRecordSlotDto;
  record: PersonalRecordDto;
}) {
  return (
    <>
      <div>
        <p className="tabular-nums text-3xl font-bold text-blue-600 dark:text-blue-400">
          {formatDuration(record.elapsedTimeSeconds)}
        </p>
        <p className="tabular-nums font-semibold text-slate-600 dark:text-slate-300">
          {formatPacePerKm(averageSpeedKmh(slot.distanceMeters, record.elapsedTimeSeconds))}
        </p>
      </div>
      <div className="border-t border-slate-200 dark:border-slate-800 pt-2 text-sm">
        <p className="text-slate-500 dark:text-slate-400">
          Achieved on{' '}
          <time
            dateTime={record.activityStartedAt}
            className="tabular-nums font-semibold text-slate-700 dark:text-slate-200"
          >
            {formatDate(record.activityStartedAt)}
          </time>
        </p>
        <Link
          to="/activities/$activityId"
          params={{ activityId: String(record.activityId) }}
          className="font-semibold text-blue-600 dark:text-blue-400 hover:underline"
        >
          {record.activityTitle}
        </Link>
      </div>
    </>
  );
}

function PlaceholderCard({ title, description }: { title: string; description: string }) {
  return (
    <div className="rounded-lg border border-dashed border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 p-5 text-slate-500 dark:text-slate-400">
      <h2 className="mb-2 font-bold text-slate-600 dark:text-slate-300">{title}</h2>
      <p className="text-sm">{description}</p>
    </div>
  );
}
