import { describe, expect, it } from 'vitest';
import type { ActivitySummaryDto } from '../src/api/generated/types.gen';
import {
  type ActivityFormValues,
  emptyActivityFormValues,
  formErrorsFromApi,
  formValuesFromActivity,
  parseDuration,
  toCreateRequest,
  toMetadataUpdateRequest,
} from '../src/lib/activityForm';

const validValues: ActivityFormValues = {
  title: '  Lunch Run ',
  sport: 'RUN',
  startedAt: '2026-05-02T12:30',
  distance: '8.5',
  duration: '45:00',
  averagePaceKmh: '11.33',
  averageHeartRate: '148',
  calories: '',
};

describe('parseDuration', () => {
  it.each([
    ['45:00', 2700],
    ['1:05:30', 3930],
    ['01:05:30', 3930],
    ['0:00:59', 59],
    [' 50:00 ', 3000],
  ])('parses %s as %i seconds', (input, seconds) => {
    expect(parseDuration(input)).toBe(seconds);
  });

  it.each(['', 'abc', '5', '1:60', '1:2:3:4', '-1:00', '00:00'])('rejects %j', (input) => {
    expect(parseDuration(input)).toBeNull();
  });
});

describe('toCreateRequest', () => {
  it('converts valid form values into a create request', () => {
    const result = toCreateRequest(validValues);

    expect(result).toEqual({
      kind: 'VALID',
      data: {
        title: 'Lunch Run',
        sport: 'RUN',
        startedAt: new Date(2026, 4, 2, 12, 30).toISOString(),
        distance: 8.5,
        timeTakenSeconds: 2700,
        averagePaceKmh: 11.33,
        averageHeartRate: 148,
        calories: null,
      },
    });
  });

  it('reports an error for each invalid field', () => {
    const result = toCreateRequest({
      ...validValues,
      title: '   ',
      startedAt: '',
      distance: '0',
      duration: 'soon',
      averageHeartRate: '-3',
      calories: '1.5',
    });

    if (result.kind !== 'INVALID') throw new Error('expected INVALID');
    expect(Object.keys(result.fieldErrors).sort()).toEqual([
      'averageHeartRate',
      'calories',
      'distance',
      'duration',
      'startedAt',
      'title',
    ]);
    expect(result.fieldErrors.title).toBe('Title cannot be empty');
    expect(result.fieldErrors.duration).toBe('Enter a duration as mm:ss or h:mm:ss');
  });
});

describe('toMetadataUpdateRequest', () => {
  it('sends only the title and sport', () => {
    expect(toMetadataUpdateRequest({ ...validValues, sport: 'HIKE' })).toEqual({
      kind: 'VALID',
      data: { title: 'Lunch Run', sport: 'HIKE' },
    });
  });

  it('rejects an empty title', () => {
    expect(toMetadataUpdateRequest({ ...validValues, title: '' })).toEqual({
      kind: 'INVALID',
      fieldErrors: { title: 'Title cannot be empty' },
    });
  });
});

describe('form values', () => {
  it('starts empty with RUN selected', () => {
    expect(emptyActivityFormValues()).toEqual({
      title: '',
      sport: 'RUN',
      startedAt: '',
      distance: '',
      duration: '',
      averagePaceKmh: '',
      averageHeartRate: '',
      calories: '',
    });
  });

  it('round-trips an existing activity into editable form values', () => {
    const activity: ActivitySummaryDto = {
      id: 3,
      title: 'Hill Repeats',
      sport: 'RUN',
      startedAt: new Date(2026, 0, 9, 6, 5).toISOString(),
      distance: 7.25,
      timeTakenSeconds: 3930,
      averagePaceKmh: null,
      averageHeartRate: 171,
      calories: null,
      source: 'MANUAL',
      hasProtectedMetrics: false,
    };

    const values = formValuesFromActivity(activity);

    expect(values).toEqual({
      title: 'Hill Repeats',
      sport: 'RUN',
      startedAt: '2026-01-09T06:05:00',
      distance: '7.25',
      duration: '01:05:30',
      averagePaceKmh: '',
      averageHeartRate: '171',
      calories: '',
    });
    expect(toCreateRequest(values)).toMatchObject({
      kind: 'VALID',
      data: { startedAt: activity.startedAt, timeTakenSeconds: 3930 },
    });
  });

  it('preserves the seconds of the start time so an unchanged edit does not rewrite it', () => {
    const startedAt = new Date(2026, 0, 9, 6, 5, 37).toISOString();
    const values = formValuesFromActivity({
      id: 4,
      title: 'Imported Run',
      sport: 'RUN',
      startedAt,
      distance: 5,
      timeTakenSeconds: 1500,
      averagePaceKmh: 12,
      averageHeartRate: null,
      calories: null,
      source: 'MANUAL',
      hasProtectedMetrics: false,
    });

    expect(values.startedAt).toBe('2026-01-09T06:05:37');
    expect(toCreateRequest({ ...values, title: 'Renamed' })).toMatchObject({
      kind: 'VALID',
      data: { title: 'Renamed', startedAt },
    });
  });
});

describe('formErrorsFromApi', () => {
  it('maps API field names onto form fields', () => {
    expect(
      formErrorsFromApi({
        timeTakenSeconds: 'Duration is required',
        distance: 'Distance is required',
      }),
    ).toEqual({ duration: 'Duration is required', distance: 'Distance is required' });
  });
});

describe('average speed', () => {
  it('describes an invalid average speed as a speed', () => {
    const result = toCreateRequest({ ...validValues, averagePaceKmh: '0' });

    if (result.kind !== 'INVALID') throw new Error('expected INVALID');
    expect(result.fieldErrors.averagePaceKmh).toBe('Average speed must be greater than zero');
  });
});
