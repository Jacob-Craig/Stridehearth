import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  CriticalSpeedFitDto,
  CriticalSpeedResponseDto,
  DashboardResponseDto,
  PeakEffortDto,
  PeakEffortSlotDto,
  PeakEffortsResponseDto,
  UserResponseDto,
} from '../src/api/generated/types.gen';
import { createAppRouter } from '../src/router';
import { stubColourScheme } from './support/colourScheme';
import { paceDurationCharts } from './support/fakePaceDurationChart';

vi.mock('../src/features/pmc/pmcChart', () =>
  import('./support/fakePmcChart').then((m) => m.fakePmcChartModule),
);
vi.mock('../src/features/pr-progression/progressionChart', () =>
  import('./support/fakeProgressionChart').then((m) => m.fakeProgressionChartModule),
);
vi.mock('../src/features/pace-duration/paceDurationChart', () =>
  import('./support/fakePaceDurationChart').then((m) => m.fakePaceDurationChartModule),
);

const runner: UserResponseDto = {
  id: 1,
  username: 'runner_bob',
  email: 'bob@example.com',
  timeZone: 'Europe/London',
};

const DURATIONS: ReadonlyArray<Pick<PeakEffortSlotDto, 'benchmarkDuration' | 'durationSeconds'>> = [
  { benchmarkDuration: 'FIVE_SECONDS', durationSeconds: 5 },
  { benchmarkDuration: 'FIFTEEN_SECONDS', durationSeconds: 15 },
  { benchmarkDuration: 'THIRTY_SECONDS', durationSeconds: 30 },
  { benchmarkDuration: 'ONE_MINUTE', durationSeconds: 60 },
  { benchmarkDuration: 'TWO_MINUTES', durationSeconds: 120 },
  { benchmarkDuration: 'THREE_MINUTES', durationSeconds: 180 },
  { benchmarkDuration: 'FIVE_MINUTES', durationSeconds: 300 },
  { benchmarkDuration: 'TEN_MINUTES', durationSeconds: 600 },
  { benchmarkDuration: 'TWENTY_MINUTES', durationSeconds: 1200 },
  { benchmarkDuration: 'THIRTY_MINUTES', durationSeconds: 1800 },
  { benchmarkDuration: 'SIXTY_MINUTES', durationSeconds: 3600 },
];

type Duration = PeakEffortSlotDto['benchmarkDuration'];
type Peaks = Partial<Record<Duration, { recent?: PeakEffortDto; allTime?: PeakEffortDto }>>;

const effort = (activityId: number, value: number): PeakEffortDto => ({
  value,
  activityId,
  activityTitle: `Run ${activityId}`,
  activityStartedAt: '2026-10-01T08:00:00Z',
});

const slots = (peaks: Peaks): PeakEffortSlotDto[] =>
  DURATIONS.map((d) => ({
    ...d,
    recent: peaks[d.benchmarkDuration]?.recent ?? null,
    allTime: peaks[d.benchmarkDuration]?.allTime ?? null,
  }));

const peakEfforts = (pace: Peaks, power?: Peaks): PeakEffortsResponseDto => ({
  windowDays: 90,
  pace: slots(pace),
  power: power ? slots(power) : [],
});

const model = (criticalValue: number, reserveCapacity: number): CriticalSpeedFitDto => ({
  status: 'MODEL',
  criticalValue,
  reserveCapacity,
  pointCount: 3,
  points: [],
  rSquared: 0.99,
});

const NO_MODEL: CriticalSpeedFitDto = {
  status: 'INSUFFICIENT_POINTS',
  criticalValue: null,
  reserveCapacity: null,
  pointCount: 1,
  points: [],
  rSquared: null,
};

const criticalSpeed = (
  pace: CriticalSpeedFitDto,
  power: CriticalSpeedFitDto | null = null,
): CriticalSpeedResponseDto => ({ asOf: '2026-11-24', windowDays: 90, pace, power });

const PACE_PEAKS: Peaks = {
  FIVE_SECONDS: { recent: effort(1, 6.0), allTime: effort(2, 6.2) },
  TWO_MINUTES: { recent: effort(4, 5.0), allTime: effort(4, 5.0) },
  TEN_MINUTES: { allTime: effort(42, 4.5) },
};

const EMPTY_DASHBOARD: DashboardResponseDto = { personalRecordSlots: [] };

/** Serves every Dashboard request; `peaks` and `cs` are the bodies, or an HTTP status to fail with. */
function stubApi(
  peaks: PeakEffortsResponseDto | number,
  cs: CriticalSpeedResponseDto | number = criticalSpeed(NO_MODEL),
) {
  const bodies: Record<string, object | number> = {
    '/api/dashboard': EMPTY_DASHBOARD,
    '/api/athlete/personal-record-progression': { progressions: [] },
    '/api/athlete/pmc': { timeZone: 'Europe/London', firstDay: null, days: [] },
    '/api/athlete/peak-efforts': peaks,
    '/api/athlete/critical-speed': cs,
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const body = bodies[String(input)];
      if (body === undefined || typeof body === 'number') {
        return new Response(JSON.stringify({ kind: 'BAD_REQUEST', message: 'Server exploded' }), {
          status: typeof body === 'number' ? body : 404,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }),
  );
}

function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const history = createMemoryHistory({ initialEntries: ['/dashboard'] });
  const router = createAppRouter(
    { queryClient, auth: { user: runner, isLoading: false } },
    history,
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { history };
}

const pdcSection = () => screen.findByRole('region', { name: 'Pace-Duration Curve' });

describe('Pace-Duration Curve', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    paceDurationCharts.reset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('draws the 90-day and All-Time peaks and the Critical Speed model, with CS and D′ in the legend', async () => {
    stubApi(peakEfforts(PACE_PEAKS), criticalSpeed(model(4.0, 240)));
    renderDashboard();

    const section = await pdcSection();
    await waitFor(() => expect(paceDurationCharts.live()).toHaveLength(1));
    const { options } = paceDurationCharts.live()[0]!;
    expect(options.mode).toBe('pace');
    expect(options.theme).toBe('light');
    expect(options.data.x.slice(0, 2)).toEqual([5, 120]);
    expect(options.data.recent.slice(0, 2)).toEqual([6.0, 5.0]);
    expect(options.data.allTime.slice(0, 2)).toEqual([6.2, 5.0]);
    // 4 + 240/120 = 6 at 2 min; nothing is modelled below 2 min.
    expect(options.data.model[0]).toBeNull();
    expect(options.data.model[1]).toBeCloseTo(6.0, 9);
    expect(options.data.model.filter((v) => v !== null)).toHaveLength(60);

    const legend = within(within(section).getByRole('list', { name: 'Legend' }));
    expect(legend.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '90 days',
      'All-Time',
      'CS 04:10 /km · D′ 240 m',
    ]);
    expect(within(section).queryByRole('button', { name: 'Power' })).not.toBeInTheDocument();
  });

  it('switches to power, with CP and W′ in the legend, when the athlete has power peaks', async () => {
    stubApi(
      peakEfforts(PACE_PEAKS, { TWO_MINUTES: { recent: effort(7, 350), allTime: effort(8, 380) } }),
      criticalSpeed(model(4.0, 240), model(300, 20_000)),
    );
    renderDashboard();

    const section = await pdcSection();
    await waitFor(() => expect(paceDurationCharts.live()).toHaveLength(1));
    expect(within(section).getByRole('button', { name: 'Pace' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    fireEvent.click(within(section).getByRole('button', { name: 'Power' }));

    await waitFor(() => expect(paceDurationCharts.live()[0]?.options.mode).toBe('power'));
    expect(paceDurationCharts.live()).toHaveLength(1);
    const { data } = paceDurationCharts.live()[0]!.options;
    expect(data.recent[0]).toBe(350);
    expect(data.allTime[0]).toBe(380);
    expect(within(section).getByText('CP 300 W · W′ 20.0 kJ')).toBeInTheDocument();
    expect(within(section).queryByText(/^CS /)).not.toBeInTheDocument();
    expect(within(section).getByRole('cell', { name: /380 W/ })).toBeInTheDocument();
  });

  it('links every peak to the Activity that set it', async () => {
    stubApi(peakEfforts(PACE_PEAKS));
    renderDashboard();

    const section = await pdcSection();
    const table = within(section).getByRole('table', { name: 'Pace-Duration Curve peaks' });
    const tenMinutes = within(table).getByRole('row', { name: /^10m/ });
    // 4.5 m/s = 16.2 km/h = 222 s/km.
    expect(within(tenMinutes).getByText('03:42 /km')).toBeInTheDocument();
    expect(within(tenMinutes).getByRole('link', { name: 'Run 42' })).toHaveAttribute(
      'href',
      '/activities/42',
    );
    expect(within(table).getAllByRole('link')).toHaveLength(5);
  });

  it('opens the Activity of a clicked point', async () => {
    stubApi(peakEfforts(PACE_PEAKS));
    const { history } = renderDashboard();

    await waitFor(() => expect(paceDurationCharts.live()).toHaveLength(1));
    act(() => paceDurationCharts.live()[0]!.options.onSelectActivity(42));

    await waitFor(() => expect(history.location.pathname).toBe('/activities/42'));
  });

  it('shows an empty state, and no chart, for an athlete without Peak Efforts', async () => {
    stubApi(peakEfforts({}));
    renderDashboard();

    const section = await pdcSection();
    expect(await within(section).findByText('Not enough data yet')).toBeInTheDocument();
    expect(within(section).queryByRole('table')).not.toBeInTheDocument();
    expect(paceDurationCharts.all).toHaveLength(0);
  });

  it('draws the peaks without a model line, and says why, when there is no model', async () => {
    stubApi(peakEfforts(PACE_PEAKS), criticalSpeed(NO_MODEL));
    renderDashboard();

    const section = await pdcSection();
    await waitFor(() => expect(paceDurationCharts.live()).toHaveLength(1));
    expect(paceDurationCharts.live()[0]!.options.data.model.every((v) => v === null)).toBe(true);
    expect(
      within(section).getByText(
        'No Critical Speed model yet: it needs 3 Peak Efforts between 2 and 20 min in the last 90 days.',
      ),
    ).toBeInTheDocument();
  });

  it('draws in the dark palette when the system is dark', async () => {
    stubColourScheme('dark');
    stubApi(peakEfforts(PACE_PEAKS));
    renderDashboard();

    await waitFor(() => expect(paceDurationCharts.live()).toHaveLength(1));
    expect(paceDurationCharts.live()[0]!.options.theme).toBe('dark');
  });

  it('shows an error in its own section, and keeps the Dashboard, when the peaks cannot be loaded', async () => {
    stubApi(500);
    renderDashboard();

    const section = await pdcSection();
    const alert = await within(section).findByRole('alert');
    expect(alert).toHaveTextContent(/pace-duration curve could not be loaded/i);
    expect(alert).toHaveTextContent(/server exploded/i);
    expect(screen.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeInTheDocument();
    expect(paceDurationCharts.all).toHaveLength(0);
  });
});
