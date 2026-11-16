import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createAppRouter } from '../src/router';

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** Serves the Server's registration status as [acceptsSignUps], or fails it when that is null. */
function stubRegistrationStatus(acceptsSignUps: boolean | null) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/auth/registration')) {
      return acceptsSignUps === null
        ? json({ kind: 'BAD_REQUEST', message: 'Unavailable' }, 500)
        : json({ acceptsSignUps });
    }
    return json({ kind: 'UNAUTHORIZED', message: 'Authentication required' }, 401);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createAppRouter(
    { queryClient, auth: { user: null, isLoading: false } },
    createMemoryHistory({ initialEntries: [path] }),
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('Registration Mode on the sign-in and sign-up screens', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe('on a closed Server that already has its first account', () => {
    it('says plainly on the sign-in screen that registration is closed, with no way to sign up', async () => {
      stubRegistrationStatus(false);
      renderAt('/login');

      expect(await screen.findByText('Registration is closed on this Server.')).toBeInTheDocument();
      expect(screen.getByRole('heading', { name: /log in/i })).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /register/i })).not.toBeInTheDocument();
    });

    it('offers no sign-up form on the sign-up route', async () => {
      stubRegistrationStatus(false);
      renderAt('/register');

      expect(
        await screen.findByRole('heading', { name: 'Registration is closed' }),
      ).toBeInTheDocument();
      expect(screen.getByText(/Registration is closed on this Server\./)).toBeInTheDocument();
      expect(screen.queryByLabelText(/^username/i)).not.toBeInTheDocument();
      expect(screen.queryByLabelText(/^password/i)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /register/i })).not.toBeInTheDocument();
      expect(screen.getByRole('link', { name: /log in here/i })).toHaveAttribute('href', '/login');
    });
  });

  describe('on a Server that accepts sign-ups', () => {
    it('links the sign-in screen to the sign-up form', async () => {
      stubRegistrationStatus(true);
      renderAt('/login');

      expect(await screen.findByRole('link', { name: /register here/i })).toHaveAttribute(
        'href',
        '/register',
      );
      expect(screen.queryByText('Registration is closed on this Server.')).not.toBeInTheDocument();
    });

    it('shows the sign-up form on the sign-up route', async () => {
      stubRegistrationStatus(true);
      renderAt('/register');

      expect(
        await screen.findByRole('heading', { name: /create an account/i }),
      ).toBeInTheDocument();
      expect(screen.getByLabelText(/^username/i)).toBeInTheDocument();
      expect(screen.queryByText(/Registration is closed/)).not.toBeInTheDocument();
    });
  });

  describe('when the Server cannot say whether it accepts sign-ups', () => {
    it('keeps the sign-in screen usable but offers no sign-up link', async () => {
      const fetchMock = stubRegistrationStatus(null);
      renderAt('/login');

      expect(await screen.findByRole('heading', { name: /log in/i })).toBeInTheDocument();
      await waitFor(() =>
        expect(fetchMock).toHaveBeenCalledWith('/api/auth/registration', expect.anything()),
      );
      expect(screen.queryByRole('link', { name: /register/i })).not.toBeInTheDocument();
    });

    it('offers no sign-up form on the sign-up route', async () => {
      stubRegistrationStatus(null);
      renderAt('/register');

      expect(
        await screen.findByRole('heading', { name: 'Registration could not be checked' }),
      ).toBeInTheDocument();
      expect(screen.queryByLabelText(/^username/i)).not.toBeInTheDocument();
    });
  });
});
