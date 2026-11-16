import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { importHistoryQueryOptions } from '../../api/importBatches';
import { ImportHistory } from './ImportHistory';

const authenticatedApi = getRouteApi('/authenticated');

/** The queries the import history route loads and renders. */
export const importHistoryQueries = (userId: number | undefined) => ({
  history: importHistoryQueryOptions(userId),
});

export function ImportHistoryRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const { data } = useSuspenseQuery(importHistoryQueries(user.id).history);
  return <ImportHistory batches={data} />;
}
