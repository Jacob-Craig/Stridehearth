import { Link } from '@tanstack/react-router';
import type { ActivityDetailDto } from '../../api/generated/types.gen';
import { formatTrainingLoad, formatTrainingLoadUnavailableReason } from '../../lib/format';

const REASON_CLASS = 'mt-1 block text-xs font-medium text-slate-500 dark:text-slate-400';

/** Reasons the athlete fixes on the settings page, where Thresholds are recorded. */
const SETTINGS_REASONS: ReadonlySet<string> = new Set(['NO_THRESHOLDS', 'INCONSISTENT_THRESHOLDS']);

/**
 * An Activity's **Training Load** as `87 · rTSS`, or "No Training Load" with the reason it has none;
 * when Thresholds are missing or contradict each other, the reason links to the settings page where they are
 * recorded.
 */
export function TrainingLoadValue({
  activity,
}: {
  readonly activity: Pick<
    ActivityDetailDto,
    'sport' | 'trainingLoad' | 'trainingLoadUnavailableReason'
  >;
}) {
  const { sport, trainingLoad, trainingLoadUnavailableReason: reason } = activity;
  if (trainingLoad) return <>{formatTrainingLoad(trainingLoad)}</>;
  return (
    <>
      <span className="text-base">No Training Load</span>
      {reason && SETTINGS_REASONS.has(reason) ? (
        <Link
          to="/settings"
          className={`${REASON_CLASS} underline hover:text-slate-700 dark:hover:text-slate-200`}
        >
          {formatTrainingLoadUnavailableReason(reason, sport)}
        </Link>
      ) : (
        reason && (
          <span className={REASON_CLASS}>{formatTrainingLoadUnavailableReason(reason, sport)}</span>
        )
      )}
    </>
  );
}
