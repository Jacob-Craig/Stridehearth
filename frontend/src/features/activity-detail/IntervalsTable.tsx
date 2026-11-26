import type { DetectedIntervalDto } from '../../api/generated/types.gen';
import {
  formatDecouplingPercent,
  formatDistanceMeters,
  formatDuration,
  formatNgpPerKm,
  formatOptional,
  MISSING,
} from '../../lib/format';
import { NumericCell, type TableColumn, TableSection } from './TableSection';

const COLUMNS: readonly TableColumn[] = [
  { label: '#' },
  { label: 'Type' },
  { label: 'Distance', numeric: true },
  { label: 'Duration', numeric: true },
  { label: 'Pace', numeric: true },
  { label: 'Avg HR', numeric: true },
  { label: 'Avg Power', numeric: true },
  { label: 'Decoupling', numeric: true },
];

/**
 * The **Detected Intervals** of a Run: its work and recovery stretches, found from its pace or power without any lap
 * press. Work rows are tinted, and the same intervals are shaded on the charts above. Pace is Grade-Adjusted.
 */
export function IntervalsTable({
  intervals,
}: {
  readonly intervals: readonly DetectedIntervalDto[];
}) {
  return (
    <TableSection
      headingId="intervals-heading"
      title="Intervals"
      description="Work and recovery found automatically from pace or power. Work is shaded on the charts."
      columns={COLUMNS}
      className="[content-visibility:auto] [contain-intrinsic-size:auto_320px]"
    >
      {intervals.map((interval) => {
        const isWork = interval.kind === 'WORK';
        return (
          <tr
            key={interval.intervalNumber}
            data-kind={interval.kind}
            className={isWork ? 'bg-teal-50 dark:bg-teal-950/40' : undefined}
          >
            <td className="px-4 py-3 font-semibold tabular-nums">{interval.intervalNumber}</td>
            <td className="px-4 py-3">
              <span
                className={
                  isWork
                    ? 'rounded-full bg-teal-100 dark:bg-teal-900 px-2.5 py-0.5 text-xs font-semibold text-teal-800 dark:text-teal-200'
                    : 'rounded-full bg-slate-100 dark:bg-slate-800 px-2.5 py-0.5 text-xs font-semibold text-slate-700 dark:text-slate-300'
                }
              >
                {isWork ? 'Work' : 'Recovery'}
              </span>
            </td>
            <NumericCell>
              {interval.distanceMeters === null
                ? MISSING
                : formatDistanceMeters(Math.round(interval.distanceMeters))}
            </NumericCell>
            <NumericCell>{formatDuration(interval.movingSeconds)}</NumericCell>
            <NumericCell>{formatNgpPerKm(interval.averageGapMetersPerSecond)}</NumericCell>
            <NumericCell>
              {formatOptional(
                interval.averageHeartRate === null ? null : Math.round(interval.averageHeartRate),
                'bpm',
              )}
            </NumericCell>
            <NumericCell>
              {formatOptional(
                interval.averagePowerWatts === null ? null : Math.round(interval.averagePowerWatts),
                'W',
              )}
            </NumericCell>
            <NumericCell>
              {interval.decouplingPct === null
                ? MISSING
                : formatDecouplingPercent(interval.decouplingPct)}
            </NumericCell>
          </tr>
        );
      })}
    </TableSection>
  );
}
