import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  DashboardResponseDto,
  PersonalRecordDto,
  PersonalRecordSlotDto,
  UserResponseDto,
} from '../src/api/generated/types.gen';
import { createAppRouter } from '../src/router';

const BENCHMARKS: ReadonlyArray<
  Pick<PersonalRecordSlotDto, 'benchmarkDistance' | 'benchmarkName' | 'distanceMeters'>
> = [
  { benchmarkDistance: 'FOUR_HUNDRED_METERS', benchmarkName: '400m', distanceMeters: 400 },
  { benchmarkDistance: 'ONE_KILOMETER', benchmarkName: '1k', distanceMeters: 1000 },
  { benchmarkDistance: 'ONE_MILE', benchmarkName: '1 mile', distanceMeters: 1609.344 },
  { benchmarkDistance: 'FIVE_KILOMETERS', benchmarkName: '5k', distanceMeters: 5000 },
  { benchmarkDistance: 'TEN_KILOMETERS', benchmarkName: '10k', distanceMeters: 10000 },
  { benchmarkDistance: 'HALF_MARATHON', benchmarkName: 'Half-Marathon', distanceMeters: 21097.5 },
];

function dashboardWith(
  records: Partial<Record<PersonalRecordSlotDto['benchmarkDistance'], PersonalRecordDto>>,
): DashboardResponseDto {
  return {
    personalRecordSlots: BENCHMARKS.map((b) => ({
      ...b,
      personalRecord: records[b.benchmarkDistance] ?? null,
    })),
  };
}

const fiveKRecord: PersonalRecordDto = {
  bestEffortId: 501,
  activityId: 42,
  activityTitle: 'Fast 5k Race',
  activityStartedAt: '2026-04-10T09:00:00Z',
  elapsedTimeSeconds: 1200,
};

const runner: UserResponseDto = { id: 1, username: 'runner_bob', email: 'bob@example.com' };

function stubDashboardApi(responses: ReadonlyArray<DashboardResponseDto | { status: number }>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url !== '/api/dashboard') throw new Error(`Unexpected request to ${url}`);
    const next = responses[Math.min(fetchMock.mock.calls.length - 1, responses.length - 1)];
    if (next && 'status' in next) {
      return new Response(JSON.stringify({ kind: 'BAD_REQUEST', message: 'Server exploded' }), {
        status: next.status,
        headers: { 'Content-Type': 'application/json' },
      });
    }
    return new Response(JSON.stringify(next), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderApp(path: string, user: UserResponseDto, queryClient = newQueryClient()) {
  const history = createMemoryHistory({ initialEntries: [path] });
  const router = createAppRouter({ queryClient, auth: { user, isLoading: false } }, history);
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { history, router, queryClient };
}

function newQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

async function findCard(benchmarkName: string) {
  return screen.findByRole('article', { name: benchmarkName });
}

describe('Dashboard', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('redirects / to /dashboard', async () => {
    stubDashboardApi([dashboardWith({})]);
    const { history } = renderApp('/', runner);

    await waitFor(() => expect(history.location.pathname).toBe('/dashboard'));
    expect(
      await screen.findByRole('heading', { name: /dashboard/i, level: 1 }),
    ).toBeInTheDocument();
  });

  it('renders all six benchmark slots as empty states with Coming Soon placeholders', async () => {
    stubDashboardApi([dashboardWith({})]);
    renderApp('/dashboard', runner);

    const cards = await screen.findAllByRole('article');
    expect(cards.map((c) => within(c).getByRole('heading').textContent)).toEqual([
      '400m',
      '1k',
      '1 mile',
      '5k',
      '10k',
      'Half-Marathon',
    ]);
    for (const card of cards) {
      expect(within(card).getByText(/no record yet/i)).toBeInTheDocument();
      expect(within(card).queryByRole('link')).not.toBeInTheDocument();
    }
    expect(within(await findCard('1 mile')).getByText('1,609.344 m')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: /weekly activity summary.*coming soon/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: /performance trends.*coming soon/i }),
    ).toBeInTheDocument();
  });

  it('renders a partial set of personal records with formatted figures and an activity link', async () => {
    stubDashboardApi([dashboardWith({ FIVE_KILOMETERS: fiveKRecord })]);
    renderApp('/dashboard', runner);

    const card = await findCard('5k');
    const duration = within(card).getByText('20:00');
    expect(duration).toHaveClass('tabular-nums');
    expect(within(card).getByText('04:00 /km')).toHaveClass('tabular-nums');
    expect(within(card).getByText('5,000 m')).toHaveClass('tabular-nums');
    expect(within(card).getByText('10 Apr 2026')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Fast 5k Race' })).toHaveAttribute(
      'href',
      '/activities/42',
    );

    expect(within(await findCard('10k')).getByText(/no record yet/i)).toBeInTheDocument();
    expect(within(await findCard('400m')).getByText(/no record yet/i)).toBeInTheDocument();
  });

  it('renders every slot when all benchmarks hold a personal record', async () => {
    const record = (activityId: number, seconds: number): PersonalRecordDto => ({
      bestEffortId: activityId * 10,
      activityId,
      activityTitle: `Run ${activityId}`,
      activityStartedAt: '2026-05-01T07:00:00Z',
      elapsedTimeSeconds: seconds,
    });
    stubDashboardApi([
      dashboardWith({
        FOUR_HUNDRED_METERS: record(1, 96),
        ONE_KILOMETER: record(2, 240),
        ONE_MILE: record(3, 360),
        FIVE_KILOMETERS: record(4, 1200),
        TEN_KILOMETERS: record(5, 2400),
        HALF_MARATHON: record(6, 6000),
      }),
    ]);
    renderApp('/dashboard', runner);

    await findCard('Half-Marathon');
    expect(screen.queryByText(/no record yet/i)).not.toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: /^Run \d$/ })).toHaveLength(6);
    expect(within(await findCard('400m')).getByText('01:36')).toBeInTheDocument();
    expect(within(await findCard('1 mile')).getByText('03:44 /km')).toBeInTheDocument();
    const halfMarathon = await findCard('Half-Marathon');
    expect(within(halfMarathon).getByText('01:40:00')).toBeInTheDocument();
    expect(within(halfMarathon).getByRole('link')).toHaveAttribute('href', '/activities/6');
  });

  it("never shows a previous athlete's cached personal records to the next athlete", async () => {
    const fetchMock = stubDashboardApi([
      dashboardWith({ FIVE_KILOMETERS: fiveKRecord }),
      dashboardWith({}),
    ]);
    const queryClient = newQueryClient();
    const first = renderApp('/dashboard', runner, queryClient);
    await screen.findByRole('link', { name: 'Fast 5k Race' });
    first.router.history.destroy();
    document.body.innerHTML = '';

    renderApp(
      '/dashboard',
      { id: 2, username: 'other_athlete', email: 'o@example.com' },
      queryClient,
    );

    const card = await findCard('5k');
    await waitFor(() => expect(within(card).getByText(/no record yet/i)).toBeInTheDocument());
    expect(screen.queryByText('Fast 5k Race')).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('shows an error message when the dashboard cannot be loaded', async () => {
    stubDashboardApi([{ status: 500 }]);
    renderApp('/dashboard', runner);

    expect(await screen.findByRole('alert')).toHaveTextContent(/server exploded/i);
  });
});
