/**
 * Pure helpers for the **Race Split Plan** page: the Goal Time input, the Goal Time suggested from the
 * athlete's **Race Prognosis**, split labels per unit, and the splits as tab-separated text to copy.
 */
import type {
  RaceDistance,
  RacePredictionDto,
  RaceSplitDto,
  RaceSplitPlanResponseDto,
} from '../../api/generated/types.gen';
import { pad2 } from '../../lib/format';

export type SplitUnit = RaceSplitPlanResponseDto['unit'];

/** The Goal Times the API accepts, in s: a minute to a day. */
export const GOAL_LIMITS = { min: 60, max: 86400 } as const;

/** The **Split Bias** the API accepts either way, in percent, and the slider's step. */
export const MAX_BIAS_PCT = 5;
export const BIAS_STEP_PCT = 0.5;

/** Height of the elevation profile's canvas; the page reserves the same slot. */
export const PROFILE_PLOT_HEIGHT_PX = 160;

/** Riegel's exponent: time grows as distance to this power. */
const RIEGEL_EXPONENT = 1.06;

/** How far, as a fraction, a course may be from a race distance for its prediction to be scaled to it. */
const MAX_DISTANCE_MISMATCH = 0.1;

export const UNIT_LABELS: Record<SplitUnit, { readonly short: string; readonly meters: number }> = {
  KM: { short: 'km', meters: 1000 },
  MILE: { short: 'mi', meters: 1609.344 },
};

/** A Goal Time suggested from the Race Prognosis, and the race distance it was scaled from. */
export interface SuggestedGoal {
  readonly distance: RaceDistance;
  readonly seconds: number;
}

/**
 * The predicted time of the race distance nearest the course, scaled to the course's length by Riegel's
 * formula `t × (courseM / raceM)^1.06`, in whole seconds; `null` when no race distance is within 10%.
 */
export function predictedGoal(
  courseM: number,
  predictions: readonly RacePredictionDto[],
): SuggestedGoal | null {
  let nearest: RacePredictionDto | null = null;
  for (const prediction of predictions) {
    if (
      nearest === null ||
      Math.abs(prediction.meters - courseM) < Math.abs(nearest.meters - courseM)
    ) {
      nearest = prediction;
    }
  }
  if (
    nearest === null ||
    Math.abs(courseM - nearest.meters) > nearest.meters * MAX_DISTANCE_MISMATCH
  ) {
    return null;
  }
  return {
    distance: nearest.distance,
    seconds: Math.round(nearest.predictedSeconds * (courseM / nearest.meters) ** RIEGEL_EXPONENT),
  };
}

export type ParsedGoalTime =
  | { readonly ok: true; readonly seconds: number }
  | { readonly ok: false; readonly error: string };

const GOAL_TIME_PATTERN = /^(?:(\d+):)?(\d{1,2}):(\d{2})$/;

/** Reads a Goal Time typed as `h:mm:ss` or `m:ss`, from a minute to a day. */
export function parseGoalTime(text: string): ParsedGoalTime {
  const match = GOAL_TIME_PATTERN.exec(text.trim());
  const error = 'Enter a time as h:mm:ss, from 0:01:00 to 24:00:00';
  if (!match) return { ok: false, error };
  const [, hours = '0', minutes = '0', seconds = '0'] = match;
  if (Number(seconds) > 59 || (match[1] !== undefined && Number(minutes) > 59)) {
    return { ok: false, error };
  }
  const total = Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds);
  if (total < GOAL_LIMITS.min || total > GOAL_LIMITS.max) return { ok: false, error };
  return { ok: true, seconds: total };
}

/** A Goal Time as `h:mm:ss`, e.g. `1:30:55` or `0:18:20`. */
export function formatGoalTime(seconds: number): string {
  const total = Math.round(seconds);
  return `${Math.floor(total / 3600)}:${pad2(Math.floor((total % 3600) / 60))}:${pad2(total % 60)}`;
}

/** The Goal Time a plan starts from: the Activity's own time, within the accepted range. */
export function initialGoalSeconds(activitySeconds: number): number {
  return Math.min(GOAL_LIMITS.max, Math.max(GOAL_LIMITS.min, Math.round(activitySeconds)));
}

/** A split or cumulative time as `m:ss`, or `h:mm:ss` from an hour. */
export function formatSplitTime(seconds: number): string {
  const total = Math.round(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = pad2(total % 60);
  return hours > 0 ? `${hours}:${pad2(minutes)}:${rest}` : `${minutes}:${rest}`;
}

/** Distance from the start in the plan's unit, to two decimals, e.g. `1.00` or `6.21`. */
export function formatUnitDistance(meters: number, unit: SplitUnit): string {
  return (meters / UNIT_LABELS[unit].meters).toFixed(2);
}

/** A split's net grade, e.g. `+5.0%`, `-2.3%` or `0.0%`. */
export function formatGrade(percent: number): string {
  const rounded = Math.round(percent * 10) / 10;
  return `${rounded > 0 ? '+' : ''}${(rounded === 0 ? 0 : rounded).toFixed(1)}%`;
}

const optionalNumber = (value: number | null, digits: number) =>
  value === null ? '' : value.toFixed(digits);

/** The splits as tab-separated text with a header, ready to paste into a spreadsheet. */
export function splitsTsv(plan: RaceSplitPlanResponseDto): string {
  const unit = UNIT_LABELS[plan.unit].short;
  const header = [
    'Split',
    `To (${unit})`,
    'Target',
    `Pace (/${unit})`,
    'Cumulative',
    'Gain (m)',
    'Loss (m)',
    'Grade (%)',
  ];
  const row = (split: RaceSplitDto) => [
    String(split.index),
    formatUnitDistance(split.endM, plan.unit),
    formatSplitTime(split.targetSeconds),
    formatSplitTime(split.paceSecondsPerUnit),
    formatSplitTime(split.cumulativeSeconds),
    optionalNumber(split.elevationGainM, 0),
    optionalNumber(split.elevationLossM, 0),
    optionalNumber(split.avgGradePct, 1),
  ];
  return [header, ...plan.splits.map(row)].map((cells) => cells.join('\t')).join('\n');
}

/** A plan's elevation profile as the chart draws it. */
export interface ElevationProfileData {
  /** Distance from the start, in the plan's unit (km or mi). */
  readonly x: number[];
  /** Altitude, in m. */
  readonly altitude: number[];
  /** Where each split but the last ends, in the plan's unit. */
  readonly boundaries: readonly number[];
  /** `km` or `mi`. */
  readonly unitLabel: string;
}

/** The profile in the plan's unit, with a boundary at the end of every split but the last. */
export function elevationProfileData(plan: RaceSplitPlanResponseDto): ElevationProfileData {
  const { meters, short } = UNIT_LABELS[plan.unit];
  return {
    x: plan.profile.map((point) => point.distanceM / meters),
    altitude: plan.profile.map((point) => point.altitudeM),
    boundaries: plan.splits.slice(0, -1).map((split) => split.endM / meters),
    unitLabel: short,
  };
}
