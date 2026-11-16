import type { ActivityBestEffortDto } from '../../api/generated/types.gen';
import {
  averageSpeedKmh,
  formatDistanceRangeKm,
  formatDuration,
  formatDurationRange,
  formatPacePerKm,
} from '../../lib/format';
import type { BenchmarkDistance } from '../../types/domain';
import { NumericCell, type TableColumn, TableSection } from './TableSection';

const COLUMNS: readonly TableColumn[] = [
  { label: 'Distance' },
  { label: 'Time', numeric: true },
  { label: 'Pace', numeric: true },
  { label: 'Elapsed window', numeric: true },
  { label: 'Distance window', numeric: true },
];

interface BestEffortsTableProps {
  readonly bestEfforts: readonly ActivityBestEffortDto[];
  /** The Benchmark Distance whose Best Effort is selected, or `null` when none is. */
  readonly selected: BenchmarkDistance | null;
  /** Selects the Best Effort for this Benchmark Distance, or clears it when it is already selected. */
  readonly onToggle: (benchmarkDistance: BenchmarkDistance) => void;
}

/**
 * The **Best Efforts** of an **Activity**. Each row's leading toggle button is the accessible control
 * (focus, `Enter`/`Space`, `aria-pressed`); clicking anywhere else on the row is a pointer shortcut.
 */
export function BestEffortsTable({ bestEfforts, selected, onToggle }: BestEffortsTableProps) {
  return (
    <TableSection
      headingId="best-efforts-heading"
      title="Best Efforts"
      description="Select a Best Effort to highlight where in the Activity it was achieved."
      columns={COLUMNS}
    >
      {bestEfforts.map((effort) => (
        <BestEffortRow
          key={effort.benchmarkDistance}
          effort={effort}
          isSelected={effort.benchmarkDistance === selected}
          onToggle={onToggle}
        />
      ))}
    </TableSection>
  );
}

function BestEffortRow({
  effort,
  isSelected,
  onToggle,
}: {
  effort: ActivityBestEffortDto;
  isSelected: boolean;
  onToggle: (benchmarkDistance: BenchmarkDistance) => void;
}) {
  const { window } = effort;
  const toggle = () => onToggle(effort.benchmarkDistance);
  return (
    // biome-ignore lint/a11y/useKeyWithClickEvents: a pointer-only shortcut; the row's toggle button is the keyboard-accessible control.
    <tr
      onClick={toggle}
      className={
        isSelected
          ? 'cursor-pointer bg-amber-50 dark:bg-amber-950/40'
          : 'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/40'
      }
    >
      <td className="px-4 py-3">
        <button
          type="button"
          aria-pressed={isSelected}
          aria-label={`${effort.benchmarkName} Best Effort`}
          onClick={(event) => {
            // Handled here, so the row's pointer shortcut must not toggle it back.
            event.stopPropagation();
            toggle();
          }}
          className="font-semibold text-blue-700 dark:text-blue-300 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600"
        >
          {effort.benchmarkName}
        </button>
      </td>
      <NumericCell>{formatDuration(effort.elapsedTimeSeconds)}</NumericCell>
      <NumericCell>
        {/* Over the nominal Benchmark Distance, as the Dashboard paces Personal Records. */}
        {formatPacePerKm(averageSpeedKmh(effort.distanceMeters, effort.elapsedTimeSeconds))}
      </NumericCell>
      <NumericCell>
        {formatDurationRange(window.startTimeSeconds, window.endTimeSeconds)}
      </NumericCell>
      <NumericCell>
        {formatDistanceRangeKm(window.startDistanceMeters, window.endDistanceMeters)}
      </NumericCell>
    </tr>
  );
}
