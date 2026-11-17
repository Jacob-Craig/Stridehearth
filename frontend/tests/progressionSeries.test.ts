import { describe, expect, it } from 'vitest';
import type {
  BenchmarkProgressionDto,
  PersonalRecordStepDto,
} from '../src/api/generated/types.gen';
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
