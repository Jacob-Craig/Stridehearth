import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { dashboardQueryOptions } from '../api/dashboard';
import { Dashboard } from './Dashboard';

const authenticatedApi = getRouteApi('/authenticated');

/** The queries the Dashboard route loads and renders. */
export const dashboardQueries = (userId: number | undefined) => ({
  dashboard: dashboardQueryOptions(userId),
});

export function DashboardRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const { data } = useSuspenseQuery(dashboardQueries(user.id).dashboard);
  return <Dashboard personalRecordSlots={data.personalRecordSlots} />;
}
