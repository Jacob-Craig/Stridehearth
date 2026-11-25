import { getRouteApi, Outlet, useNavigate } from '@tanstack/react-router';
import { useInitialiseTimeZone } from '../api/athlete';
import { AppShell } from './AppShell';

const rootApi = getRouteApi('__root__');
const authenticatedApi = getRouteApi('/authenticated');

export function AuthenticatedLayout() {
  const { auth } = rootApi.useRouteContext();
  const { user } = authenticatedApi.useRouteContext();
  const navigate = useNavigate();
  useInitialiseTimeZone(user);

  const handleLogout = async () => {
    if (auth.logout) {
      await auth.logout();
    }
    navigate({ to: '/login' });
  };

  return (
    <AppShell user={user} onLogout={handleLogout}>
      <Outlet />
    </AppShell>
  );
}
