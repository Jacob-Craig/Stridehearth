import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  ActivityBestEffortDto,
  ActivityDetailDto,
  ActivityHistoryEntryDto,
  ActivityLapDto,
  ActivitySummaryDto,
  ActivityTelemetryResponseDto,
  ActivityTelemetryStreamsDto,
  RunMetricsDto,
  UserResponseDto,
} from '../src/api/generated/types.gen';
import { zActivityTelemetryStreamsDto } from '../src/api/generated/zod.gen';
import { describeActivityChange } from '../src/lib/activityChange';
import { createAppRouter } from '../src/router';

// jsdom has neither canvas nor WebGL: the charts and route map render through recording stand-ins.
vi.mock('../src/features/telemetry/uplotChart', () =>
  import('./support/fakeTelemetryRenderers').then((m) => m.fakeUplotChartModule),
);
vi.mock('../src/features/telemetry/maplibreRouteMap', () =>
  import('./support/fakeTelemetryRenderers').then((m) => m.fakeRouteMapModule),
);

const runner: UserResponseDto = { id: 1, username: 'runner_bob', email: 'bob@example.com' };

function lap(lapNumber: number, overrides: Partial<ActivityLapDto> = {}): ActivityLapDto {
  return {
    lapNumber,
    startedAt: '2026-03-01T08:00:00Z',
    distance: 1,
    timeTakenSeconds: 300,
    averagePaceKmh: 12,
    averageHeartRate: 150,
    elevationGainMeters: 12.5,
    ...overrides,
  };
}

const oneKilometerEffort: ActivityBestEffortDto = {
  id: 501,
  benchmarkDistance: 'ONE_KILOMETER',
  benchmarkName: '1k',
  distanceMeters: 1000,
  elapsedTimeSeconds: 240,
  window: {
    startIndex: 10,
    endIndex: 250,
    startTimeSeconds: 10,
    endTimeSeconds: 250,
    startDistanceMeters: 120,
    endDistanceMeters: 1120,
  },
};

const fiveKilometerEffort: ActivityBestEffortDto = {
  id: 502,
  benchmarkDistance: 'FIVE_KILOMETERS',
  benchmarkName: '5k',
  distanceMeters: 5000,
  elapsedTimeSeconds: 1250,
  window: {
    startIndex: 30,
    endIndex: 1280,
    startTimeSeconds: 30,
    endTimeSeconds: 1280,
    startDistanceMeters: 500,
    endDistanceMeters: 5500,
  },
};

function detail(overrides: Partial<ActivityDetailDto> = {}): ActivityDetailDto {
  return {
    id: 42,
    title: 'Track Session',
    sport: 'RUN',
    startedAt: '2026-03-01T08:00:00Z',
    distance: 6.4,
    timeTakenSeconds: 1920,
    averagePaceKmh: 12,
    averageHeartRate: 158,
    calories: 480,
    source: 'FIT_UPLOAD',
    hasProtectedMetrics: true,
    ownerUsername: 'runner_bob',
    laps: [],
    history: [],
    bestEfforts: [],
    runMetrics: null,
    trainingLoad: null,
    trainingLoadUnavailableReason: null,
    minStaminaPct: null,
    conditions: null,
    ...overrides,
  };
}

/**
 * Activity Details as the server streams them: every stream the generated contract declares is present,
 * `null` for sensors that were never recorded.
 */
function telemetry(): ActivityTelemetryResponseDto {
  const unrecorded = Object.fromEntries(
    Object.keys(zActivityTelemetryStreamsDto.shape).map((stream) => [stream, null]),
  );
  return {
    activityId: 42,
    sport: 'RUN',
    route: {
      type: 'LineString',
      coordinates: [
        [-0.16, 51.51],
        [-0.17, 51.52],
      ],
    },
    bestEfforts: [],
    derivedStreams: { gapSpeed: null, staminaPct: null },
    telemetry: {
      ...unrecorded,
      t: [0, 1, 2],
      heartRate: [140, null, 150],
    } as ActivityTelemetryStreamsDto,
  };
}

interface RecordedRequest {
  method: string;
  path: string;
}

type Handler = () => Response | Promise<Response>;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const notFound = () => json({ kind: 'RESOURCE_NOT_FOUND', message: 'Activity 42 not found' }, 404);

/** A fake `/api` server: routes are keyed by `METHOD /path`. */
function stubApi(routes: Record<string, Handler>) {
  const requests: RecordedRequest[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = new URL(typeof input === 'string' ? input : input.toString(), 'http://localhost');
      const request = { method: init?.method?.toUpperCase() ?? 'GET', path: url.pathname };
      requests.push(request);
      const handler = routes[`${request.method} ${request.path}`];
      if (!handler) throw new Error(`Unexpected request ${request.method} ${request.path}`);
      return handler();
    }),
  );
  return requests;
}

function stubActivity(
  activity: ActivityDetailDto,
  telemetryResponse: () => Response = notFound,
  extra: Record<string, Handler> = {},
) {
  return stubApi({
    [`GET /api/activities/${activity.id}`]: () => json(activity),
    [`GET /api/activities/${activity.id}/telemetry`]: telemetryResponse,
    ...extra,
  });
}

function renderApp(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const history = createMemoryHistory({ initialEntries: [path] });
  const router = createAppRouter(
    { queryClient, auth: { user: runner, isLoading: false } },
    history,
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { history, router, queryClient };
}

beforeEach(() => {
  vi.unstubAllGlobals();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('/activities/$activityId loading', () => {
  it('requests the Activity and its Activity Details in parallel before either responds', async () => {
    const pending: Array<() => void> = [];
    const deferred = (response: () => Response) => () =>
      new Promise<Response>((resolve) => pending.push(() => resolve(response())));
    const requests = stubApi({
      'GET /api/activities/42': deferred(() => json(detail())),
      'GET /api/activities/42/telemetry': deferred(() => json(telemetry())),
    });

    renderApp('/activities/42');

    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests.map((r) => r.path).sort()).toEqual([
      '/api/activities/42',
      '/api/activities/42/telemetry',
    ]);
    for (const release of pending) release();

    expect(await screen.findByRole('heading', { name: 'Track Session' })).toBeInTheDocument();
    expect(
      screen.getByRole('region', { name: /route map and sensor charts/i }),
    ).toBeInTheDocument();
  });

  it('preloads the Activity and its Activity Details when its link is hovered in the list', async () => {
    const summary: ActivitySummaryDto = {
      id: 42,
      title: 'Track Session',
      sport: 'RUN',
      startedAt: '2026-03-01T08:00:00Z',
      distance: 6.4,
      timeTakenSeconds: 1920,
      averagePaceKmh: 12,
      averageHeartRate: 158,
      calories: 480,
      source: 'FIT_UPLOAD',
      hasProtectedMetrics: true,
    };
    const requests = stubActivity(detail(), () => json(telemetry()), {
      'GET /api/activities': () =>
        json({
          items: [summary],
          page: 0,
          size: 10,
          totalElements: 1,
          totalPages: 1,
          hasNext: false,
          hasPrevious: false,
        }),
    });
    const user = userEvent.setup();
    renderApp('/activities');

    await user.hover(await screen.findByRole('link', { name: /view track session/i }));

    await waitFor(() =>
      expect(requests.map((r) => r.path)).toEqual(
        expect.arrayContaining(['/api/activities/42', '/api/activities/42/telemetry']),
      ),
    );
  });

  it('preloads the Activity and its Activity Details when its Personal Record link is focused on the Dashboard', async () => {
    const requests = stubActivity(detail(), () => json(telemetry()), {
      'GET /api/athlete/pmc': () => json({ timeZone: 'UTC', firstDay: null, days: [] }),
      'GET /api/athlete/personal-record-progression': () => json({ progressions: [] }),
      'GET /api/dashboard': () =>
        json({
          personalRecordSlots: [
            {
              benchmarkDistance: 'ONE_KILOMETER',
              benchmarkName: '1k',
              distanceMeters: 1000,
              personalRecord: {
                bestEffortId: 501,
                activityId: 42,
                activityTitle: 'Track Session',
                activityStartedAt: '2026-03-01T08:00:00Z',
                elapsedTimeSeconds: 240,
              },
            },
          ],
        }),
    });
    renderApp('/dashboard');

    const link = await screen.findByRole('link', { name: 'Track Session' });
    act(() => link.focus());

    await waitFor(() =>
      expect(requests.map((r) => r.path)).toEqual(
        expect.arrayContaining(['/api/activities/42', '/api/activities/42/telemetry']),
      ),
    );
  });

  it('shows a uniform not-found error for an Activity the athlete cannot see', async () => {
    stubApi({
      'GET /api/activities/99': () =>
        json({ kind: 'RESOURCE_NOT_FOUND', message: 'Activity 99 not found' }, 404),
      'GET /api/activities/99/telemetry': () =>
        json({ kind: 'RESOURCE_NOT_FOUND', message: 'Activity 99 not found' }, 404),
    });
    renderApp('/activities/99');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/this activity could not be loaded/i);
    expect(alert).toHaveTextContent('Activity 99 not found');
  });
});

describe('/activities/$activityId summary', () => {
  it('shows the title, sport, start date, owner and formatted summary metrics', async () => {
    stubActivity(detail());
    renderApp('/activities/42');

    expect(await screen.findByRole('heading', { name: 'Track Session' })).toBeInTheDocument();
    expect(screen.getByText('Run')).toBeInTheDocument();
    expect(screen.getByText('1 Mar 2026')).toBeInTheDocument();
    expect(screen.getByText('by runner_bob')).toBeInTheDocument();

    const metrics = screen.getByRole('list', { name: /summary metrics/i });
    const metric = (label: string) => within(metrics).getByText(label).closest('li') as HTMLElement;
    expect(metric('Distance')).toHaveTextContent('6.40 km');
    expect(metric('Duration')).toHaveTextContent('32:00');
    expect(metric('Avg Pace')).toHaveTextContent('05:00 /km');
    expect(metric('Avg Heart Rate')).toHaveTextContent('158 bpm');
    expect(metric('Calories')).toHaveTextContent('480 kcal');
  });

  it('renders a manual Activity without Activity Details and reserves no visualization space', async () => {
    stubActivity(detail({ source: 'MANUAL', hasProtectedMetrics: false, averageHeartRate: null }));
    renderApp('/activities/42');

    expect(await screen.findByRole('heading', { name: 'Track Session' })).toBeInTheDocument();
    const metrics = screen.getByRole('list', { name: /summary metrics/i });
    expect(within(metrics).getByText('Avg Heart Rate').closest('li')).toHaveTextContent('—');
    expect(screen.queryByRole('region', { name: /route map and sensor charts/i })).toBeNull();
  });

  describe('Run metrics', () => {
    const runMetrics = (overrides: Partial<RunMetricsDto> = {}): RunMetricsDto => ({
      movingSeconds: 3540,
      ngpMetersPerSecond: 10 / 3,
      efficiencyFactor: 1.2,
      aerobicDecouplingPercent: 4.9,
      decouplingUnavailableReason: null,
      ...overrides,
    });

    async function summaryTile(metrics: RunMetricsDto, label: string): Promise<HTMLElement> {
      stubActivity(detail({ runMetrics: metrics }));
      renderApp('/activities/42');
      const list = await screen.findByRole('list', { name: /summary metrics/i });
      return within(list).getByText(label).closest('li') as HTMLElement;
    }

    it('shows NGP as a pace and the Efficiency Factor to two decimals', async () => {
      stubActivity(detail({ runMetrics: runMetrics() }));
      renderApp('/activities/42');

      const list = await screen.findByRole('list', { name: /summary metrics/i });
      const metric = (label: string) => within(list).getByText(label).closest('li') as HTMLElement;
      // 10/3 m/s = 12 km/h = 5:00 /km.
      expect(metric('NGP')).toHaveTextContent('05:00 /km');
      expect(metric('Efficiency Factor')).toHaveTextContent('1.20');
    });

    it('shows a green Coupled badge below 5 percent', async () => {
      const tile = await summaryTile(
        runMetrics({ aerobicDecouplingPercent: 4.9 }),
        'Aerobic Decoupling',
      );

      const badge = within(tile).getByText('Coupled');
      expect(tile).toHaveTextContent('4.9%');
      expect(badge.closest('[data-status]')).toHaveAttribute('data-status', 'coupled');
      expect(badge.closest('[data-status]')?.className).toMatch(/emerald/);
    });

    it('shows an amber Drifting badge at 5 percent', async () => {
      const tile = await summaryTile(
        runMetrics({ aerobicDecouplingPercent: 5 }),
        'Aerobic Decoupling',
      );

      const badge = within(tile).getByText('Drifting');
      expect(tile).toHaveTextContent('5.0%');
      expect(badge.closest('[data-status]')).toHaveAttribute('data-status', 'drifting');
      expect(badge.closest('[data-status]')?.className).toMatch(/amber/);
    });

    it('classifies the percentage it displays, so 4.96 shows 5.0% and Drifting', async () => {
      const tile = await summaryTile(
        runMetrics({ aerobicDecouplingPercent: 4.96 }),
        'Aerobic Decoupling',
      );

      expect(tile).toHaveTextContent('5.0%');
      const badge = within(tile).getByText('Drifting');
      expect(badge.closest('[data-status]')).toHaveAttribute('data-status', 'drifting');
    });

    it('keeps 4.94, displayed as 4.9%, Coupled', async () => {
      const tile = await summaryTile(
        runMetrics({ aerobicDecouplingPercent: 4.94 }),
        'Aerobic Decoupling',
      );

      expect(tile).toHaveTextContent('4.9%');
      expect(within(tile).getByText('Coupled')).toBeInTheDocument();
    });

    it('treats negative decoupling as coupled', async () => {
      const tile = await summaryTile(
        runMetrics({ aerobicDecouplingPercent: -2 }),
        'Aerobic Decoupling',
      );

      expect(tile).toHaveTextContent('-2.0%');
      expect(within(tile).getByText('Coupled')).toBeInTheDocument();
    });

    it.each([
      ['NO_HEART_RATE', 'No heart rate'],
      ['HEART_RATE_GAP_IN_HALF', 'Heart rate missing in one half'],
      ['TOO_SHORT', 'Needs 30 min of running'],
      ['NOT_STEADY', 'Pace too variable'],
      ['NO_PACE_DATA', 'No pace data'],
    ] as const)('explains why decoupling is unavailable: %s', async (reason, copy) => {
      const tile = await summaryTile(
        runMetrics({ aerobicDecouplingPercent: null, decouplingUnavailableReason: reason }),
        'Aerobic Decoupling',
      );

      expect(tile).toHaveTextContent('—');
      expect(tile).toHaveTextContent(copy);
      expect(within(tile).queryByText(/Coupled|Drifting/)).toBeNull();
    });

    it('shows dashes for NGP and EF a Run could not compute', async () => {
      stubActivity(
        detail({
          runMetrics: runMetrics({
            ngpMetersPerSecond: null,
            efficiencyFactor: null,
            aerobicDecouplingPercent: null,
            decouplingUnavailableReason: 'NO_PACE_DATA',
          }),
        }),
      );
      renderApp('/activities/42');

      const list = await screen.findByRole('list', { name: /summary metrics/i });
      expect(within(list).getByText('NGP').closest('li')).toHaveTextContent('—');
      expect(within(list).getByText('Efficiency Factor').closest('li')).toHaveTextContent('—');
    });

    it('adds no Run metric tiles without Run metrics', async () => {
      stubActivity(detail({ runMetrics: null }));
      renderApp('/activities/42');

      const list = await screen.findByRole('list', { name: /summary metrics/i });
      expect(within(list).getAllByRole('listitem')).toHaveLength(5);
      expect(within(list).queryByText('NGP')).toBeNull();
      expect(within(list).queryByText('Efficiency Factor')).toBeNull();
      expect(within(list).queryByText('Aerobic Decoupling')).toBeNull();
    });
  });

  describe('Training Load', () => {
    async function trainingLoadTile(overrides: Partial<ActivityDetailDto>): Promise<HTMLElement> {
      stubActivity(detail(overrides));
      renderApp('/activities/42');
      const list = await screen.findByRole('list', { name: /summary metrics/i });
      return within(list).getByText('Training Load').closest('li') as HTMLElement;
    }

    it.each([
      ['RTSS', 87.4, '87 · rTSS'],
      ['RSS', 112.5, '113 · RSS'],
      ['HRSS', 63.34, '63 · HRSS'],
    ] as const)(
      'shows a %s load rounded to an integer with its method',
      async (method, value, shown) => {
        const tile = await trainingLoadTile({ trainingLoad: { value, method } });

        expect(tile).toHaveTextContent(shown);
      },
    );

    it.each([
      ['NO_ACTIVITY_DETAILS', 'No Activity Details recorded'],
      ['NO_USABLE_STREAM', 'No usable heart rate or power'],
    ] as const)('explains why there is no Training Load: %s', async (reason, copy) => {
      const tile = await trainingLoadTile({ trainingLoadUnavailableReason: reason });

      expect(tile).toHaveTextContent('No Training Load');
      expect(tile).toHaveTextContent(copy);
      expect(within(tile).queryByRole('link')).toBeNull();
    });

    it.each(['RIDE', 'SWIM', 'WORKOUT'] as const)(
      'does not blame power for a %s, whose power never counts',
      async (sport) => {
        const tile = await trainingLoadTile({
          sport,
          trainingLoadUnavailableReason: 'NO_USABLE_STREAM',
        });

        expect(tile).toHaveTextContent('No usable heart rate');
        expect(tile).not.toHaveTextContent(/power/i);
      },
    );

    it.each(['WALK', 'HIKE'] as const)('names heart rate or power for a %s', async (sport) => {
      const tile = await trainingLoadTile({
        sport,
        trainingLoadUnavailableReason: 'NO_USABLE_STREAM',
      });

      expect(tile).toHaveTextContent('No usable heart rate or power');
    });

    it('links to the settings page when heart-rate Thresholds contradict each other', async () => {
      const tile = await trainingLoadTile({
        trainingLoadUnavailableReason: 'INCONSISTENT_THRESHOLDS',
      });

      expect(tile).toHaveTextContent('No Training Load');
      expect(
        within(tile).getByRole('link', {
          name: /max and threshold heart rate must be above resting/i,
        }),
      ).toHaveAttribute('href', '/settings');
      expect(tile).not.toHaveTextContent(/set your thresholds/i);
    });

    it('links to the settings page when Thresholds are missing', async () => {
      const tile = await trainingLoadTile({ trainingLoadUnavailableReason: 'NO_THRESHOLDS' });

      expect(tile).toHaveTextContent('No Training Load');
      expect(within(tile).getByRole('link', { name: /set your thresholds/i })).toHaveAttribute(
        'href',
        '/settings',
      );
    });

    it('adds no tile while the Training Load is not computed yet', async () => {
      stubActivity(detail({ trainingLoad: null, trainingLoadUnavailableReason: null }));
      renderApp('/activities/42');

      const list = await screen.findByRole('list', { name: /summary metrics/i });
      expect(within(list).queryByText('Training Load')).toBeNull();
    });
  });

  describe('Min Stamina', () => {
    it('shows the lowest Stamina the Run reached as a whole percentage, after the other tiles', async () => {
      stubActivity(detail({ minStaminaPct: 24.6 }));
      renderApp('/activities/42');

      const list = await screen.findByRole('list', { name: /summary metrics/i });
      const tiles = within(list).getAllByRole('listitem');
      expect(tiles).toHaveLength(6);
      expect(tiles.at(-1)).toHaveTextContent('Min Stamina');
      expect(tiles.at(-1)).toHaveTextContent('25%');
    });

    it('adds no tile when the Run has no Stamina stream', async () => {
      stubActivity(detail({ minStaminaPct: null }));
      renderApp('/activities/42');

      const list = await screen.findByRole('list', { name: /summary metrics/i });
      expect(within(list).queryByText('Min Stamina')).toBeNull();
    });
  });

  it('links to the edit screen', async () => {
    stubActivity(detail());
    renderApp('/activities/42');

    expect(await screen.findByRole('link', { name: /edit/i })).toHaveAttribute(
      'href',
      '/activities/42/edit',
    );
  });

  it('deletes the Activity after confirmation and returns to the Activity list', async () => {
    const requests = stubActivity(detail(), notFound, {
      'DELETE /api/activities/42': () => new Response(null, { status: 204 }),
      'GET /api/activities': () =>
        json({
          items: [],
          page: 0,
          size: 10,
          totalElements: 0,
          totalPages: 0,
          hasNext: false,
          hasPrevious: false,
        }),
    });
    const user = userEvent.setup();
    const { history } = renderApp('/activities/42');

    await user.click(await screen.findByRole('button', { name: /^delete$/i }));
    const dialog = await screen.findByRole('alertdialog', { name: /delete activity/i });
    await user.click(within(dialog).getByRole('button', { name: /^delete$/i }));

    await waitFor(() => expect(history.location.pathname).toBe('/activities'));
    expect(requests).toContainEqual({ method: 'DELETE', path: '/api/activities/42' });
  });
});

describe('/activities/$activityId Best Efforts', () => {
  it('lists each Best Effort with its formatted duration, pace and EffortWindow', async () => {
    stubActivity(detail({ bestEfforts: [oneKilometerEffort, fiveKilometerEffort] }));
    renderApp('/activities/42');

    const table = await screen.findByRole('table', { name: /best efforts/i });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('1k');
    expect(rows[0]).toHaveTextContent('04:00');
    expect(rows[0]).toHaveTextContent('04:00 /km');
    expect(rows[0]).toHaveTextContent('00:10 – 04:10');
    expect(rows[0]).toHaveTextContent('0.12 – 1.12 km');
    expect(rows[1]).toHaveTextContent('5k');
    expect(rows[1]).toHaveTextContent('20:50');
    expect(rows[1]).toHaveTextContent('04:10 /km');
  });

  it('toggles the selected Best Effort by click and by Enter or Space', async () => {
    stubActivity(detail({ bestEfforts: [oneKilometerEffort, fiveKilometerEffort] }));
    const user = userEvent.setup();
    renderApp('/activities/42');

    const oneK = await screen.findByRole('button', { name: /1k best effort/i });
    const fiveK = screen.getByRole('button', { name: /5k best effort/i });
    expect(oneK).toHaveAttribute('aria-pressed', 'false');

    await user.click(oneK.closest('tr') as HTMLElement);
    expect(oneK).toHaveAttribute('aria-pressed', 'true');

    fiveK.focus();
    await user.keyboard('{Enter}');
    expect(fiveK).toHaveAttribute('aria-pressed', 'true');
    expect(oneK).toHaveAttribute('aria-pressed', 'false');

    await user.keyboard(' ');
    expect(fiveK).toHaveAttribute('aria-pressed', 'false');
  });

  it('activates the toggle button itself exactly once per click', async () => {
    stubActivity(detail({ bestEfforts: [oneKilometerEffort] }));
    const user = userEvent.setup();
    renderApp('/activities/42');

    const oneK = await screen.findByRole('button', { name: /1k best effort/i });
    await user.click(oneK);
    expect(oneK).toHaveAttribute('aria-pressed', 'true');

    await user.click(oneK);
    expect(oneK).toHaveAttribute('aria-pressed', 'false');
  });

  it('paces a Best Effort over its Benchmark Distance, as the Dashboard does for Personal Records', async () => {
    // The sampled EffortWindow overshoots the 1k Benchmark Distance by 250 m; pace stays nominal.
    const overshootingEffort: ActivityBestEffortDto = {
      ...oneKilometerEffort,
      window: { ...oneKilometerEffort.window, endDistanceMeters: 1370 },
    };
    stubActivity(detail({ bestEfforts: [overshootingEffort] }));
    renderApp('/activities/42');

    const table = await screen.findByRole('table', { name: /best efforts/i });
    const [row] = within(table).getAllByRole('row').slice(1);
    expect(row).toHaveTextContent('04:00 /km');
  });

  it('omits the Best Efforts table when the Activity has none', async () => {
    stubActivity(detail());
    renderApp('/activities/42');

    await screen.findByRole('heading', { name: 'Track Session' });
    expect(screen.queryByRole('table', { name: /best efforts/i })).toBeNull();
  });
});

describe('/activities/$activityId Laps', () => {
  it('lists two or more device Laps with formatted, tabular metrics', async () => {
    stubActivity(
      detail({
        laps: [
          lap(1),
          lap(2, {
            distance: 1.25,
            timeTakenSeconds: 3725,
            averagePaceKmh: null,
            averageHeartRate: null,
            elevationGainMeters: null,
          }),
        ],
      }),
    );
    renderApp('/activities/42');

    const table = await screen.findByRole('table', { name: /laps/i });
    const [, first, second] = within(table).getAllByRole('row');
    expect(first).toHaveTextContent('1');
    expect(first).toHaveTextContent('1.00 km');
    expect(first).toHaveTextContent('05:00');
    expect(first).toHaveTextContent('05:00 /km');
    expect(first).toHaveTextContent('150 bpm');
    expect(first).toHaveTextContent('+12.5 m');
    expect(second).toHaveTextContent('1.25 km');
    expect(second).toHaveTextContent('01:02:05');
    expect(within(second as HTMLElement).getAllByText('—')).toHaveLength(3);
  });

  it('hides the Laps table when the device recorded a single Lap', async () => {
    stubActivity(detail({ laps: [lap(1)] }));
    renderApp('/activities/42');

    await screen.findByRole('heading', { name: 'Track Session' });
    expect(screen.queryByRole('table', { name: /laps/i })).toBeNull();
  });
});

describe('/activities/$activityId Activity History', () => {
  it('lists Activity History Entries chronologically in an accessible dialog', async () => {
    const history: ActivityHistoryEntryDto[] = [
      {
        id: 1,
        createdAt: '2026-03-01T09:00:00Z',
        change: { kind: 'CREATED', source: 'FIT_UPLOAD', parserVersion: 1, lapCount: 3 },
      },
      {
        id: 2,
        createdAt: '2026-03-02T10:00:00Z',
        change: { kind: 'EDITED', fields: [{ kind: 'TITLE', from: 'A', to: 'Track Session' }] },
      },
      {
        id: 3,
        createdAt: '2026-03-03T10:00:00Z',
        change: {
          kind: 'UPGRADED',
          details: { kind: 'PARSER_VERSION_UPGRADED', from: 1, to: 2 },
          laps: null,
        },
      },
      {
        id: 4,
        createdAt: '2026-03-04T10:00:00Z',
        change: { kind: 'BATCH_EDITED', fields: [{ kind: 'SPORT', from: 'RUN', to: 'WALK' }] },
      },
      {
        id: 5,
        createdAt: '2026-03-05T10:00:00Z',
        change: { kind: 'LEGACY', changeType: 'EDITED', description: 'title: A, B -> C' },
      },
    ];
    stubActivity(detail({ history }));
    const user = userEvent.setup();
    renderApp('/activities/42');

    expect(screen.queryByRole('dialog')).toBeNull();
    await user.click(await screen.findByRole('button', { name: /activity history/i }));

    const dialog = await screen.findByRole('dialog', { name: /activity history/i });
    const entries = within(dialog).getAllByRole('listitem');
    expect(
      entries.map(
        (e) => within(e).getByText(/^(created|edited|upgraded|batch edited)$/i).textContent,
      ),
    ).toEqual(['Created', 'Edited', 'Upgraded', 'Batch edited', 'Edited']);
    entries.forEach((entry, i) => {
      expect(entry).toHaveTextContent(describeActivityChange(history[i]!.change));
    });
    expect(entries[0]).toHaveTextContent('1 Mar 2026');

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('explains when an Activity has no history yet', async () => {
    stubActivity(detail());
    const user = userEvent.setup();
    renderApp('/activities/42');

    await user.click(await screen.findByRole('button', { name: /activity history/i }));
    const dialog = await screen.findByRole('dialog', { name: /activity history/i });
    expect(dialog).toHaveTextContent(/no history/i);
  });
});
