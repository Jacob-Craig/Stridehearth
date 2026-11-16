import { useSuspenseQuery } from '@tanstack/react-query';
import { type ErrorComponentProps, Link, getRouteApi, useNavigate } from '@tanstack/react-router';
import type { UserResponseDto } from '../api/generated/types.gen';
import { inviteStatusQueryOptions, redeemInvite } from '../api/invites';
import { formatDate } from '../lib/format';
import { parseApiError } from '../utils/error';
import { RegisterForm } from './RegisterForm';

const rootApi = getRouteApi('__root__');
const routeApi = getRouteApi('/invite/$token');

function SignInLink() {
  return (
    <p className="mt-4 text-sm text-slate-600 dark:text-slate-400">
      Already have an account?{' '}
      <Link to="/login" className="text-blue-600 hover:underline">
        Log in here
      </Link>
    </p>
  );
}

/** The screen an invite link opens: a sign-up form that works on a closed Server, once. */
export function InviteRoute() {
  const { token } = routeApi.useParams();
  const navigate = useNavigate();
  const { auth } = rootApi.useRouteContext();
  const { data: invite } = useSuspenseQuery(inviteStatusQueryOptions(token));

  const handleRegisterSuccess = (user: UserResponseDto) => {
    auth.setUser?.(user);
    navigate({ to: '/dashboard' });
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-4">
      <RegisterForm
        heading="You're invited"
        intro={`Create your own account on this Server. This invite link works once and expires on ${formatDate(invite.expiresAt)}.`}
        submit={(data) => redeemInvite(token, data)}
        onRegisterSuccess={handleRegisterSuccess}
      />
      <SignInLink />
    </div>
  );
}

/** What an invite link that is unknown, used, expired or revoked opens instead of a sign-up form. */
export function InviteUnusableNotice({ error }: ErrorComponentProps) {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-4">
      <div
        role="alert"
        className="w-full max-w-md p-8 bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-slate-200 dark:border-slate-800"
      >
        <h2 className="text-2xl font-bold text-center mb-4 text-slate-900 dark:text-slate-100">
          This invite link can't be used
        </h2>
        <p className="text-sm text-center text-slate-600 dark:text-slate-400">
          {parseApiError(error).generalError}
        </p>
      </div>
      <SignInLink />
    </div>
  );
}
