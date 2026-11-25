/**
 * Pure helpers for the **Pace-Duration Curve**: the 90-day and All-Time curves of best Peak Efforts, the
 * Critical Speed (or Critical Power) model line, the shared x the chart plots them on, and their labels.
 */
import type {
  CriticalSpeedFitDto,
  PeakEffortDto,
  PeakEffortSlotDto,
} from '../../api/generated/types.gen';
import type { ColorScheme } from '../../lib/colorScheme';
import { formatPacePerKm } from '../../lib/format';

/** Grade-Adjusted speed (m/s, shown as pace) or running power (W). */
export type PdcMode = 'pace' | 'power';

/** Height of the chart's canvas; the React card reserves the same slot. */
export const PDC_PLOT_HEIGHT_PX = 260;

/** The fixed x range, in seconds: the shortest and longest Benchmark Durations. */
export const PDC_MIN_SECONDS = 5;
export const PDC_MAX_SECONDS = 3600;

/** The x ticks: one per Benchmark Duration. */
export const PDC_TICKS_SECONDS: readonly number[] = [
  5, 15, 30, 60, 120, 180, 300, 600, 1200, 1800, 3600,
];

/** The model is fitted from 2-minute peaks up, so its line is drawn only from there. */
const MODEL_FROM_SECONDS = 120;
const MODEL_SAMPLES = 60;

/** The colours of the three lines, shared by the uPlot adapter and the card's legend. */
export const PDC_PALETTE: Record<
  ColorScheme,
  { readonly recent: string; readonly allTime: string; readonly model: string }
> = {
  light: { recent: '#2563eb', allTime: '#64748b', model: '#ea580c' },
  dark: { recent: '#60a5fa', allTime: '#94a3b8', model: '#fb923c' },
};

/** A best Peak Effort placed on the curve. */
export interface PeakPoint extends PeakEffortDto {
  readonly durationSeconds: number;
}

export interface PeakCurves {
  /** Best over the last 90 days. */
  readonly recent: readonly PeakPoint[];
  readonly allTime: readonly PeakPoint[];
}

export interface ModelPoint {
  readonly durationSeconds: number;
  readonly value: number;
}

/** The curves on one ascending x, as uPlot needs them: `null` where a curve has no point. */
export interface AlignedPdcData {
  readonly x: number[];
  readonly recent: (number | null)[];
  readonly allTime: (number | null)[];
  readonly model: (number | null)[];
}

/** One point per slot that holds a Peak Effort, shortest duration first. */
export function peakCurves(slots: readonly PeakEffortSlotDto[]): PeakCurves {
  const points = (pick: (slot: PeakEffortSlotDto) => PeakEffortDto | null): PeakPoint[] =>
    [...slots]
      .sort((a, b) => a.durationSeconds - b.durationSeconds)
      .flatMap((slot) => {
        const effort = pick(slot);
        return effort ? [{ ...effort, durationSeconds: slot.durationSeconds }] : [];
      });
  return { recent: points((s) => s.recent), allTime: points((s) => s.allTime) };
}

/** The fit's published model, or `null` when it has none. */
function publishedModel(
  fit: CriticalSpeedFitDto | null,
): { critical: number; reserve: number } | null {
  if (fit?.status !== 'MODEL' || fit.criticalValue === null || fit.reserveCapacity === null) {
    return null;
  }
  return { critical: fit.criticalValue, reserve: fit.reserveCapacity };
}

/**
 * The model `v(t) = CS + D′/t` (or `P(t) = CP + W′/t`) sampled at log-spaced durations from 2 to 60
 * minutes, or `null` unless the fit published a model.
 */
export function modelCurve(fit: CriticalSpeedFitDto | null): ModelPoint[] | null {
  const model = publishedModel(fit);
  if (!model) return null;
  const ratio = PDC_MAX_SECONDS / MODEL_FROM_SECONDS;
  return Array.from({ length: MODEL_SAMPLES }, (_, i) => {
    const durationSeconds = MODEL_FROM_SECONDS * ratio ** (i / (MODEL_SAMPLES - 1));
    return { durationSeconds, value: model.critical + model.reserve / durationSeconds };
  });
}

export function alignedPdcData(
  curves: PeakCurves,
  model: readonly ModelPoint[] | null,
): AlignedPdcData {
  const at = (points: readonly { durationSeconds: number; value: number }[]) =>
    new Map(points.map((p) => [p.durationSeconds, p.value]));
  const recent = at(curves.recent);
  const allTime = at(curves.allTime);
  const modelAt = at(model ?? []);
  const x = [...new Set([...recent.keys(), ...allTime.keys(), ...modelAt.keys()])].sort(
    (a, b) => a - b,
  );
  const column = (values: Map<number, number>) => x.map((d) => values.get(d) ?? null);
  return { x, recent: column(recent), allTime: column(allTime), model: column(modelAt) };
}

/** The peak nearest `durationSeconds` on the chart's log duration axis, or `null` on an empty curve. */
export function nearestPeak(
  points: readonly PeakPoint[],
  durationSeconds: number,
): PeakPoint | null {
  let best: PeakPoint | null = null;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const point of points) {
    const distance = Math.abs(Math.log(point.durationSeconds / durationSeconds));
    if (distance < bestDistance) {
      best = point;
      bestDistance = distance;
    }
  }
  return best;
}

/** `5s`, `1m`, `60m`. */
export function durationTickLabel(seconds: number): string {
  return seconds < 60 ? `${seconds}s` : `${Math.round(seconds / 60)}m`;
}

/** A curve value: Grade-Adjusted speed as pace per km, or power in watts. */
export function formatPdcValue(mode: PdcMode, value: number): string {
  return mode === 'pace' ? formatPacePerKm(value * 3.6) : `${Math.round(value)} W`;
}

/** `CS 04:10 /km · D′ 240 m` or `CP 300 W · W′ 20.0 kJ`, or `null` when the fit has no model. */
export function formatModelLegend(mode: PdcMode, fit: CriticalSpeedFitDto | null): string | null {
  const model = publishedModel(fit);
  if (!model) return null;
  return mode === 'pace'
    ? `CS ${formatPdcValue('pace', model.critical)} · D′ ${Math.round(model.reserve)} m`
    : `CP ${formatPdcValue('power', model.critical)} · W′ ${(model.reserve / 1000).toFixed(1)} kJ`;
}
