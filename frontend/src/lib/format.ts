/**
 * Pure display formatters for canonical domain primitives returned by `/api/**`.
 * The API never pre-formats values; every human-readable string is produced here.
 */

import type {
  ActivitySummaryDto,
  RunMetricsDto,
  TrainingLoadDto,
  TrainingLoadMethod,
  TrainingLoadUnavailableReason,
  WorkoutType,
} from '../api/generated/types.gen';

export type ActivitySport = ActivitySummaryDto['sport'];

/** How every formatter shows an absent value. */
export const MISSING = '—';

export const pad2 = (value: number): string => value.toString().padStart(2, '0');

/** Formats elapsed seconds as `MM:SS`, or `HH:MM:SS` once an hour or more has elapsed. */
export function formatDuration(elapsedSeconds: number): string {
  if (!Number.isFinite(elapsedSeconds) || elapsedSeconds < 0) return MISSING;
  const total = Math.round(elapsedSeconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  return hours > 0
    ? `${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)}`
    : `${pad2(minutes)}:${pad2(seconds)}`;
}

/** Average speed in km/h for covering `distanceMeters` in `elapsedSeconds`. */
export function averageSpeedKmh(distanceMeters: number, elapsedSeconds: number): number {
  if (elapsedSeconds <= 0) return 0;
  return distanceMeters / 1000 / (elapsedSeconds / 3600);
}

/** Formats a speed in km/h as a running pace, e.g. `04:00 /km`. */
export function formatPacePerKm(speedKmh: number | null): string {
  if (speedKmh === null || !Number.isFinite(speedKmh) || speedKmh <= 0) return MISSING;
  const secondsPerKm = Math.round(3600 / speedKmh);
  return `${pad2(Math.floor(secondsPerKm / 60))}:${pad2(secondsPerKm % 60)} /km`;
}

/** Formats a **Normalized Graded Pace** in m/s as a running pace, e.g. `05:00 /km`, or `—` when absent. */
export function formatNgpPerKm(metersPerSecond: number | null): string {
  return metersPerSecond === null ? MISSING : formatPacePerKm(metersPerSecond * 3.6);
}

/** Formats an **Efficiency Factor** (m/min per bpm) to two decimal places, or `—` when absent. */
export function formatEfficiencyFactor(efficiencyFactor: number | null): string {
  return efficiencyFactor === null ? MISSING : efficiencyFactor.toFixed(2);
}

/** Formats a **Stamina** percentage (0-100) as a whole percentage, e.g. `25%`. */
export function formatStaminaPercent(percent: number): string {
  return `${Math.round(percent)}%`;
}

/** Formats a temperature in °C to whole degrees, e.g. `18 °C`; never `-0 °C`. */
export function formatTemperatureC(celsius: number): string {
  return `${Math.round(celsius) || 0} °C`;
}

/** Formats an altitude in metres to whole metres, e.g. `312 m`. */
export function formatAltitudeM(metres: number): string {
  return `${Math.round(metres) || 0} m`;
}

/** Formats an **Aerobic Decoupling** percentage to one decimal place, e.g. `4.9%`. */
export function formatDecouplingPercent(percent: number): string {
  return `${percent.toFixed(1)}%`;
}

/** At or above this **Aerobic Decoupling** percentage, heart rate drifted from pace. */
export const DECOUPLING_DRIFT_PERCENT = 5;

/** Whether an **Aerobic Decoupling** shows heart rate and pace coupled (below 5 %, negative included). */
export function decouplingStatus(percent: number): 'coupled' | 'drifting' {
  return percent < DECOUPLING_DRIFT_PERCENT ? 'coupled' : 'drifting';
}

export type DecouplingUnavailableReason = NonNullable<RunMetricsDto['decouplingUnavailableReason']>;

const DECOUPLING_UNAVAILABLE_LABELS: Readonly<Record<DecouplingUnavailableReason, string>> = {
  NO_PACE_DATA: 'No pace data',
  NO_HEART_RATE: 'No heart rate',
  TOO_SHORT: 'Needs 30 min of running',
  HEART_RATE_GAP_IN_HALF: 'Heart rate missing in one half',
  NOT_STEADY: 'Pace too variable',
};

/** Why a Run has no **Aerobic Decoupling**, e.g. `Pace too variable`. */
export function formatDecouplingUnavailableReason(reason: DecouplingUnavailableReason): string {
  return DECOUPLING_UNAVAILABLE_LABELS[reason];
}

export type Vo2maxUnavailableReason = NonNullable<RunMetricsDto['vo2maxUnavailableReason']>;

const VO2MAX_UNAVAILABLE_LABELS: Readonly<Record<Vo2maxUnavailableReason, string>> = {
  NO_ACTIVITY_DETAILS: 'No recorded data',
  NO_PACE_DATA: 'No pace data',
  NO_HEART_RATE_THRESHOLDS: 'Set your Max and Resting HR',
  NO_HEART_RATE: 'No heart rate',
  TOO_SHORT: 'Needs 20 min of running',
  TOO_HILLY: 'Too hilly',
  NOT_STEADY: 'Pace too variable',
  INTENSITY_TOO_LOW: 'Too easy to estimate',
  TOO_FEW_SEGMENTS: 'Too little steady running',
  OUT_OF_RANGE: 'Outside the plausible range',
};

/** Why a Run has no **VO2max Estimate**, e.g. `Too hilly`. */
export function formatVo2maxUnavailableReason(reason: Vo2maxUnavailableReason): string {
  return VO2MAX_UNAVAILABLE_LABELS[reason];
}

/** A VO2max in ml/kg/min to one decimal, e.g. `52.1`. */
export function formatVo2max(value: number): string {
  return value.toFixed(1);
}

const TRAINING_LOAD_METHOD_LABELS: Readonly<Record<TrainingLoadMethod, string>> = {
  RSS: 'RSS',
  RTSS: 'rTSS',
  HRSS: 'HRSS',
};

/** A **Training Load** rounded to an integer with its **Training Load Method**, e.g. `87 · rTSS`. */
export function formatTrainingLoad(load: TrainingLoadDto): string {
  return `${Math.round(load.value)} · ${TRAINING_LOAD_METHOD_LABELS[load.method]}`;
}

/** Sports whose power counts toward **Training Load** (RSS); any other sport is scored from heart rate only. */
const RSS_SPORTS: ReadonlySet<ActivitySport> = new Set(['RUN', 'WALK', 'HIKE']);

const TRAINING_LOAD_UNAVAILABLE_LABELS: Readonly<Record<TrainingLoadUnavailableReason, string>> = {
  NO_ACTIVITY_DETAILS: 'No Activity Details recorded',
  NO_THRESHOLDS: 'Set your Thresholds',
  INCONSISTENT_THRESHOLDS:
    'Check your Thresholds: max and threshold heart rate must be above resting',
  NO_USABLE_STREAM: 'No usable heart rate or power',
};

/**
 * Why an Activity of `sport` has no **Training Load**, e.g. `No usable heart rate or power`. Power counts only for
 * Runs, Walks and Hikes, so another sport without usable heart rate reads `No usable heart rate`.
 */
export function formatTrainingLoadUnavailableReason(
  reason: TrainingLoadUnavailableReason,
  sport: ActivitySport,
): string {
  if (reason === 'NO_USABLE_STREAM' && !RSS_SPORTS.has(sport)) return 'No usable heart rate';
  return TRAINING_LOAD_UNAVAILABLE_LABELS[reason];
}

const kilometersFormat = new Intl.NumberFormat('en-GB', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** Formats a distance in kilometers to two decimal places, e.g. `21.10 km`. */
export function formatDistanceKm(kilometers: number): string {
  return `${kilometersFormat.format(kilometers)} km`;
}

const metersFormat = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 3 });

/** Formats a distance in meters without trailing zeros, e.g. `1,609.344 m`. */
export function formatDistanceMeters(meters: number): string {
  return `${metersFormat.format(meters)} m`;
}

const LOCAL_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Formats an ISO-8601 local date (`2026-03-01`) or instant (`2026-04-10T09:00:00Z`) as `1 Mar 2026`.
 * Local dates are calendar days and never shift; instants are rendered in `timeZone`
 * (the viewer's zone when omitted).
 */
export function formatDate(iso: string, timeZone?: string): string {
  const isLocalDate = LOCAL_DATE.test(iso);
  const date = new Date(isLocalDate ? `${iso}T00:00:00Z` : iso);
  if (Number.isNaN(date.getTime())) return MISSING;
  return formatInstant(date, {}, isLocalDate ? 'UTC' : timeZone);
}

/** Formats `date` as `1 Mar 2026` plus any `extra` fields, in `timeZone` (the viewer's when omitted). */
function formatInstant(date: Date, extra: Intl.DateTimeFormatOptions, timeZone?: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    ...extra,
    ...(timeZone ? { timeZone } : {}),
  }).format(date);
}

const SPORT_LABELS: Readonly<Record<ActivitySport, string>> = {
  RUN: 'Run',
  WALK: 'Walk',
  HIKE: 'Hike',
  RIDE: 'Ride',
  SWIM: 'Swim',
  WORKOUT: 'Workout',
};

/** Every sport, in display order. */
export const ACTIVITY_SPORTS = Object.keys(SPORT_LABELS) as readonly ActivitySport[];

/** The human-readable name of a sport, e.g. `Run`. */
export function formatSport(sport: ActivitySport): string {
  return SPORT_LABELS[sport];
}

const WORKOUT_TYPE_LABELS: Readonly<Record<WorkoutType, string>> = {
  EASY: 'Easy',
  LONG_RUN: 'Long Run',
  TEMPO: 'Tempo',
  INTERVALS: 'Intervals',
  RACE: 'Race',
};

/** Every **Workout Type**, in display order. */
export const WORKOUT_TYPES = Object.keys(WORKOUT_TYPE_LABELS) as readonly WorkoutType[];

/** The human-readable name of a **Workout Type**, e.g. `Long Run`. */
export function formatWorkoutType(type: WorkoutType): string {
  return WORKOUT_TYPE_LABELS[type];
}

/** Formats an optional measurement with its unit, e.g. `152 bpm`, or `—` when absent. */
export function formatOptional(value: number | null | undefined, unit: string): string {
  return value === null || value === undefined ? MISSING : `${value} ${unit}`;
}

export type ActivitySource = ActivitySummaryDto['source'];

const SOURCE_LABELS: Readonly<Record<ActivitySource, string>> = {
  MANUAL: 'Manual',
  FIT_UPLOAD: 'FIT Upload',
  GARMIN: 'Garmin',
  STRAVA: 'Strava',
};

/** Every **Activity Source**, in display order. */
export const ACTIVITY_SOURCES = Object.keys(SOURCE_LABELS) as readonly ActivitySource[];

/** The human-readable name of an **Activity Source**, e.g. `FIT Upload`. */
export function formatSource(source: ActivitySource): string {
  return SOURCE_LABELS[source];
}

const countFormat = new Intl.NumberFormat('en-GB');

/** Formats a whole number with digit grouping, e.g. `2,000`. */
export function formatInteger(count: number): string {
  return countFormat.format(count);
}

/** Formats a count with its noun, pluralising as needed, e.g. `1 activity`, `2,000 activities`. */
export function formatCount(count: number, singular: string, plural: string): string {
  return `${formatInteger(count)} ${count === 1 ? singular : plural}`;
}

const elevationFormat = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 1 });

/** Formats an elevation gain in meters, e.g. `+12.5 m`, or `—` when absent. */
export function formatElevationGain(meters: number | null): string {
  return meters === null ? MISSING : `+${elevationFormat.format(meters)} m`;
}

/** Formats an ISO-8601 instant as a date and 24-hour time, e.g. `1 Mar 2026, 09:00`, in `timeZone`. */
export function formatDateTime(iso: string, timeZone?: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return MISSING;
  return formatInstant(date, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }, timeZone);
}

/** Formats a span of cumulative distance given in meters as kilometers, e.g. `0.12 – 1.12 km`. */
export function formatDistanceRangeKm(startMeters: number, endMeters: number): string {
  return `${kilometersFormat.format(startMeters / 1000)} – ${formatDistanceKm(endMeters / 1000)}`;
}

/** Formats a span of elapsed seconds, e.g. `00:10 – 04:10`. */
export function formatDurationRange(startSeconds: number, endSeconds: number): string {
  return `${formatDuration(startSeconds)} – ${formatDuration(endSeconds)}`;
}
