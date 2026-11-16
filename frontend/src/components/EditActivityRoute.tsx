import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { activityQueryOptions } from '../api/activities';
import { EditActivityPage } from './EditActivityPage';

const authenticatedApi = getRouteApi('/authenticated');
const routeApi = getRouteApi('/authenticated/activities/$activityId/edit');

/** The queries the edit route loads and renders for the **Activity** in its path. */
export const editActivityQueries = (
  userId: number | undefined,
  params: { readonly activityId: string },
) => ({
  activity: activityQueryOptions(userId, Number(params.activityId)),
});

export function EditActivityRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const { data } = useSuspenseQuery(editActivityQueries(user.id, routeApi.useParams()).activity);
  return <EditActivityPage key={data.id} userId={user.id} activity={data} />;
}
