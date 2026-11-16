import type { ActivityLapDto } from '../../api/generated/types.gen';
import {
  formatDistanceKm,
  formatDuration,
  formatElevationGain,
  formatOptional,
  formatPacePerKm,
} from '../../lib/format';
import { NumericCell, type TableColumn, TableSection } from './TableSection';

const COLUMNS: readonly TableColumn[] = [
  { label: 'Lap' },
  { label: 'Distance', numeric: true },
  { label: 'Duration', numeric: true },
  { label: 'Pace', numeric: true },
  { label: 'Avg HR', numeric: true },
  { label: 'Elevation Gain', numeric: true },
];

/** The device-recorded **Laps** of an **Activity**; below the fold, so its layout is skipped until scrolled to. */
export function LapsTable({ laps }: { readonly laps: readonly ActivityLapDto[] }) {
  return (
    <TableSection
      headingId="laps-heading"
      title="Laps"
      columns={COLUMNS}
      className="[content-visibility:auto] [contain-intrinsic-size:auto_320px]"
    >
      {laps.map((lap) => (
        <tr key={lap.lapNumber}>
          <td className="px-4 py-3 font-semibold tabular-nums">{lap.lapNumber}</td>
          <NumericCell>{formatDistanceKm(lap.distance)}</NumericCell>
          <NumericCell>{formatDuration(lap.timeTakenSeconds)}</NumericCell>
          <NumericCell>{formatPacePerKm(lap.averagePaceKmh)}</NumericCell>
          <NumericCell>{formatOptional(lap.averageHeartRate, 'bpm')}</NumericCell>
          <NumericCell>{formatElevationGain(lap.elevationGainMeters)}</NumericCell>
        </tr>
      ))}
    </TableSection>
  );
}
