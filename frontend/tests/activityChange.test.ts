import { describe, expect, it } from 'vitest';
import type { ActivityChangeDto } from '../src/api/generated/types.gen';
import {
  activityChangeType,
  describeActivityChange,
  describeItemUpgrades,
} from '../src/lib/activityChange';

/** The one test of history wording: every change kind, as the history dialog and Import Batch outcomes show it. */
describe('describeActivityChange', () => {
  const cases: ReadonlyArray<readonly [string, ActivityChangeDto, string]> = [
    [
      'a manual create',
      { kind: 'CREATED', source: 'MANUAL', parserVersion: null, lapCount: 0 },
      'Logged manually',
    ],
    [
      'an import with Activity Details and Laps',
      { kind: 'CREATED', source: 'FIT_UPLOAD', parserVersion: 1, lapCount: 3 },
      'Imported from FIT file (Parser v1, 3 laps)',
    ],
    [
      'an import with a single Lap and no Activity Details',
      { kind: 'CREATED', source: 'FIT_UPLOAD', parserVersion: null, lapCount: 1 },
      'Imported from FIT file (1 lap)',
    ],
    [
      'a bare import from another source',
      { kind: 'CREATED', source: 'GARMIN', parserVersion: null, lapCount: 0 },
      'Imported from Garmin',
    ],
    [
      'an import from Strava',
      { kind: 'CREATED', source: 'STRAVA', parserVersion: 2, lapCount: 0 },
      'Imported from Strava (Parser v2)',
    ],
    [
      'an edit of every field',
      {
        kind: 'EDITED',
        fields: [
          { kind: 'TITLE', from: 'Run, with -> arrows', to: 'B' },
          { kind: 'SPORT', from: 'RUN', to: 'RIDE' },
          { kind: 'STARTED_AT', from: '2026-03-01T09:00:00Z', to: '2026-03-01T10:30:00Z' },
          { kind: 'DISTANCE', from: 5, to: 10.5 },
          { kind: 'TIME_TAKEN', from: 1500, to: 3725 },
          { kind: 'AVERAGE_PACE', from: 12, to: null },
          { kind: 'AVERAGE_HEART_RATE', from: null, to: 152 },
          { kind: 'CALORIES', from: 400, to: 410 },
        ],
      },
      'title: Run, with -> arrows → B, sport: Run → Ride, ' +
        'start time: 1 Mar 2026, 09:00 → 1 Mar 2026, 10:30, distance: 5.00 km → 10.50 km, ' +
        'time taken: 25:00 → 01:02:05, average pace: 05:00 /km → —, ' +
        'average heart rate: — → 152 bpm, calories: 400 kcal → 410 kcal',
    ],
    [
      'a batch edit',
      { kind: 'BATCH_EDITED', fields: [{ kind: 'SPORT', from: 'RUN', to: 'WALK' }] },
      'sport: Run → Walk',
    ],
    [
      'a Parser Version upgrade with refreshed Laps',
      {
        kind: 'UPGRADED',
        details: { kind: 'PARSER_VERSION_UPGRADED', from: 1, to: 2 },
        laps: { kind: 'REPLACED', count: 3 },
      },
      'Upgraded Activity Details (Parser v1 → v2); refreshed 3 laps',
    ],
    [
      'a Parser Version upgrade alone',
      {
        kind: 'UPGRADED',
        details: { kind: 'PARSER_VERSION_UPGRADED', from: 1, to: 2 },
        laps: null,
      },
      'Upgraded Activity Details (Parser v1 → v2)',
    ],
    [
      'an Activity Details backfill with its Laps',
      {
        kind: 'UPGRADED',
        details: { kind: 'BACKFILLED', parserVersion: 1 },
        laps: { kind: 'REPLACED', count: 1 },
      },
      'Backfilled Activity Details (Parser v1); backfilled 1 lap',
    ],
    [
      'a distance backfill',
      { kind: 'UPGRADED', details: { kind: 'DISTANCE_BACKFILLED', parserVersion: 1 }, laps: null },
      'Backfilled Activity Details distance (Parser v1)',
    ],
    [
      'a distance backfill with refreshed Laps',
      {
        kind: 'UPGRADED',
        details: { kind: 'DISTANCE_BACKFILLED', parserVersion: 1 },
        laps: { kind: 'REPLACED', count: 2 },
      },
      'Backfilled Activity Details distance (Parser v1); refreshed 2 laps',
    ],
    [
      'a single-Lap backfill',
      { kind: 'UPGRADED', details: null, laps: { kind: 'BACKFILLED', count: 1 } },
      'Backfilled 1 lap',
    ],
    [
      'a Laps backfill',
      { kind: 'UPGRADED', details: null, laps: { kind: 'BACKFILLED', count: 2000 } },
      'Backfilled 2,000 laps',
    ],
    [
      'a legacy entry, shown exactly as recorded',
      { kind: 'LEGACY', changeType: 'EDITED', description: 'title: A -> B, sport: RUN -> WALK' },
      'title: A -> B, sport: RUN -> WALK',
    ],
  ];

  it.each(cases)('describes %s', (_, change, expected) => {
    expect(describeActivityChange(change, 'UTC')).toBe(expected);
  });

  it('describes an Import Batch Item by its upgrades, or as a bare upgrade when it recorded none', () => {
    expect(
      describeItemUpgrades([
        { kind: 'UPGRADED', details: null, laps: { kind: 'BACKFILLED', count: 3 } },
        { kind: 'LEGACY', changeType: 'UPGRADED', description: 'Upgraded Parser Version 1 -> 2' },
      ]),
    ).toBe('Backfilled 3 laps; Upgraded Parser Version 1 -> 2');
    expect(describeItemUpgrades([])).toBe('Upgraded');
  });

  it('badges a legacy entry with the change type it was recorded under', () => {
    expect(activityChangeType({ kind: 'BATCH_EDITED', fields: [] })).toBe('BATCH_EDITED');
    expect(activityChangeType({ kind: 'LEGACY', changeType: 'UPGRADED', description: 'x' })).toBe(
      'UPGRADED',
    );
  });
});
