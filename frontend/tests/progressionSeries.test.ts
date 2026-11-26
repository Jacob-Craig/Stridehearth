import { describe, expect, it } from 'vitest';
import type {
  BenchmarkProgressionDto,
  PersonalRecordStepDto,
} from '../src/api/generated/types.gen';
import { DATE_TICK_SPACE_PX, dateTickLabels } from '../src/features/charts/dateAxis';
import {
  defaultBenchmark,
  progressionSteps,
} from '../src/features/pr-progression/progressionSeries';

const step = (activityId: number, startedAt: string, seconds: number): PersonalRecordStepDto => ({
  activityId,
  activityTitle: `Run ${activityId}`,
  activityStartedAt: startedAt,
  elapsedTimeSeconds: seconds,
});

const progression = (
  benchmarkDistance: BenchmarkProgressionDto['benchmarkDistance'],
  distanceMeters: number,
  records: PersonalRecordStepDto[],
): BenchmarkProgressionDto => ({
  benchmarkDistance,
  benchmarkName: benchmarkDistance,
  distanceMeters,
  records,
});

describe('defaultBenchmark', () => {
  it('picks the longest Benchmark Distance that has a record', () => {
    const progressions = [
      progression('FOUR_HUNDRED_METERS', 400, [step(1, '2026-01-01T08:00:00Z', 90)]),
      progression('FIVE_KILOMETERS', 5000, [step(1, '2026-01-01T08:00:00Z', 1500)]),
      progression('TEN_KILOMETERS', 10000, []),
    ];

    expect(defaultBenchmark(progressions)).toBe('FIVE_KILOMETERS');
  });

  it('is null when no distance has a record', () => {
    expect(defaultBenchmark([progression('FIVE_KILOMETERS', 5000, [])])).toBeNull();
  });
});

describe('progressionSteps', () => {
  // 2026-01-01T08:00:00Z, 2026-02-01T08:00:00Z and 2026-03-01T00:00:00Z in epoch seconds.
  const jan = 1_767_254_400;
  const feb = 1_769_932_800;
  const mar = 1_772_323_200;

  it('plots each record at its start, holding the last one until now', () => {
    const records = [step(1, '2026-01-01T08:00:00Z', 1600), step(2, '2026-02-01T08:00:00Z', 1550)];

    expect(progressionSteps(records, mar * 1000)).toEqual({
      x: [jan, feb, mar],
      y: [1600, 1550, 1550],
    });
  });

  it('adds no extension when the last record is not in the past', () => {
    const records = [step(1, '2026-01-01T08:00:00Z', 1600)];

    expect(progressionSteps(records, jan * 1000)).toEqual({ x: [jan], y: [1600] });
  });

  it('is empty without records', () => {
    expect(progressionSteps([], mar * 1000)).toEqual({ x: [], y: [] });
  });
});

describe('dateTickLabels', () => {
  const at = (iso: string) => Date.parse(iso) / 1000;

  it('labels month ticks in the zone uPlot placed them in, not UTC', () => {
    // uPlot puts month ticks at local midnight: 1 Mar in Berlin is 28 Feb 23:00 UTC.
    const ticks = [at('2026-01-31T23:00:00Z'), at('2026-02-28T23:00:00Z')];
    expect(dateTickLabels(ticks, 'Europe/Berlin')).toEqual(['Feb 2026', 'Mar 2026']);
  });

  it('labels ticks under a month apart by day', () => {
    const ticks = [
      at('2026-11-02T00:00:00Z'),
      at('2026-11-09T00:00:00Z'),
      at('2026-11-16T00:00:00Z'),
    ];
    expect(dateTickLabels(ticks, 'UTC')).toEqual(['2 Nov', '9 Nov', '16 Nov']);
  });

  it('labels a lone tick by month', () => {
    expect(dateTickLabels([at('2026-03-01T00:00:00Z')], 'UTC')).toEqual(['Mar 2026']);
  });
});

describe('DATE_TICK_SPACE_PX', () => {
  // uPlot's axis font is 12px; its widest glyphs here average under 7px, and labels need a gap.
  const AXIS_CHAR_WIDTH_PX = 7;
  const LABEL_GAP_PX = 16;

  it('spaces date ticks wide enough that the longest month label cannot touch its neighbour', () => {
    const monthStarts = Array.from({ length: 12 }, (_, m) => Date.UTC(2026, m, 15) / 1000);
    const widest = Math.max(...dateTickLabels(monthStarts, 'UTC').map((label) => label.length));
    expect(DATE_TICK_SPACE_PX).toBeGreaterThanOrEqual(widest * AXIS_CHAR_WIDTH_PX + LABEL_GAP_PX);
  });
});
