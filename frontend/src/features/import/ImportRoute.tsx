import { getRouteApi, useNavigate } from '@tanstack/react-router';
import { ImportBatchProgress } from './ImportBatchProgress';
import { ImportBatchUploader } from './ImportBatchUploader';

const authenticatedApi = getRouteApi('/authenticated');
const routeApi = getRouteApi('/authenticated/activities/import');

export function ImportRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const { batchId } = routeApi.useSearch();
  const navigate = useNavigate();
  return batchId === undefined ? (
    <ImportBatchUploader
      onSubmitted={(receipt) =>
        navigate({ to: '/activities/import', search: { batchId: receipt.batchId } })
      }
    />
  ) : (
    <ImportBatchProgress key={batchId} userId={user.id} batchId={batchId} />
  );
}
