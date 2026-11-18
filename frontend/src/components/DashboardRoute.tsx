import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { match } from 'ts-pattern';
import { dashboardQueryOptions } from '../api/dashboard';
import { personalRecordProgressionQueryOptions } from '../api/personalRecords';
import type { ProgressionState } from '../features/pr-progression/PersonalRecordProgression';
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
  // Not suspense: a failing progression degrades its own card, never the Personal Records. The loader
  // has just fetched it, so mounting does not fetch it again.
  const progression = useQuery({ ...queries.progression, refetchOnMount: false });
  return (
    <Dashboard
      personalRecordSlots={data.personalRecordSlots}
      progression={match(progression)
        .returnType<ProgressionState>()
        .with({ status: 'success' }, ({ data }) => ({
          status: 'success',
          progressions: data.progressions,
        }))
        .with({ status: 'error' }, ({ error }) => ({ status: 'error', error }))
        .otherwise(() => ({ status: 'pending' }))}
    />
  );
}
