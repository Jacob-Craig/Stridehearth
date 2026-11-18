import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { dashboardQueryOptions } from '../api/dashboard';
import { pmcQueryOptions } from '../api/pmc';
import { Dashboard } from './Dashboard';

const authenticatedApi = getRouteApi('/authenticated');

/** The queries the Dashboard route loads and renders. */
export const dashboardQueries = (userId: number | undefined) => ({
  dashboard: dashboardQueryOptions(userId),
  pmc: pmcQueryOptions(userId),
});

export function DashboardRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const queries = dashboardQueries(user.id);
  const { data } = useSuspenseQuery(queries.dashboard);
  const { data: pmc } = useSuspenseQuery(queries.pmc);
  return <Dashboard personalRecordSlots={data.personalRecordSlots} pmc={pmc} />;
}
