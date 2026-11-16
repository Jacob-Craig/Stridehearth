import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { InviteDto, UserResponseDto } from '../src/api/generated/types.gen';
import { inviteLink } from '../src/api/invites';
import { currentUserQueryOptions } from '../src/auth/AuthContext';
import { createAppRouter } from '../src/router';

const administrator: UserResponseDto = {
  id: 1,
  username: 'admin',
  email: 'admin@example.com',
  administrator: true,
};
const member: UserResponseDto = {
  id: 2,
  username: 'member',
  email: 'member@example.com',
  administrator: false,
};

type Reply = { status: number; body: unknown };
type Handler = (init: RequestInit | undefined) => Reply;

/** Answers each `METHOD url` with its handler; anything else is a 404 so a stray request fails visibly. */
function stubApi(routes: Record<string, Handler>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const handler = routes[`${(init?.method ?? 'GET').toUpperCase()} ${url}`];
    const reply = handler
      ? handler(init)
      : { status: 404, body: { kind: 'RESOURCE_NOT_FOUND', message: `No stub for ${url}` } };
    return new Response(reply.status === 204 ? null : JSON.stringify(reply.body), {
      status: reply.status,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderAt(path: string, user: UserResponseDto | null) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(currentUserQueryOptions.queryKey, user);
  const router = createAppRouter(
    {
      queryClient,
      auth: {
        user,
        isLoading: false,
        setUser: (next) => queryClient.setQueryData(currentUserQueryOptions.queryKey, next),
      },
    },
    createMemoryHistory({ initialEntries: [path] }),
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return router;
}

const pendingInvite: InviteDto = {
  id: 11,
  createdAt: '2026-11-10T09:00:00Z',
  expiresAt: '2026-11-17T09:00:00Z',
};

describe('Invites', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('builds an invite link on the origin the app is served from', () => {
    expect(inviteLink('abc-_123', 'https://stride.example.org')).toBe(
      'https://stride.example.org/invite/abc-_123',
    );
  });

  describe('opening an invite link', () => {
    it('offers a pending Invite a sign-up form that creates the account with it', async () => {
      const fetchMock = stubApi({
        'GET /api/auth/invites/tok123': () => ({
          status: 200,
          body: { expiresAt: '2026-11-20T12:00:00Z' },
        }),
        'POST /api/auth/invites/tok123/redeem': () => ({
          status: 201,
          body: { id: 3, username: 'newbie', email: 'newbie@example.com', administrator: false },
        }),
      });
      const router = renderAt('/invite/tok123', null);

      expect(await screen.findByRole('heading', { name: "You're invited" })).toBeInTheDocument();
      expect(screen.getByText(/expires on 20 Nov 2026/)).toBeInTheDocument();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText(/^username/i), 'newbie');
      await user.type(screen.getByLabelText(/^email/i), 'newbie@example.com');
      await user.type(screen.getByLabelText(/^password/i), 'secret123');
      await user.click(screen.getByRole('button', { name: /register/i }));

      await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard'));
      const redeem = fetchMock.mock.calls.find(
        ([url]) => url === '/api/auth/invites/tok123/redeem',
      );
      expect(JSON.parse(String(redeem?.[1]?.body))).toMatchObject({
        username: 'newbie',
        email: 'newbie@example.com',
        password: 'secret123',
      });
    });

    it('says why a used, expired or revoked link cannot be used, with no sign-up form', async () => {
      stubApi({
        'GET /api/auth/invites/stale': () => ({
          status: 410,
          body: {
            kind: 'INVITE_UNUSABLE',
            message:
              'This invite link has already been used. Ask whoever invited you for a new one.',
          },
        }),
      });
      renderAt('/invite/stale', null);

      expect(
        await screen.findByRole('heading', { name: "This invite link can't be used" }),
      ).toBeInTheDocument();
      expect(screen.getByText(/has already been used/)).toBeInTheDocument();
      expect(screen.queryByLabelText(/^username/i)).not.toBeInTheDocument();
      expect(screen.getByRole('link', { name: /log in here/i })).toHaveAttribute('href', '/login');
    });
  });

  describe('the Invites page', () => {
    it('is linked from the navigation for the Administrator only', async () => {
      stubApi({ 'GET /api/invites': () => ({ status: 200, body: [] }) });
      renderAt('/invites', administrator);
      expect(await screen.findByRole('link', { name: 'Invites' })).toHaveAttribute(
        'href',
        '/invites',
      );
    });

    it('sends any other account to its Dashboard, with no Invites link', async () => {
      const fetchMock = stubApi({});
      const router = renderAt('/invites', member);

      await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard'));
      expect(screen.queryByRole('link', { name: 'Invites' })).not.toBeInTheDocument();
      expect(fetchMock).not.toHaveBeenCalledWith('/api/invites', expect.anything());
    });

    it('creates an invite link and shows it once, ready to copy', async () => {
      let pending: InviteDto[] = [];
      stubApi({
        'GET /api/invites': () => ({ status: 200, body: pending }),
        'POST /api/invites': () => {
          pending = [pendingInvite];
          return {
            status: 201,
            body: { ...pendingInvite, token: 'fresh-token' },
          };
        },
      });
      renderAt('/invites', administrator);

      expect(await screen.findByText('No pending invites.')).toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: 'Create invite link' }));

      const link = await screen.findByLabelText('Invite link');
      expect(link).toHaveValue(`${window.location.origin}/invite/fresh-token`);
      expect(screen.getByText(/It is shown only once/)).toBeInTheDocument();
      expect(await screen.findByRole('table', { name: 'Pending invites' })).toBeInTheDocument();
    });

    it('revokes a pending Invite', async () => {
      let pending: InviteDto[] = [pendingInvite];
      const fetchMock = stubApi({
        'GET /api/invites': () => ({ status: 200, body: pending }),
        'DELETE /api/invites/11': () => {
          pending = [];
          return { status: 204, body: null };
        },
      });
      renderAt('/invites', administrator);

      const table = await screen.findByRole('table', { name: 'Pending invites' });
      await userEvent.click(within(table).getByRole('button', { name: /revoke/i }));

      expect(await screen.findByText('No pending invites.')).toBeInTheDocument();
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/invites/11',
        expect.objectContaining({ method: 'DELETE' }),
      );
    });

    it('shows the Server refusing an account that is not the Administrator', async () => {
      stubApi({
        'GET /api/invites': () => ({
          status: 403,
          body: {
            kind: 'ADMINISTRATOR_ONLY',
            message: 'Only the Administrator can manage invites.',
          },
        }),
      });
      renderAt('/invites', administrator);

      expect(
        await screen.findByText('Only the Administrator can manage invites.'),
      ).toBeInTheDocument();
    });
  });
});
