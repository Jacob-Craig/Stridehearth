import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAppRouter } from '../src/router';

describe('Router & Route Protection', () => {
  const queryClient = new QueryClient();

  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async (input: RequestInfo | URL) =>
          new Response(
            JSON.stringify(
              String(input) === '/api/athlete/pmc'
                ? { timeZone: 'UTC', firstDay: null, days: [] }
                : { personalRecordSlots: [] },
            ),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
      ),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('redirects unauthenticated user from protected route to /login?redirect=...', async () => {
    const memoryHistory = createMemoryHistory({
      initialEntries: ['/dashboard'],
    });

    const router = createAppRouter(
      {
        queryClient,
        auth: {
          user: null,
          isLoading: false,
        },
      },
      memoryHistory,
    );

    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(memoryHistory.location.pathname).toBe('/login');
      expect(router.state.location.search).toEqual({
        redirect: '/dashboard',
      });
      expect(screen.getByRole('heading', { name: /log in/i })).toBeInTheDocument();
    });
  });

  it('renders protected dashboard and app shell when user is authenticated', async () => {
    const memoryHistory = createMemoryHistory({
      initialEntries: ['/dashboard'],
    });

    const router = createAppRouter(
      {
        queryClient,
        auth: {
          user: {
            id: 1,
            username: 'runner_bob',
            email: 'bob@example.com',
          },
          isLoading: false,
        },
      },
      memoryHistory,
    );

    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeInTheDocument();
      expect(screen.getByText('runner_bob')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /manage/i })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /log out/i })).toBeInTheDocument();
    });
  });

  it('allows navigation to other protected routes like /activities/manage when authenticated', async () => {
    const emptyPage = {
      items: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    };
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify(emptyPage), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    );
    const memoryHistory = createMemoryHistory({
      initialEntries: ['/activities/manage'],
    });

    const router = createAppRouter(
      {
        queryClient,
        auth: {
          user: {
            id: 1,
            username: 'runner_bob',
            email: 'bob@example.com',
          },
          isLoading: false,
        },
      },
      memoryHistory,
    );

    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /manage activities/i })).toBeInTheDocument();
      expect(screen.getByText('runner_bob')).toBeInTheDocument();
    });
  });

  it('hydrates user session asynchronously without prematurely redirecting to /login', async () => {
    const memoryHistory = createMemoryHistory({
      initialEntries: ['/dashboard'],
    });

    const testQueryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });

    testQueryClient.setQueryData(['currentUser'], {
      id: 5,
      username: 'hydrated_athlete',
      email: 'athlete@example.com',
    });

    const router = createAppRouter(
      {
        queryClient: testQueryClient,
        auth: {
          user: null,
          isLoading: true,
        },
      },
      memoryHistory,
    );

    render(
      <QueryClientProvider client={testQueryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeInTheDocument();
      expect(screen.getByText('hydrated_athlete')).toBeInTheDocument();
    });
  });

  it('renders the protected /settings route when authenticated', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({
              entries: [],
              current: {
                maxHr: null,
                restingHr: null,
                lthr: null,
                thresholdSpeedMps: null,
                criticalPowerWatts: null,
              },
              heartRateZones: null,
              paceZones: null,
              proposals: [],
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
      ),
    );
    const settingsQueryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const router = createAppRouter(
      {
        queryClient: settingsQueryClient,
        auth: {
          user: { id: 1, username: 'runner_bob', email: 'bob@example.com', timeZone: 'UTC' },
          isLoading: false,
        },
      },
      createMemoryHistory({ initialEntries: ['/settings'] }),
    );

    render(
      <QueryClientProvider client={settingsQueryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Settings', level: 1 })).toBeInTheDocument();
    });
  });
});
