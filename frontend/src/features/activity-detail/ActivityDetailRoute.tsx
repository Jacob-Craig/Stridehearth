import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { activityQueryOptions, activityTelemetryQueryOptions } from '../../api/activities';
import { ActivityDetailPage } from './ActivityDetailPage';

const authenticatedApi = getRouteApi('/authenticated');
const routeApi = getRouteApi('/authenticated/activities/$activityId');

/** The queries the detail route loads and renders: the **Activity** and its **Activity Details**. */
export const activityDetailQueries = (
  userId: number | undefined,
  params: { readonly activityId: string },
) => {
  const activityId = Number(params.activityId);
  return {
    activity: activityQueryOptions(userId, activityId),
    telemetry: activityTelemetryQueryOptions(userId, activityId),
  };
};

export function ActivityDetailRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const queries = activityDetailQueries(user.id, routeApi.useParams());
  const { data: activity } = useSuspenseQuery(queries.activity);
  const { data: telemetry } = useSuspenseQuery(queries.telemetry);
  return (
    <ActivityDetailPage
      key={activity.id}
      userId={user.id}
      activity={activity}
      telemetry={telemetry}
    />
  );
}
