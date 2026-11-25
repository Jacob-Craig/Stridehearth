import type { RaceDistance, RacePrognosisDayDto } from '../../api/generated/types.gen';
/**
 * Pure helpers for the **Race Prognosis** card: the four race distances, their predicted paces over time on
 * one axis for the trend chart, finish-time labels, and the race-day conditions form.
 */
import type { RaceConditions } from '../../api/racePrognosis';
import type { ColorScheme } from '../../lib/colorScheme';
import { pad2 } from '../../lib/format';
import { dayStartSeconds } from '../charts/dateAxis';

/** Height of the trend chart's canvas; the card reserves the same slot. */
export const PROGNOSIS_PLOT_HEIGHT_PX = 240;

type DaySecondsKey = 'fiveKSeconds' | 'tenKSeconds' | 'halfSeconds' | 'marathonSeconds';

export interface RaceDistanceInfo {
  readonly distance: RaceDistance;
  readonly label: string;
  readonly meters: number;
  /** The field of a trend day that holds this distance's finish time. */
  readonly dayKey: DaySecondsKey;
}

/** The distances a Race Prognosis predicts, shortest first, as the API returns them. */
export const RACE_DISTANCES: readonly RaceDistanceInfo[] = [
  { distance: 'FIVE_K', label: '5K', meters: 5000, dayKey: 'fiveKSeconds' },
  { distance: 'TEN_K', label: '10K', meters: 10000, dayKey: 'tenKSeconds' },
  {
    distance: 'HALF_MARATHON',
    label: 'Half Marathon',
    meters: 21097.5,
    dayKey: 'halfSeconds',
  },
  {
    distance: 'MARATHON',
    label: 'Marathon',
    meters: 42195,
    dayKey: 'marathonSeconds',
  },
];

/** One colour per distance, shared by the uPlot adapter and the card's legend. */
export const PROGNOSIS_PALETTE: Record<ColorScheme, Record<RaceDistance, string>> = {
  light: {
    FIVE_K: '#2563eb',
    TEN_K: '#059669',
    HALF_MARATHON: '#d97706',
    MARATHON: '#dc2626',
  },
  dark: {
    FIVE_K: '#60a5fa',
    TEN_K: '#34d399',
    HALF_MARATHON: '#fbbf24',
    MARATHON: '#f87171',
  },
};

/** The trend as uPlot plots it: one x per day, and per distance the predicted pace in seconds per km. */
export interface AlignedPrognosisData {
  readonly x: number[];
  readonly paces: Record<RaceDistance, number[]>;
}

/**
 * Finish times differ about tenfold between the 5K and the Marathon, so the chart plots each distance's
 * predicted pace, which shares one scale.
 */
export function alignedPrognosisData(days: readonly RacePrognosisDayDto[]): AlignedPrognosisData {
  const paces = Object.fromEntries(
    RACE_DISTANCES.map(({ distance, meters, dayKey }) => [
      distance,
      days.map((day) => day[dayKey] / (meters / 1000)),
    ]),
  ) as Record<RaceDistance, number[]>;
  return { x: days.map((day) => dayStartSeconds(day.day)), paces };
}

/** A finish time as `h:mm:ss`, or `m:ss` under an hour, e.g. `3:10:40` or `19:57`. */
export function formatFinishTime(seconds: number): string {
  const total = Math.round(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = pad2(total % 60);
  return hours > 0 ? `${hours}:${pad2(minutes)}:${rest}` : `${minutes}:${rest}`;
}

/** A pace in seconds per km as `m:ss /km`, e.g. `4:00 /km`. */
export function formatPaceSeconds(secondsPerKm: number): string {
  const total = Math.round(secondsPerKm);
  return `${Math.floor(total / 60)}:${pad2(total % 60)} /km`;
}

/** The accepted range of each race-day condition, as the API enforces it. */
export const CONDITION_LIMITS = {
  temperatureC: { min: -30, max: 50 },
  elevationGainM: { min: 0, max: 5000 },
} as const;

export type ConditionsForm = Readonly<Record<keyof RaceConditions, string>>;

export type ParsedConditions =
  | { readonly ok: true; readonly conditions: RaceConditions }
  | {
      readonly ok: false;
      readonly errors: Partial<Record<keyof RaceConditions, string>>;
    };

/** Reads the race-day conditions form: a blank field is no condition; anything else must be in range. */
export function parseConditions(form: ConditionsForm): ParsedConditions {
  const conditions: { temperatureC?: number; elevationGainM?: number } = {};
  const errors: Partial<Record<keyof RaceConditions, string>> = {};
  for (const field of ['temperatureC', 'elevationGainM'] as const) {
    const text = form[field].trim();
    if (text === '') continue;
    const value = Number(text);
    const { min, max } = CONDITION_LIMITS[field];
    if (!Number.isFinite(value) || value < min || value > max) {
      errors[field] = `Enter a number from ${min} to ${max}`;
    } else {
      conditions[field] = value;
    }
  }
  return Object.keys(errors).length > 0 ? { ok: false, errors } : { ok: true, conditions };
}
