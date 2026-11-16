import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi, useNavigate } from '@tanstack/react-router';
import { activitiesPageQueryOptions } from '../api/activities';
import { ActivitiesPage } from './ActivitiesPage';

const authenticatedApi = getRouteApi('/authenticated');
const routeApi = getRouteApi('/authenticated/activities');

/** The queries the Activities route loads and renders for one page of the list. */
export const activitiesQueries = (
  userId: number | undefined,
  { page }: { readonly page: number },
) => ({
  activityPage: activitiesPageQueryOptions(userId, page),
});

export function ActivitiesRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const navigate = useNavigate();
  const { data } = useSuspenseQuery(
    activitiesQueries(user.id, routeApi.useLoaderDeps()).activityPage,
  );
  return (
    <ActivitiesPage
      userId={user.id}
      activityPage={data}
      onPageChange={(next) => navigate({ to: '/activities', search: { page: next } })}
    />
  );
}
