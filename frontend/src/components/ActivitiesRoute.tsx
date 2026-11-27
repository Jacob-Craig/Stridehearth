import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi, useNavigate } from '@tanstack/react-router';
import { activitiesPageQueryOptions } from '../api/activities';
import type { WorkoutType } from '../api/generated/types.gen';
import { ActivitiesPage } from './ActivitiesPage';

const authenticatedApi = getRouteApi('/authenticated');
const routeApi = getRouteApi('/authenticated/activities');

/** The queries the Activities route loads and renders for one page of the list. */
export const activitiesQueries = (
  userId: number | undefined,
  { page, workoutType }: { readonly page: number; readonly workoutType?: WorkoutType | undefined },
) => ({
  activityPage: activitiesPageQueryOptions(userId, page, workoutType),
});

export function ActivitiesRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const navigate = useNavigate();
  const deps = routeApi.useLoaderDeps();
  const { data } = useSuspenseQuery(activitiesQueries(user.id, deps).activityPage);
  return (
    <ActivitiesPage
      userId={user.id}
      activityPage={data}
      workoutType={deps.workoutType}
      onPageChange={(next) =>
        navigate({ to: '/activities', search: { page: next, workoutType: deps.workoutType } })
      }
      onWorkoutTypeChange={(next) =>
        navigate({ to: '/activities', search: { page: 0, workoutType: next } })
      }
    />
  );
}
