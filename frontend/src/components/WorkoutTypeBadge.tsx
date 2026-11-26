import type { WorkoutType } from '../api/generated/types.gen';
import { formatWorkoutType } from '../lib/format';

const STYLES: Readonly<Record<WorkoutType, string>> = {
  EASY: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-200',
  LONG_RUN: 'bg-sky-100 text-sky-800 dark:bg-sky-900 dark:text-sky-200',
  TEMPO: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
  INTERVALS: 'bg-violet-100 text-violet-800 dark:bg-violet-900 dark:text-violet-200',
  RACE: 'bg-rose-100 text-rose-800 dark:bg-rose-900 dark:text-rose-200',
};

/**
 * A Run's **Workout Type**. The words carry the meaning, not only the colour. Renders nothing for an
 * Activity without one (any other sport, or a Run not yet classified).
 */
export function WorkoutTypeBadge({ type }: { readonly type: WorkoutType | null }) {
  if (type === null) return null;
  return (
    <span
      data-workout-type={type}
      className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${STYLES[type]}`}
    >
      {formatWorkoutType(type)}
    </span>
  );
}
