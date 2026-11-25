import { describe, expect, it } from 'vitest';
import type { RacePredictionDto, RaceSplitPlanResponseDto } from '../src/api/generated/types.gen';
import {
  formatGoalTime,
  initialGoalSeconds,
  parseGoalTime,
  predictedGoal,
  splitsTsv,
} from '../src/features/race-split-plan/splitPlan';

const prediction = (
  distance: RacePredictionDto['distance'],
  meters: number,
  predictedSeconds: number,
): RacePredictionDto => ({
  distance,
  meters,
  vdotSeconds: predictedSeconds,
  predictedSeconds,
  enduranceFactor: 1,
  courseFactor: 1,
});

const PREDICTIONS = [
  prediction('FIVE_K', 5000, 1200),
  prediction('TEN_K', 10000, 2500),
  prediction('HALF_MARATHON', 21097.5, 5400),
  prediction('MARATHON', 42195, 11400),
];

describe('predictedGoal', () => {
  it("scales the nearest race distance's predicted time to the course by Riegel's exponent 1.06", () => {
    // 5400 × (21300 / 21097.5)^1.06 = 5454.96 s, rounded.
    expect(predictedGoal(21300, PREDICTIONS)).toEqual({ distance: 'HALF_MARATHON', seconds: 5455 });
  });

  it('takes the predicted time unscaled on a course of exactly the race distance', () => {
    expect(predictedGoal(10000, PREDICTIONS)).toEqual({ distance: 'TEN_K', seconds: 2500 });
  });

  it('makes a course shorter than the race distance faster', () => {
    // 1200 × (4600 / 5000)^1.06 = 1098.49 s.
    expect(predictedGoal(4600, PREDICTIONS)).toEqual({ distance: 'FIVE_K', seconds: 1098 });
  });

  it('suggests nothing for a course more than 10% from every race distance', () => {
    // 30 km is 29% short of the Marathon and 42% over the Half.
    expect(predictedGoal(30000, PREDICTIONS)).toBeNull();
    expect(predictedGoal(4400, PREDICTIONS)).toBeNull();
  });

  it('suggests nothing without predictions', () => {
    expect(predictedGoal(21300, [])).toBeNull();
  });
});

describe('Goal Time input', () => {
  it('reads h:mm:ss, mm:ss and m:ss', () => {
    expect(parseGoalTime('1:30:00')).toEqual({ ok: true, seconds: 5400 });
    expect(parseGoalTime(' 45:07 ')).toEqual({ ok: true, seconds: 2707 });
    expect(parseGoalTime('3:05:59')).toEqual({ ok: true, seconds: 11159 });
  });

  it('refuses malformed times and times outside a minute to a day', () => {
    expect(parseGoalTime('')).toMatchObject({ ok: false });
    expect(parseGoalTime('fast')).toMatchObject({ ok: false });
    expect(parseGoalTime('1:75')).toMatchObject({ ok: false });
    expect(parseGoalTime('0:59')).toMatchObject({ ok: false });
    expect(parseGoalTime('24:00:01')).toMatchObject({ ok: false });
    expect(parseGoalTime('24:00:00')).toEqual({ ok: true, seconds: 86400 });
  });

  it('writes a Goal Time as h:mm:ss', () => {
    expect(formatGoalTime(5455)).toBe('1:30:55');
    expect(formatGoalTime(1100)).toBe('0:18:20');
  });

  it("starts from the Activity's own time, kept within a minute and a day", () => {
    expect(initialGoalSeconds(2700)).toBe(2700);
    expect(initialGoalSeconds(20)).toBe(60);
    expect(initialGoalSeconds(100_000)).toBe(86400);
  });
});

describe('splitsTsv', () => {
  it('writes one tab-separated row per split under a header', () => {
    const plan: RaceSplitPlanResponseDto = {
      activityId: 9,
      courseDistanceM: 1400,
      goalSeconds: 380,
      unit: 'KM',
      biasPct: 0,
      elevationGainM: 12,
      profile: [],
      splits: [
        {
          index: 1,
          startM: 0,
          endM: 1000,
          distanceM: 1000,
          targetSeconds: 280.4,
          paceSecondsPerUnit: 280.4,
          cumulativeSeconds: 280.4,
          elevationGainM: 12,
          elevationLossM: 0.4,
          avgGradePct: 1.16,
        },
        {
          index: 2,
          startM: 1000,
          endM: 1400,
          distanceM: 400,
          targetSeconds: 99.6,
          paceSecondsPerUnit: 249,
          cumulativeSeconds: 380,
          elevationGainM: null,
          elevationLossM: null,
          avgGradePct: null,
        },
      ],
    };

    expect(splitsTsv(plan)).toBe(
      [
        'Split\tTo (km)\tTarget\tPace (/km)\tCumulative\tGain (m)\tLoss (m)\tGrade (%)',
        '1\t1.00\t4:40\t4:40\t4:40\t12\t0\t1.2',
        '2\t1.40\t1:40\t4:09\t6:20\t\t\t',
      ].join('\n'),
    );
  });
});
