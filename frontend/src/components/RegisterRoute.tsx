import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router';
import { registrationStatusQueryOptions, useRefreshRegistrationStatus } from '../api/auth';
import type { UserResponseDto } from '../api/generated/types.gen';
import { RegisterForm } from './RegisterForm';
import { RegistrationClosedNotice } from './RegistrationClosedNotice';

const rootApi = getRouteApi('__root__');

export function RegisterRoute() {
  const navigate = useNavigate();
  const refreshRegistrationStatus = useRefreshRegistrationStatus();
  const { auth } = rootApi.useRouteContext();
  const { data: registration } = useSuspenseQuery(registrationStatusQueryOptions);

  const handleRegisterSuccess = (user: UserResponseDto) => {
    refreshRegistrationStatus();
    if (auth.setUser) {
      auth.setUser(user);
    }
    navigate({ to: '/dashboard' });
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-4">
      {registration.acceptsSignUps ? (
        <RegisterForm onRegisterSuccess={handleRegisterSuccess} />
      ) : (
        <RegistrationClosedNotice />
      )}
      <p className="mt-4 text-sm text-slate-600 dark:text-slate-400">
        Already have an account?{' '}
        <Link to="/login" className="text-blue-600 hover:underline">
          Log in here
        </Link>
      </p>
    </div>
  );
}
