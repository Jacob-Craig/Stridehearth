import { getRouteApi } from '@tanstack/react-router';
import { AddActivityPage } from './AddActivityPage';

const authenticatedApi = getRouteApi('/authenticated');

export function AddActivityRoute() {
  const { user } = authenticatedApi.useRouteContext();
  return <AddActivityPage userId={user.id} />;
}
