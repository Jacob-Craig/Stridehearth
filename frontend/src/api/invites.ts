import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient, validatedFetch } from './client';
import type {
  CreatedInviteDto,
  InviteDto,
  PendingInviteDto,
  UserFormDto,
  UserResponseDto,
} from './generated/types.gen';
import {
  zCreateInviteResponse,
  zInviteStatusResponse,
  zListPendingInvitesResponse,
  zRedeemInviteResponse,
} from './generated/zod.gen';

/** The Server's pending **Invites**, as only its Administrator may see them, newest first. */
export const pendingInvitesQueryOptions = (userId: number | undefined) =>
  queryOptions({
    queryKey: ['pendingInvites', userId] as const,
    queryFn: () => validatedFetch<InviteDto[]>(zListPendingInvitesResponse, '/api/invites'),
  });

/**
 * The link an invited person opens, on the origin this app is served from. Built only here, from the token the
 * Server sends once when the **Invite** is created: the Server keeps no copy of it.
 */
export function inviteLink(token: string, origin: string = window.location.origin): string {
  return new URL(`/invite/${encodeURIComponent(token)}`, origin).href;
}

function refreshPendingInvites(
  queryClient: ReturnType<typeof useQueryClient>,
  userId: number | undefined,
) {
  return queryClient.invalidateQueries({ queryKey: pendingInvitesQueryOptions(userId).queryKey });
}

/** Creates an **Invite**; the result carries its link's token, which no later request can show again. */
export function useCreateInvite(userId: number | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      validatedFetch<CreatedInviteDto>(zCreateInviteResponse, '/api/invites', { method: 'POST' }),
    onSuccess: () => refreshPendingInvites(queryClient, userId),
  });
}

/** Revokes a pending **Invite**, so its link no longer lets anyone sign up. */
export function useRevokeInvite(userId: number | undefined) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (inviteId: number) =>
      apiClient<void>(`/api/invites/${inviteId}`, { method: 'DELETE' }),
    onSettled: () => refreshPendingInvites(queryClient, userId),
  });
}

/** Whether the **Invite** an invite link carries is still pending; fails with `INVITE_UNUSABLE` if not. */
export const inviteStatusQueryOptions = (token: string) =>
  queryOptions({
    queryKey: ['inviteStatus', token] as const,
    queryFn: () =>
      validatedFetch<PendingInviteDto>(
        zInviteStatusResponse,
        `/api/auth/invites/${encodeURIComponent(token)}`,
      ),
    retry: false,
  });

/** Creates the invited person's own account with an **Invite**, and signs it in. */
export async function redeemInvite(token: string, data: UserFormDto): Promise<UserResponseDto> {
  return validatedFetch<UserResponseDto>(
    zRedeemInviteResponse,
    `/api/auth/invites/${encodeURIComponent(token)}/redeem`,
    { method: 'POST', body: JSON.stringify(data) },
  );
}
