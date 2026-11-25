import type { UseQueryResult } from '@tanstack/react-query';
import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { match } from 'ts-pattern';
import { dashboardQueryOptions } from '../api/dashboard';
import { criticalSpeedQueryOptions, peakEffortsQueryOptions } from '../api/paceDuration';
import { personalRecordProgressionQueryOptions } from '../api/personalRecords';
import { pmcQueryOptions } from '../api/pmc';
import { racePrognosisQueryOptions } from '../api/racePrognosis';
import type { QueryState } from '../features/pace-duration/PaceDurationCurve';
import type { ProgressionState } from '../features/pr-progression/PersonalRecordProgression';
import { Dashboard } from './Dashboard';

const authenticatedApi = getRouteApi('/authenticated');

/** The queries the Dashboard route loads and renders. */
export const dashboardQueries = (userId: number | undefined) => ({
  dashboard: dashboardQueryOptions(userId),
  pmc: pmcQueryOptions(userId),
  progression: personalRecordProgressionQueryOptions(userId),
  peakEfforts: peakEffortsQueryOptions(userId),
  criticalSpeed: criticalSpeedQueryOptions(userId),
  racePrognosis: racePrognosisQueryOptions(userId),
});

/** A query's result as a card sees it, so a failure stays inside that card. */
function queryState<T>(query: UseQueryResult<T>): QueryState<T> {
  return match(query)
    .returnType<QueryState<T>>()
    .with({ status: 'success' }, ({ data }) => ({ status: 'success', data }))
    .with({ status: 'error' }, ({ error }) => ({ status: 'error', error }))
    .otherwise(() => ({ status: 'pending' }));
}

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
  // Likewise the Pace-Duration Curve: each of its queries fails only its own section.
  const peakEfforts = useQuery({ ...queries.peakEfforts, refetchOnMount: false });
  const criticalSpeed = useQuery({ ...queries.criticalSpeed, refetchOnMount: false });
  return (
    <Dashboard
      userId={user.id}
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
      peakEfforts={queryState(peakEfforts)}
      criticalSpeed={queryState(criticalSpeed)}
    />
  );
}
