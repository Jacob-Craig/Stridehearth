import { useSuspenseQuery } from '@tanstack/react-query';
import { getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import type { CreatedInviteDto, InviteDto } from '../../api/generated/types.gen';
import {
  inviteLink,
  pendingInvitesQueryOptions,
  useCreateInvite,
  useRevokeInvite,
} from '../../api/invites';
import { ErrorAlert } from '../../components/ErrorAlert';
import { formatDate, formatDateTime } from '../../lib/format';
import { parseApiError } from '../../utils/error';

const authenticatedApi = getRouteApi('/authenticated');

/** The queries the Invites route loads and renders. */
export const invitesQueries = (userId: number | undefined) => ({
  pending: pendingInvitesQueryOptions(userId),
});

/** The Administrator's Invites: create an invite link, and see or revoke the pending ones. */
export function InvitesRoute() {
  const { user } = authenticatedApi.useRouteContext();
  const { data: pending } = useSuspenseQuery(invitesQueries(user.id).pending);
  const createInvite = useCreateInvite(user.id);
  const revokeInvite = useRevokeInvite(user.id);
  const [created, setCreated] = useState<CreatedInviteDto | null>(null);

  const handleCreate = () => {
    createInvite.mutate(undefined, { onSuccess: setCreated });
  };

  const handleRevoke = (invite: InviteDto) => {
    revokeInvite.mutate(invite.id, {
      onSuccess: () => {
        if (created?.id === invite.id) setCreated(null);
      },
    });
  };

  const failure = createInvite.error ?? revokeInvite.error;

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 dark:border-slate-800 pb-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Invites</h1>
          <p className="mt-1 text-slate-600 dark:text-slate-400">
            An invite link lets one person create their own account on this Server, even while
            registration is closed. Each link works once and expires 7 days after you create it.
          </p>
        </div>
        <button
          type="button"
          onClick={handleCreate}
          disabled={createInvite.isPending}
          className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {createInvite.isPending ? 'Creating…' : 'Create invite link'}
        </button>
      </header>

      {failure && <ErrorAlert>{parseApiError(failure).generalError}</ErrorAlert>}

      {created && <NewInviteLink invite={created} />}

      <section aria-labelledby="pending-invites-heading" className="space-y-3">
        <h2 id="pending-invites-heading" className="text-lg font-semibold">
          Pending invites
        </h2>
        {pending.length === 0 ? (
          <div className="rounded-lg border border-dashed border-slate-300 dark:border-slate-700 p-8 text-center">
            <p className="font-medium">No pending invites.</p>
          </div>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Pending invites</caption>
              <thead className="bg-slate-50 dark:bg-slate-800/50 text-slate-600 dark:text-slate-400">
                <tr>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Created
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    Expires
                  </th>
                  <th scope="col" className="px-3 py-2 font-medium">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
                {pending.map((invite) => (
                  <tr key={invite.id}>
                    <td className="px-3 py-2 tabular-nums">{formatDateTime(invite.createdAt)}</td>
                    <td className="px-3 py-2 tabular-nums">{formatDateTime(invite.expiresAt)}</td>
                    <td className="px-3 py-2 text-right">
                      <button
                        type="button"
                        onClick={() => handleRevoke(invite)}
                        disabled={revokeInvite.isPending}
                        aria-label={`Revoke the invite created ${formatDateTime(invite.createdAt)}`}
                        className="rounded-md px-2 py-1 text-sm font-medium text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/50 disabled:opacity-50"
                      >
                        Revoke
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

/** A just-created invite link, shown once: the Server keeps no copy of it. */
function NewInviteLink({ invite }: { invite: CreatedInviteDto }) {
  const link = inviteLink(invite.token);
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
    } catch {
      // No clipboard access (an insecure origin, or permission refused): the link can still be selected.
      setCopied(false);
    }
  };

  return (
    <section
      aria-labelledby="new-invite-heading"
      className="rounded-lg border border-blue-200 dark:border-blue-900 bg-blue-50 dark:bg-blue-950/40 p-4 space-y-3"
    >
      <h2 id="new-invite-heading" className="font-semibold">
        New invite link
      </h2>
      <p className="text-sm text-slate-700 dark:text-slate-300">
        Copy this link now and send it to the person you are inviting. It is shown only once, and
        works until {formatDate(invite.expiresAt)}.
      </p>
      <div className="flex flex-col gap-2 sm:flex-row">
        <label htmlFor="new-invite-link" className="sr-only">
          Invite link
        </label>
        <input
          id="new-invite-link"
          readOnly
          value={link}
          onFocus={(e) => e.currentTarget.select()}
          className="min-w-0 flex-1 rounded-md border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-3 py-1.5 font-mono text-sm"
        />
        <button
          type="button"
          onClick={handleCopy}
          className="rounded-md border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-3 py-1.5 text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800"
        >
          {copied ? 'Copied' : 'Copy link'}
        </button>
      </div>
    </section>
  );
}
