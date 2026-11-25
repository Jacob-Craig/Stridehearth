import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { activityQueryOptions } from '../../api/activities';
import { RaceSplitPlanPage } from './RaceSplitPlanPage';

const authenticatedApi = getRouteApi('/authenticated');
const routeApi = getRouteApi('/authenticated/activities/$activityId/split-plan');

/** The query the split-plan route loads before rendering: the **Activity** whose route is the course. */
export const raceSplitPlanQueries = (
  userId: number | undefined,
  params: { readonly activityId: string },
) => ({ activity: activityQueryOptions(userId, Number(params.activityId)) });

export function RaceSplitPlanRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const { data: activity } = useSuspenseQuery(
    raceSplitPlanQueries(user.id, routeApi.useParams()).activity,
  );
  return <RaceSplitPlanPage key={activity.id} userId={user.id} activity={activity} />;
}
