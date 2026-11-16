import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi, useNavigate } from '@tanstack/react-router';
import { filteredActivitiesQueryOptions } from '../../api/activityManagement';
import { ManageActivitiesPage } from './ManageActivitiesPage';
import { type ManageFilters, toManageSearch } from './manageSearch';

const authenticatedApi = getRouteApi('/authenticated');
const routeApi = getRouteApi('/authenticated/activities/manage');

/** The queries the manage route loads and renders for its filters. */
export const manageActivitiesQueries = (userId: number | undefined, filters: ManageFilters) => ({
  activityPage: filteredActivitiesQueryOptions(userId, filters),
});

export function ManageActivitiesRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const filters = routeApi.useLoaderDeps();
  const navigate = useNavigate();
  const { data } = useSuspenseQuery(manageActivitiesQueries(user.id, filters).activityPage);
  return (
    <ManageActivitiesPage
      userId={user.id}
      filters={filters}
      activityPage={data}
      onFiltersChange={(next) =>
        navigate({ to: '/activities/manage', search: toManageSearch(next) })
      }
    />
  );
}
