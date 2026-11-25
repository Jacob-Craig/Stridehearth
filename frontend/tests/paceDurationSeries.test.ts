import { describe, expect, it } from 'vitest';
import type {
  CriticalSpeedFitDto,
  PeakEffortDto,
  PeakEffortSlotDto,
} from '../src/api/generated/types.gen';
import {
  PDC_TICKS_SECONDS,
  alignedPdcData,
  durationTickLabel,
  formatModelLegend,
  formatPdcValue,
  modelCurve,
  nearestPeak,
  peakCurves,
} from '../src/features/pace-duration/paceDurationSeries';

const peak = (activityId: number, value: number): PeakEffortDto => ({
  value,
  activityId,
  activityTitle: `Run ${activityId}`,
  activityStartedAt: '2026-10-01T08:00:00Z',
});

const slot = (
  benchmarkDuration: PeakEffortSlotDto['benchmarkDuration'],
  durationSeconds: number,
  recent: PeakEffortDto | null,
  allTime: PeakEffortDto | null,
): PeakEffortSlotDto => ({ benchmarkDuration, durationSeconds, recent, allTime });

const SLOTS: PeakEffortSlotDto[] = [
  slot('FIVE_SECONDS', 5, peak(1, 6.0), peak(2, 6.2)),
  slot('FIFTEEN_SECONDS', 15, null, null),
  slot('ONE_MINUTE', 60, null, peak(3, 5.5)),
  slot('TWO_MINUTES', 120, peak(4, 5.0), peak(4, 5.0)),
];

const fit = (overrides: Partial<CriticalSpeedFitDto>): CriticalSpeedFitDto => ({
  status: 'MODEL',
  criticalValue: 4.0,
  reserveCapacity: 240,
  pointCount: 3,
  points: [],
  rSquared: 0.99,
  ...overrides,
});

describe('peakCurves', () => {
  it('maps each slot to a point on the 90-day and All-Time curves, skipping empty slots', () => {
    const curves = peakCurves(SLOTS);

    expect(curves.recent.map((p) => [p.durationSeconds, p.value, p.activityId])).toEqual([
      [5, 6.0, 1],
      [120, 5.0, 4],
    ]);
    expect(curves.allTime.map((p) => [p.durationSeconds, p.value, p.activityId])).toEqual([
      [5, 6.2, 2],
      [60, 5.5, 3],
      [120, 5.0, 4],
    ]);
    expect(curves.allTime[1]!.activityTitle).toBe('Run 3');
  });

  it('is empty when no slot holds a Peak Effort', () => {
    expect(peakCurves([slot('FIVE_SECONDS', 5, null, null)])).toEqual({ recent: [], allTime: [] });
  });
});

describe('modelCurve', () => {
  it('samples CS + D′/t at 60 log-spaced durations from 2 to 60 minutes', () => {
    const curve = modelCurve(fit({ criticalValue: 4.0, reserveCapacity: 240 }))!;

    expect(curve).toHaveLength(60);
    expect(curve[0]!.durationSeconds).toBeCloseTo(120, 9);
    expect(curve.at(-1)!.durationSeconds).toBeCloseTo(3600, 9);
    // 120 s: 4 + 240/120 = 6; 3600 s: 4 + 240/3600 = 4.0667.
    expect(curve[0]!.value).toBeCloseTo(6.0, 9);
    expect(curve.at(-1)!.value).toBeCloseTo(4.066_667, 5);
    // Equal ratios between neighbours: (3600/120)^(1/59).
    const ratio = 30 ** (1 / 59);
    expect(curve[1]!.durationSeconds / curve[0]!.durationSeconds).toBeCloseTo(ratio, 9);
    for (const point of curve) {
      expect(point.value).toBeCloseTo(4 + 240 / point.durationSeconds, 9);
    }
  });

  it('draws no model unless the fit published one', () => {
    expect(
      modelCurve(
        fit({ status: 'INSUFFICIENT_POINTS', criticalValue: null, reserveCapacity: null }),
      ),
    ).toBeNull();
    expect(modelCurve(fit({ status: 'POOR_FIT', criticalValue: null }))).toBeNull();
    expect(modelCurve(null)).toBeNull();
  });
});

describe('alignedPdcData', () => {
  it('puts every curve on one shared, ascending x with nulls where a curve has no point', () => {
    const data = alignedPdcData(peakCurves(SLOTS), null);

    expect(data).toEqual({
      x: [5, 60, 120],
      recent: [6.0, null, 5.0],
      allTime: [6.2, 5.5, 5.0],
      model: [null, null, null],
    });
  });

  it('merges the model samples into the same x, sharing the 2-minute point', () => {
    const model = modelCurve(fit({}))!;
    const data = alignedPdcData(peakCurves(SLOTS), model);

    expect(data.x).toHaveLength(2 + 60);
    expect(data.x.slice(0, 3)).toEqual([5, 60, 120]);
    expect(data.recent[2]).toBe(5.0);
    expect(data.model[2]).toBeCloseTo(6.0, 9);
    expect(data.model.slice(0, 2)).toEqual([null, null]);
    expect(data.allTime.slice(3).every((v) => v === null)).toBe(true);
  });
});

describe('nearestPeak', () => {
  const allTime = peakCurves(SLOTS).allTime;

  it('picks the peak nearest on the log duration axis', () => {
    expect(nearestPeak(allTime, 50)?.activityId).toBe(3);
    expect(nearestPeak(allTime, 9)?.activityId).toBe(2);
    // Just below the log midpoint of 5 and 60 s: 17.3 / 5 = 3.46 < 60 / 17.3 = 3.47.
    expect(nearestPeak(allTime, 17.3)?.durationSeconds).toBe(5);
    expect(nearestPeak(allTime, 3600)?.durationSeconds).toBe(120);
  });

  it('finds nothing on an empty curve', () => {
    expect(nearestPeak([], 60)).toBeNull();
  });
});

describe('labels', () => {
  it('labels the eleven Benchmark Duration ticks', () => {
    expect(PDC_TICKS_SECONDS.map(durationTickLabel)).toEqual([
      '5s',
      '15s',
      '30s',
      '1m',
      '2m',
      '3m',
      '5m',
      '10m',
      '20m',
      '30m',
      '60m',
    ]);
  });

  it('shows speed as pace per km and power in watts', () => {
    // 5 m/s = 18 km/h = 200 s/km.
    expect(formatPdcValue('pace', 5)).toBe('03:20 /km');
    expect(formatPdcValue('power', 250.4)).toBe('250 W');
  });

  it('gives the model parameters in the legend', () => {
    // 4 m/s = 14.4 km/h = 250 s/km.
    expect(formatModelLegend('pace', fit({ criticalValue: 4, reserveCapacity: 240.4 }))).toBe(
      'CS 04:10 /km · D′ 240 m',
    );
    expect(formatModelLegend('power', fit({ criticalValue: 300.2, reserveCapacity: 20_040 }))).toBe(
      'CP 300 W · W′ 20.0 kJ',
    );
    expect(formatModelLegend('pace', fit({ status: 'NON_PHYSICAL' }))).toBeNull();
  });
});
