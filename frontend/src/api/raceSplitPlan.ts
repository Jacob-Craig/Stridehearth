import { keepPreviousData, queryOptions } from '@tanstack/react-query';
import { activitiesKey } from './activities';
import { validatedFetch } from './client';
import type { RaceSplitPlanResponseDto } from './generated/types.gen';
import { zGetRaceSplitPlanResponse } from './generated/zod.gen';

/** What a **Race Split Plan** is asked for: a Goal Time in s, the split unit and the **Split Bias** in percent. */
export interface RaceSplitPlanRequest {
  readonly goalSeconds: number;
  readonly unit: RaceSplitPlanResponseDto['unit'];
  readonly biasPct: number;
}

/** The **Race Split Plan** over an Activity's route; computed on request and never stored. */
export async function getRaceSplitPlan(
  activityId: number,
  request: RaceSplitPlanRequest,
): Promise<RaceSplitPlanResponseDto> {
  const params = new URLSearchParams({
    goalSeconds: String(request.goalSeconds),
    unit: request.unit,
    biasPct: String(request.biasPct),
  });
  return validatedFetch(
    zGetRaceSplitPlanResponse,
    `/api/activities/${activityId}/race-split-plan?${params}`,
  );
}

/**
 * Under the athlete's Activities key, so an edit or re-import of the course refreshes its plans and one
 * athlete's plan is never served to the next. Each request is its own entry.
 */
export const raceSplitPlanQueryKey = (
  userId: number | undefined,
  activityId: number,
  request: RaceSplitPlanRequest,
) =>
  [
    ...activitiesKey(userId),
    'race-split-plan',
    activityId,
    { goalSeconds: request.goalSeconds, unit: request.unit, biasPct: request.biasPct },
  ] as const;

export const raceSplitPlanQueryOptions = (
  userId: number | undefined,
  activityId: number,
  request: RaceSplitPlanRequest,
) =>
  queryOptions({
    queryKey: raceSplitPlanQueryKey(userId, activityId, request),
    queryFn: () => getRaceSplitPlan(activityId, request),
    // The last plan stays on screen while a changed Goal Time, unit or bias loads.
    placeholderData: keepPreviousData,
  });
