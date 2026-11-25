import { useQuery } from '@tanstack/react-query';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { registrationStatusQueryOptions } from '../api/auth';
import type { UserResponseDto } from '../api/generated/types.gen';
import { LoginForm } from './LoginForm';
import { REGISTRATION_CLOSED_MESSAGE } from './RegistrationClosedNotice';

const rootApi = getRouteApi('__root__');
const routeApi = getRouteApi('/login');

export function LoginRoute() {
  const search = routeApi.useSearch();
  const navigate = useNavigate();
  const { auth } = rootApi.useRouteContext();
  // Not awaited by a loader, so signing in never waits on it: until it answers, neither line shows.
  const { data: registration } = useQuery(registrationStatusQueryOptions);

  const handleLoginSuccess = (user: UserResponseDto) => {
    if (auth.setUser) {
      auth.setUser(user);
    }
    const target = search.redirect || '/dashboard';
    navigate({ to: target as never });
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-4">
      <LoginForm onLoginSuccess={handleLoginSuccess} />
      {registration?.acceptsSignUps === true && (
        <p className="mt-4 text-sm text-slate-600 dark:text-slate-400">
          Don't have an account?{' '}
          <Link to="/register" className="text-blue-600 hover:underline">
            Register here
          </Link>
        </p>
      )}
      {registration?.acceptsSignUps === false && (
        <output className="block mt-4 text-sm text-slate-600 dark:text-slate-400">
          {REGISTRATION_CLOSED_MESSAGE}
        </output>
      )}
    </div>
  );
}
