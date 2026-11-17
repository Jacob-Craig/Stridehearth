import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { currentUserQueryOptions } from '../auth/AuthContext';
import { apiClient, validatedFetch } from './client';
import type {
  AthleteThresholdsResponseDto,
  ThresholdEntryDto,
  ThresholdEntryRequestDto,
  UserResponseDto,
} from './generated/types.gen';
import {
  zAcceptThresholdProposalResponse,
  zGetAthleteThresholdsResponse,
  zRecordAthleteThresholdResponse,
  zSetAthleteTimeZoneResponse,
} from './generated/zod.gen';

/** Every athlete-level query of one athlete, so a change to their Thresholds or time zone refreshes all of them. */
export const athleteQueryKey = (userId: number | undefined) => ['athlete', userId] as const;

/** Keyed by the athlete so one athlete's **Thresholds** are never served to the next on the same client. */
export const athleteThresholdsQueryOptions = (userId: number | undefined) =>
  queryOptions({
    queryKey: [...athleteQueryKey(userId), 'thresholds'] as const,
    queryFn: () =>
      validatedFetch<AthleteThresholdsResponseDto>(
        zGetAthleteThresholdsResponse,
        '/api/athlete/thresholds',
      ),
  });

/**
 * Refreshes what a Threshold or time-zone change can alter: the athlete's own settings, and the Dashboard and
 * Activities whose derived metrics are recalculated from them.
 */
function refreshAfterChange(
  queryClient: ReturnType<typeof useQueryClient>,
  userId: number | undefined,
) {
  return Promise.all(
    [athleteQueryKey(userId), ['dashboard', userId], ['activities', userId]].map((queryKey) =>
      queryClient.invalidateQueries({ queryKey }),
    ),
  );
}

/** Records a **Threshold** entry, replacing one already recorded for that kind and date. */
export function useRecordThreshold(userId: number | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (entry: ThresholdEntryRequestDto) =>
      validatedFetch<ThresholdEntryDto>(
        zRecordAthleteThresholdResponse,
        '/api/athlete/thresholds',
        {
          method: 'POST',
          body: JSON.stringify(entry),
        },
      ),
    onSuccess: () => refreshAfterChange(queryClient, userId),
  });
}

/** Deletes one of the athlete's **Threshold** entries. */
export function useDeleteThreshold(userId: number | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (entryId: number) =>
      apiClient<void>(`/api/athlete/thresholds/${entryId}`, { method: 'DELETE' }),
    onSettled: () => refreshAfterChange(queryClient, userId),
  });
}

/** Applies a **Threshold Proposal** as an entry: the only way a proposal ever changes a Threshold. */
export function useAcceptProposal(userId: number | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (proposalId: number) =>
      validatedFetch<ThresholdEntryDto>(
        zAcceptThresholdProposalResponse,
        `/api/athlete/thresholds/proposals/${proposalId}/accept`,
        { method: 'POST' },
      ),
    onSettled: () => refreshAfterChange(queryClient, userId),
  });
}

/** Dismisses a **Threshold Proposal** without applying it. */
export function useDismissProposal(userId: number | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (proposalId: number) =>
      apiClient<void>(`/api/athlete/thresholds/proposals/${proposalId}/dismiss`, {
        method: 'POST',
      }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: athleteQueryKey(userId) }),
  });
}

function putTimeZone(timeZone: string) {
  return validatedFetch<UserResponseDto>(zSetAthleteTimeZoneResponse, '/api/athlete/time-zone', {
    method: 'PUT',
    body: JSON.stringify({ timeZone }),
  });
}

/** Stores the zone the Server answered with on the cached current user. */
function cacheTimeZone(queryClient: ReturnType<typeof useQueryClient>, saved: UserResponseDto) {
  queryClient.setQueryData<UserResponseDto | null>(currentUserQueryOptions.queryKey, (previous) =>
    previous ? { ...previous, timeZone: saved.timeZone ?? null } : previous,
  );
}

/** Sets the **Athlete Time Zone** their local dates are counted in. */
export function useSetTimeZone(userId: number | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: putTimeZone,
    onSuccess: async (saved) => {
      cacheTimeZone(queryClient, saved);
      await refreshAfterChange(queryClient, userId);
    },
  });
}

/** The browser's IANA time zone, or null when it cannot tell. */
export function browserTimeZone(): string | null {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
  } catch {
    return null;
  }
}

/**
 * Sets a signed-in athlete's time zone from the browser, once, when they have none yet (ADR-0015). A failure is
 * left alone: the athlete's dates stay in UTC until the Settings page sets a zone.
 */
export function useInitialiseTimeZone(user: UserResponseDto) {
  const queryClient = useQueryClient();
  const attempted = useRef(false);
  useEffect(() => {
    if (attempted.current) return;
    const cached = queryClient.getQueryData<UserResponseDto | null>(
      currentUserQueryOptions.queryKey,
    );
    if (user.timeZone || cached?.timeZone) return;
    const zone = browserTimeZone();
    if (!zone) return;
    attempted.current = true;
    putTimeZone(zone).then(
      (saved) => cacheTimeZone(queryClient, saved),
      () => undefined,
    );
  }, [queryClient, user.timeZone]);
}
