import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { match } from 'ts-pattern';
import { dashboardQueryOptions } from '../api/dashboard';
import { personalRecordProgressionQueryOptions } from '../api/personalRecords';
import { pmcQueryOptions } from '../api/pmc';
import type { ProgressionState } from '../features/pr-progression/PersonalRecordProgression';
import { Dashboard } from './Dashboard';

const authenticatedApi = getRouteApi('/authenticated');

/** The queries the Dashboard route loads and renders. */
export const dashboardQueries = (userId: number | undefined) => ({
  dashboard: dashboardQueryOptions(userId),
  pmc: pmcQueryOptions(userId),
  progression: personalRecordProgressionQueryOptions(userId),
});

export function DashboardRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const queries = dashboardQueries(user.id);
  const { data } = useSuspenseQuery(queries.dashboard);
  // Not suspended: a PMC that cannot be loaded fails only its own panel, never the Personal Records.
  // The loader has just fetched it, so mounting does not fetch it again; invalidation still refetches.
  const pmc = useQuery({ ...queries.pmc, refetchOnMount: false });
  // Not suspense: a failing progression degrades its own card, never the Personal Records. The loader
  // has just fetched it, so mounting does not fetch it again.
  const progression = useQuery({ ...queries.progression, refetchOnMount: false });
  return (
    <Dashboard
      personalRecordSlots={data.personalRecordSlots}
      pmc={{ data: pmc.data, isError: pmc.isError }}
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
