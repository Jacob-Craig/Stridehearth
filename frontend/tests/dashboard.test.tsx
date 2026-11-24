import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  DashboardResponseDto,
  PersonalRecordDto,
  PersonalRecordProgressionResponseDto,
  PersonalRecordSlotDto,
  PersonalRecordStepDto,
  PmcResponseDto,
  UserResponseDto,
} from '../src/api/generated/types.gen';
import { createAppRouter } from '../src/router';
import { stubColourScheme } from './support/colourScheme';
import { pmcSeries, renderedPmcCharts } from './support/fakePmcChart';
import { progressionCharts } from './support/fakeProgressionChart';

vi.mock('../src/features/pmc/pmcChart', () =>
  import('./support/fakePmcChart').then((m) => m.fakePmcChartModule),
);
vi.mock('../src/features/pr-progression/progressionChart', () =>
  import('./support/fakeProgressionChart').then((m) => m.fakeProgressionChartModule),
);

const NO_PMC: PmcResponseDto = { timeZone: 'Europe/London', firstDay: null, days: [] };

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

function progressionWith(
  records: Partial<Record<PersonalRecordSlotDto['benchmarkDistance'], PersonalRecordStepDto[]>>,
): PersonalRecordProgressionResponseDto {
  return {
    progressions: BENCHMARKS.map((b) => ({ ...b, records: records[b.benchmarkDistance] ?? [] })),
  };
}

const step = (activityId: number, startedAt: string, seconds: number): PersonalRecordStepDto => ({
  activityId,
  activityTitle: `Record run ${activityId}`,
  activityStartedAt: startedAt,
  elapsedTimeSeconds: seconds,
});

const DASHBOARD_URL = '/api/dashboard';
const PROGRESSION_URL = '/api/athlete/personal-record-progression';
const PMC_URL = '/api/athlete/pmc';
const PEAK_EFFORTS_URL = '/api/athlete/peak-efforts';
const CRITICAL_SPEED_URL = '/api/athlete/critical-speed';

// An athlete with no Peak Efforts and so no Critical Speed model: the Pace-Duration Curve's empty state.
const NO_PEAK_EFFORTS = { windowDays: 90, pace: [], power: [] };
const NO_CRITICAL_SPEED = {
  asOf: '2026-11-24',
  windowDays: 90,
  pace: {
    status: 'INSUFFICIENT_POINTS',
    criticalValue: null,
    reserveCapacity: null,
    pointCount: 0,
    points: [],
    rSquared: null,
  },
  power: null,
};

// An athlete whose time zone is already set, so the app sends no one-off time-zone request.
const runner: UserResponseDto = {
  id: 1,
  username: 'runner_bob',
  email: 'bob@example.com',
  timeZone: 'Europe/London',
};

type Stubbed<T> = T | { status: number };

/**
 * Serves the Dashboard, the Personal Record Progression and the PMC (and an empty Pace-Duration Curve): each call to a URL takes the next
 * of its responses, repeating the last.
 */
function stubDashboardApi(
  responses: ReadonlyArray<Stubbed<DashboardResponseDto>>,
  progressions: ReadonlyArray<Stubbed<PersonalRecordProgressionResponseDto>> = [
    progressionWith({}),
  ],
  pmc: ReadonlyArray<Stubbed<PmcResponseDto>> = [NO_PMC],
) {
  const lists: Record<string, ReadonlyArray<Stubbed<object>>> = {
    [DASHBOARD_URL]: responses,
    [PROGRESSION_URL]: progressions,
    [PMC_URL]: pmc,
    [PEAK_EFFORTS_URL]: [NO_PEAK_EFFORTS],
    [CRITICAL_SPEED_URL]: [NO_CRITICAL_SPEED],
  };
  const served = new Map<string, number>();
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === 'string' ? input : input.toString();
    const list = lists[url];
    if (!list) throw new Error(`Unexpected request to ${url}`);
    const call = served.get(url) ?? 0;
    served.set(url, call + 1);
    const next = list[Math.min(call, list.length - 1)];
    if (next && 'status' in next && typeof next.status === 'number') {
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

const callsTo = (fetchMock: ReturnType<typeof stubDashboardApi>, url: string) =>
  fetchMock.mock.calls.filter(([input]) => String(input) === url).length;

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
    renderedPmcCharts.reset();
    progressionCharts.reset();
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

  it('renders all six benchmark slots as empty states, the PMC panel and the Weekly placeholder', async () => {
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
      screen.getByRole('heading', { name: 'Performance Management Chart' }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/performance trends/i)).not.toBeInTheDocument();
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
      { id: 2, username: 'other_athlete', email: 'o@example.com', timeZone: 'Europe/London' },
      queryClient,
    );

    const card = await findCard('5k');
    await waitFor(() => expect(within(card).getByText(/no record yet/i)).toBeInTheDocument());
    expect(screen.queryByText('Fast 5k Race')).not.toBeInTheDocument();
    expect(callsTo(fetchMock, DASHBOARD_URL)).toBe(2);
    expect(callsTo(fetchMock, PROGRESSION_URL)).toBe(2);
    expect(callsTo(fetchMock, PMC_URL)).toBe(2);
    expect(callsTo(fetchMock, PEAK_EFFORTS_URL)).toBe(2);
    expect(callsTo(fetchMock, CRITICAL_SPEED_URL)).toBe(2);
  });

  it('draws the PMC from the PMC endpoint and changes its range without fetching again', async () => {
    const pmc = pmcSeries(400);
    const fetchMock = stubDashboardApi([dashboardWith({})], undefined, [pmc]);
    renderApp('/dashboard', runner);

    await waitFor(() => expect(renderedPmcCharts.live()).toHaveLength(1));
    const chart = renderedPmcCharts.live()[0]!;
    expect(chart.options.days).toEqual(pmc.days);
    expect(callsTo(fetchMock, PMC_URL)).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: '1 year' }));
    fireEvent.click(screen.getByRole('button', { name: 'All' }));
    fireEvent.click(screen.getByRole('button', { name: '90 days' }));

    expect(chart.setRange.mock.calls).toEqual([['1y'], ['all'], ['90d']]);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(callsTo(fetchMock, PMC_URL)).toBe(1);
    expect(fetchMock).toHaveBeenCalledTimes(5);
    expect(renderedPmcCharts.charts).toHaveLength(1);
    expect(chart.destroy).not.toHaveBeenCalled();
  });

  it('shows the PMC empty state when the athlete has no Training Load', async () => {
    stubDashboardApi([dashboardWith({})]);
    renderApp('/dashboard', runner);

    const panel = await screen.findByRole('region', { name: 'Performance Management Chart' });
    expect(within(panel).getByText('No Training Load yet')).toBeVisible();
    expect(renderedPmcCharts.charts).toHaveLength(0);
    expect(screen.getAllByRole('article')).toHaveLength(6);
  });

  it('shows an error message when the dashboard cannot be loaded', async () => {
    stubDashboardApi([{ status: 500 }]);
    renderApp('/dashboard', runner);

    expect(await screen.findByRole('alert')).toHaveTextContent(/server exploded/i);
  });

  it('still shows the Personal Records when only the PMC cannot be loaded', async () => {
    stubDashboardApi([dashboardWith({ FIVE_KILOMETERS: fiveKRecord })], undefined, [
      { status: 500 },
    ]);
    renderApp('/dashboard', runner);

    const panel = await screen.findByRole('region', { name: 'Performance Management Chart' });
    await waitFor(() =>
      expect(within(panel).getByText('This chart could not be displayed.')).toBeVisible(),
    );
    expect(screen.getAllByRole('article')).toHaveLength(6);
    expect(screen.getByRole('link', { name: 'Fast 5k Race' })).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  describe('Personal Record Progression', () => {
    const progressionSection = () =>
      screen.findByRole('region', { name: 'Personal Record Progression' });

    it('shows an empty state and no chart when no Benchmark Distance has a record', async () => {
      stubDashboardApi([dashboardWith({})], [progressionWith({})]);
      renderApp('/dashboard', runner);

      const section = await progressionSection();
      expect(within(section).getByText('No records yet')).toBeInTheDocument();
      expect(within(section).queryByRole('combobox')).not.toBeInTheDocument();
      expect(progressionCharts.all).toHaveLength(0);
      // It sits inside the Running Personal Records section.
      expect(screen.getByRole('region', { name: 'Running Personal Records' })).toContainElement(
        section,
      );
    });

    it('opens on the longest distance with a record and steps down to today', async () => {
      stubDashboardApi(
        [dashboardWith({ FIVE_KILOMETERS: fiveKRecord })],
        [
          progressionWith({
            ONE_KILOMETER: [step(7, '2026-01-05T08:00:00Z', 250)],
            FIVE_KILOMETERS: [
              step(1, '2026-01-01T08:00:00Z', 1600),
              step(2, '2026-02-01T08:00:00Z', 1550),
              step(3, '2026-03-01T08:00:00Z', 1500),
            ],
          }),
        ],
      );
      const before = Date.now() / 1000;
      renderApp('/dashboard', runner);

      const section = await progressionSection();
      expect(within(section).getByRole('combobox', { name: 'Benchmark Distance' })).toHaveValue(
        'FIVE_KILOMETERS',
      );
      expect(
        within(section)
          .getAllByRole('option')
          .map((o) => o.textContent),
      ).toEqual(['1k', '5k']);
      await waitFor(() => expect(progressionCharts.live()).toHaveLength(1));
      const { points, recordCount } = progressionCharts.live()[0]!.options;
      // 2026-01-01T08:00Z, 2026-02-01T08:00Z, 2026-03-01T08:00Z in epoch seconds, then today.
      expect(points.x.slice(0, 3)).toEqual([1_767_254_400, 1_769_932_800, 1_772_352_000]);
      expect(points.x[3]).toBeGreaterThanOrEqual(before);
      expect(points.y).toEqual([1600, 1550, 1500, 1500]);
      expect(recordCount).toBe(3);

      const table = within(section).getByRole('table', { name: '5k Personal Record Progression' });
      expect(within(table).getAllByRole('row')).toHaveLength(4);
      expect(within(table).getByText('25:00')).toBeInTheDocument();
      expect(within(table).getByRole('link', { name: 'Record run 3' })).toHaveAttribute(
        'href',
        '/activities/3',
      );
    });

    it('redraws the chart for the Benchmark Distance the athlete selects', async () => {
      stubDashboardApi(
        [dashboardWith({})],
        [
          progressionWith({
            ONE_KILOMETER: [step(7, '2026-01-05T08:00:00Z', 250)],
            FIVE_KILOMETERS: [step(1, '2026-01-01T08:00:00Z', 1600)],
          }),
        ],
      );
      renderApp('/dashboard', runner);
      const section = await progressionSection();
      await waitFor(() => expect(progressionCharts.live()).toHaveLength(1));

      await userEvent.selectOptions(
        within(section).getByRole('combobox', { name: 'Benchmark Distance' }),
        'ONE_KILOMETER',
      );

      await waitFor(() => expect(progressionCharts.live()[0]?.options.points.y[0]).toBe(250));
      expect(progressionCharts.live()).toHaveLength(1);
      expect(
        within(section).getByRole('table', { name: '1k Personal Record Progression' }),
      ).toBeInTheDocument();
    });

    it('draws in the dark palette when the system is dark', async () => {
      stubColourScheme('dark');
      stubDashboardApi(
        [dashboardWith({})],
        [progressionWith({ FIVE_KILOMETERS: [step(1, '2026-01-01T08:00:00Z', 1600)] })],
      );
      renderApp('/dashboard', runner);

      await waitFor(() => expect(progressionCharts.live()).toHaveLength(1));
      expect(progressionCharts.live()[0]!.options.theme).toBe('dark');
    });

    it('shows an error in its own card, and keeps the Personal Records, when the progression cannot be loaded', async () => {
      stubDashboardApi([dashboardWith({})], [{ status: 500 }]);
      renderApp('/dashboard', runner);

      const section = await progressionSection();
      const alert = await within(section).findByRole('alert');
      expect(alert).toHaveTextContent(/progression could not be loaded/i);
      expect(alert).toHaveTextContent(/server exploded/i);
      expect(await findCard('5k')).toBeInTheDocument();
      expect(screen.queryByText(/your dashboard could not be loaded/i)).not.toBeInTheDocument();
      expect(progressionCharts.all).toHaveLength(0);
    });
  });
});
