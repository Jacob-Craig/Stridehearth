import { queryOptions } from '@tanstack/react-query';
import { validatedFetch } from './client';
import { dashboardQueryKey } from './dashboard';
import type { RacePrognosisResponseDto } from './generated/types.gen';
import { zGetRacePrognosisResponse } from './generated/zod.gen';

/** Race-day conditions for **Course Derating**; an absent one costs nothing. */
export interface RaceConditions {
  readonly temperatureC?: number;
  readonly elevationGainM?: number;
}

/** The athlete's **Race Prognosis** today, derated for any race-day `conditions`, and its daily trend. */
export async function getRacePrognosis(
  conditions: RaceConditions = {},
): Promise<RacePrognosisResponseDto> {
  const params = new URLSearchParams();
  if (conditions.temperatureC !== undefined) {
    params.set('temperatureC', String(conditions.temperatureC));
  }
  if (conditions.elevationGainM !== undefined) {
    params.set('elevationGainM', String(conditions.elevationGainM));
  }
  const query = params.toString();
  return validatedFetch(
    zGetRacePrognosisResponse,
    `/api/athlete/race-prognosis${query ? `?${query}` : ''}`,
  );
}

/**
 * Under the athlete's Dashboard key, so a change to their Activities or Thresholds, which invalidates the
 * Dashboard, also refreshes their **Race Prognosis**, and one athlete's is never served to the next. Each set
 * of race-day conditions is its own entry.
 */
export const racePrognosisQueryKey = (
  userId: number | undefined,
  conditions: RaceConditions = {},
) =>
  [
    ...dashboardQueryKey(userId),
    'race-prognosis',
    {
      temperatureC: conditions.temperatureC,
      elevationGainM: conditions.elevationGainM,
    },
  ] as const;

export const racePrognosisQueryOptions = (
  userId: number | undefined,
  conditions: RaceConditions = {},
) =>
  queryOptions({
    queryKey: racePrognosisQueryKey(userId, conditions),
    queryFn: () => getRacePrognosis(conditions),
  });
