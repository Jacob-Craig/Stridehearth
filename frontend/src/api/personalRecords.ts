import { queryOptions } from '@tanstack/react-query';
import { validatedFetch } from './client';
import { dashboardQueryKey } from './dashboard';
import type { PersonalRecordProgressionResponseDto } from './generated/types.gen';
import { zGetPersonalRecordProgressionResponse } from './generated/zod.gen';

export async function getPersonalRecordProgression(): Promise<PersonalRecordProgressionResponseDto> {
  return validatedFetch(
    zGetPersonalRecordProgressionResponse,
    '/api/athlete/personal-record-progression',
  );
}

/**
 * Under the athlete's Dashboard key, so it is never served to another athlete and refreshes whenever
 * their Activities change, as the Personal Records do.
 */
export const personalRecordProgressionQueryOptions = (userId: number | undefined) =>
  queryOptions({
    queryKey: [...dashboardQueryKey(userId), 'progression'] as const,
    queryFn: getPersonalRecordProgression,
  });
