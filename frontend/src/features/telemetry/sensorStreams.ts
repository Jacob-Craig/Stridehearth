import type {
  ActivityDerivedStreamsDto,
  ActivityTelemetryStreamsDto,
} from '../../api/generated/types.gen';
import type { ColorScheme } from '../../lib/colorScheme';

type SensorStreamKey = Exclude<keyof ActivityTelemetryStreamsDto, 't'>;

/** A sensor of the **Activity Details** that is charted when it was recorded. */
interface SensorChart {
  readonly id: string;
  readonly label: string;
  readonly unit: string;
  /** Candidate streams, best first: "enhanced" streams take precedence over their legacy equivalents. */
  readonly streams: readonly SensorStreamKey[];
  readonly transform?: (value: number) => number;
  readonly color: Readonly<Record<ColorScheme, string>>;
}

const metersPerSecondToKmh = (mps: number) => mps * 3.6;

/** The nine charted sensors, in display order. */
const SENSOR_CHARTS: readonly SensorChart[] = [
  {
    id: 'elevation',
    label: 'Elevation',
    unit: 'm',
    streams: ['enhancedAltitude', 'altitude'],
    color: { light: '#64748b', dark: '#94a3b8' },
  },
  {
    id: 'speed',
    label: 'Speed',
    unit: 'km/h',
    streams: ['enhancedSpeed', 'speed'],
    transform: metersPerSecondToKmh,
    color: { light: '#0891b2', dark: '#22d3ee' },
  },
  {
    id: 'heart-rate',
    label: 'Heart Rate',
    unit: 'bpm',
    streams: ['heartRate'],
    color: { light: '#dc2626', dark: '#f87171' },
  },
  {
    id: 'power',
    label: 'Power',
    unit: 'W',
    streams: ['power'],
    color: { light: '#ea580c', dark: '#fb923c' },
  },
  {
    id: 'cadence',
    label: 'Cadence',
    unit: '/min',
    streams: ['cadence'],
    color: { light: '#7c3aed', dark: '#a78bfa' },
  },
  {
    id: 'respiration-rate',
    label: 'Respiration Rate',
    unit: 'brpm',
    streams: ['enhancedRespirationRate', 'respirationRate'],
    color: { light: '#0d9488', dark: '#2dd4bf' },
  },
  {
    id: 'temperature',
    label: 'Temperature',
    unit: '°C',
    streams: ['temperature'],
    color: { light: '#ca8a04', dark: '#facc15' },
  },
  {
    id: 'vertical-oscillation',
    label: 'Vertical Oscillation',
    unit: 'mm',
    streams: ['verticalOscillation'],
    color: { light: '#16a34a', dark: '#4ade80' },
  },
  {
    id: 'ground-contact-time',
    label: 'Ground Contact Time',
    unit: 'ms',
    streams: ['stanceTime'],
    color: { light: '#475569', dark: '#cbd5e1' },
  },
];

/**
 * A derived stream drawn over a chart's own series, in the same unit, which the athlete can toggle:
 * **Grade-Adjusted Pace** over Speed.
 */
export interface ChartOverlay {
  readonly label: string;
  readonly color: Readonly<Record<ColorScheme, string>>;
  /** Parallel to the elapsed-time stream `t`; `null` where the derived stream has no value. */
  readonly values: readonly (number | null)[];
}

/** One sensor stream to chart against elapsed time, already converted to its display unit. */
export interface ChartSeries {
  readonly id: string;
  readonly label: string;
  readonly unit: string;
  readonly color: Readonly<Record<ColorScheme, string>>;
  /** Parallel to the elapsed-time stream `t`; `null` where the sensor recorded nothing. */
  readonly values: readonly (number | null)[];
  readonly overlay?: ChartOverlay;
}

const GAP_COLOR: Readonly<Record<ColorScheme, string>> = { light: '#c026d3', dark: '#e879f9' };

const isRecorded = (
  values: readonly (number | null)[] | null | undefined,
  length: number,
): values is readonly (number | null)[] =>
  values?.length === length && values.some((reading) => reading != null);

/**
 * The **Grade-Adjusted Pace** overlay for the Speed chart, in km/h, or `null` when there is no GAP
 * stream parallel to `t`. GAP only repeats speed without elevation, and without a GPS fix (the
 * server takes the grade as 0 then), so it is offered only with both.
 */
function gapOverlay(
  derived: ActivityDerivedStreamsDto | null | undefined,
  length: number,
  hasGrade: boolean,
): ChartOverlay | null {
  const gapSpeed = derived?.gapSpeed;
  if (!hasGrade || !isRecorded(gapSpeed, length)) return null;
  return {
    label: 'GAP',
    color: GAP_COLOR,
    values: gapSpeed.map((value) => (value == null ? null : metersPerSecondToKmh(value))),
  };
}

/**
 * The sensor streams of the **Activity Details** worth charting: every one of the nine sensors with a
 * stream parallel to `t` that recorded at least one value, in display order. The Speed chart carries
 * the **Grade-Adjusted Pace** overlay when `derived` has a GAP stream and both elevation and a GPS
 * fix were recorded.
 */
export function selectChartSeries(
  streams: ActivityTelemetryStreamsDto,
  derived?: ActivityDerivedStreamsDto | null,
): ChartSeries[] {
  const length = streams.t.length;
  const hasGpsFix = streams.routeIndex?.some((vertex) => vertex != null) ?? false;
  const series: ChartSeries[] = [];
  for (const chart of SENSOR_CHARTS) {
    const recorded = chart.streams
      .map((key) => streams[key])
      .find((values) => isRecorded(values, length));
    if (!recorded) continue;
    const { transform } = chart;
    const overlay =
      chart.id === 'speed'
        ? gapOverlay(derived, length, hasGpsFix && series.some((s) => s.id === 'elevation'))
        : null;
    series.push({
      id: chart.id,
      label: chart.label,
      unit: chart.unit,
      color: chart.color,
      values: transform
        ? recorded.map((value) => (value == null ? null : transform(value)))
        : recorded,
      ...(overlay && { overlay }),
    });
  }
  return series;
}
