import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { dashboardQueryOptions } from '../api/dashboard';
import { personalRecordProgressionQueryOptions } from '../api/personalRecords';
import { Dashboard } from './Dashboard';

const authenticatedApi = getRouteApi('/authenticated');

/** The queries the Dashboard route loads and renders. */
export const dashboardQueries = (userId: number | undefined) => ({
  dashboard: dashboardQueryOptions(userId),
  progression: personalRecordProgressionQueryOptions(userId),
});

export function DashboardRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const queries = dashboardQueries(user.id);
  const { data } = useSuspenseQuery(queries.dashboard);
  const { data: progression } = useSuspenseQuery(queries.progression);
  return (
    <Dashboard
      personalRecordSlots={data.personalRecordSlots}
      progressions={progression.progressions}
    />
  );
}
