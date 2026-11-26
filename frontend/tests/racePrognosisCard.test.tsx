import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  DashboardResponseDto,
  RacePredictionDto,
  RacePrognosisResponseDto,
  UserResponseDto,
} from '../src/api/generated/types.gen';
import { racePrognosisQueryKey } from '../src/api/racePrognosis';
import {
  alignedPrognosisData,
  formatFinishTime,
  parseConditions,
} from '../src/features/race-prognosis/prognosisSeries';
import { createAppRouter } from '../src/router';
import { prognosisCharts } from './support/fakePrognosisChart';

vi.mock('../src/features/pmc/pmcChart', () =>
  import('./support/fakePmcChart').then((m) => m.fakePmcChartModule),
);
vi.mock('../src/features/pr-progression/progressionChart', () =>
  import('./support/fakeProgressionChart').then((m) => m.fakeProgressionChartModule),
);
vi.mock('../src/features/pace-duration/paceDurationChart', () =>
  import('./support/fakePaceDurationChart').then((m) => m.fakePaceDurationChartModule),
);
vi.mock('../src/features/race-prognosis/prognosisChart', () =>
  import('./support/fakePrognosisChart').then((m) => m.fakePrognosisChartModule),
);

const runner: UserResponseDto = {
  id: 1,
  username: 'runner_bob',
  email: 'bob@example.com',
  timeZone: 'Europe/London',
};

const prediction = (
  distance: RacePredictionDto['distance'],
  meters: number,
  predictedSeconds: number,
  courseFactor = 1,
): RacePredictionDto => ({
  distance,
  meters,
  vdotSeconds: predictedSeconds,
  predictedSeconds,
  enduranceFactor: 1,
  courseFactor,
});

/** Effective VO2max 55.54 at Marathon Shape 6.91%, as the API serves it. */
const PROGNOSIS: RacePrognosisResponseDto = {
  current: {
    day: '2026-11-24',
    effectiveVo2max: 55.54,
    marathonShapePct: 6.91,
    predictions: [
      prediction('FIVE_K', 5000, 1093.0),
      prediction('TEN_K', 10000, 2323.2),
      prediction('HALF_MARATHON', 21097.5, 5417.3),
      prediction('MARATHON', 42195, 12416.5),
    ],
  },
  days: [
    {
      day: '2026-11-23',
      fiveKSeconds: 1100,
      tenKSeconds: 2340,
      halfSeconds: 5485.35,
      marathonSeconds: 12658.5,
    },
    {
      day: '2026-11-24',
      fiveKSeconds: 1093.0,
      tenKSeconds: 2323.2,
      halfSeconds: 5417.3,
      marathonSeconds: 12416.5,
    },
  ],
};

/** The same day in 25 °C on a course climbing 200 m: the 10K × 1.0962. */
const HOT_HILLY: RacePrognosisResponseDto = {
  ...PROGNOSIS,
  current: {
    ...PROGNOSIS.current!,
    predictions: [
      prediction('FIVE_K', 5000, 1093.0 * 1.015 * 1.16, 1.015 * 1.16),
      prediction('TEN_K', 10000, 2546.7, 1.0962),
      prediction('HALF_MARATHON', 21097.5, 5417.3 * 1.015 * 1.0379, 1.015 * 1.0379),
      prediction('MARATHON', 42195, 12416.5 * 1.034244, 1.034244),
    ],
  },
};

const NO_PROGNOSIS: RacePrognosisResponseDto = { current: null, days: [] };

const EMPTY_DASHBOARD: DashboardResponseDto = { personalRecordSlots: [] };

const BASE_URL = '/api/athlete/race-prognosis';

/** Serves every Dashboard request; `prognosis` maps a Race Prognosis URL to its body or an HTTP status. */
function stubApi(prognosis: Record<string, RacePrognosisResponseDto | number>) {
  const bodies: Record<string, object | number> = {
    '/api/dashboard': EMPTY_DASHBOARD,
    '/api/athlete/personal-record-progression': { progressions: [] },
    '/api/athlete/pmc': { timeZone: 'Europe/London', firstDay: null, days: [] },
    '/api/athlete/peak-efforts': { windowDays: 90, pace: [], power: [] },
    '/api/athlete/critical-speed': 404,
    ...prognosis,
  };
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
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
  });
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

const prognosisRequests = (fetch: ReturnType<typeof stubApi>) =>
  fetch.mock.calls.map(([input]) => String(input)).filter((url) => url.startsWith(BASE_URL));

function renderDashboard() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
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

const card = () => screen.findByRole('region', { name: 'Race Prognosis' });

/** The finish time shown for each distance, in order. */
async function finishTimes(section: HTMLElement) {
  const list = within(section).getByRole('list', {
    name: 'Predicted finish times',
  });
  return within(list)
    .getAllByRole('listitem')
    .map((item) => [
      within(item).getByRole('heading').textContent,
      within(item).getByTestId('finish-time').textContent,
    ]);
}

describe('Race Prognosis card', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    prognosisCharts.reset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows the four predicted finish times, the Effective VO2max and the Marathon Shape', async () => {
    stubApi({ [BASE_URL]: PROGNOSIS });
    renderDashboard();

    const section = await card();
    await within(section).findByText('55.5');
    expect(await finishTimes(section)).toEqual([
      ['5K', '18:13'],
      ['10K', '38:43'],
      ['Half Marathon', '1:30:17'],
      ['Marathon', '3:26:57'],
    ]);
    // 12416.5 s over 42.195 km = 294.3 s/km.
    expect(within(section).getByText('4:54 /km')).toBeInTheDocument();
    expect(within(section).getByText('Effective VO2max')).toBeInTheDocument();
    expect(within(section).getByText('7%')).toBeInTheDocument();
  });

  it("draws each distance's predicted pace per day on the trend chart", async () => {
    stubApi({ [BASE_URL]: PROGNOSIS });
    renderDashboard();

    await waitFor(() => expect(prognosisCharts.live()).toHaveLength(1));
    const { options } = prognosisCharts.live()[0]!;
    expect(options.theme).toBe('light');
    expect(options.data.x).toEqual([
      Date.parse('2026-11-23T00:00:00Z') / 1000,
      Date.parse('2026-11-24T00:00:00Z') / 1000,
    ]);
    // 1100 s over 5 km = 220 s/km; 12658.5 s over 42.195 km = 300 s/km.
    expect(options.data.paces.FIVE_K).toEqual([220, 1093.0 / 5]);
    expect(options.data.paces.TEN_K[0]).toBeCloseTo(234, 9);
    expect(options.data.paces.HALF_MARATHON[0]).toBeCloseTo(260, 9);
    expect(options.data.paces.MARATHON[0]).toBeCloseTo(300, 9);
  });

  it("reads out the finish times of the day under the cursor, and today's again when it leaves", async () => {
    stubApi({ [BASE_URL]: PROGNOSIS });
    renderDashboard();

    const section = await card();
    await waitFor(() => expect(prognosisCharts.live()).toHaveLength(1));
    const readout = within(section).getByRole('status');
    expect(readout).toHaveTextContent('Today');

    act(() => prognosisCharts.live()[0]!.options.onHover(0));
    expect(readout).toHaveTextContent('23 Nov 2026');
    expect(readout).toHaveTextContent('5K 18:20');
    expect(readout).toHaveTextContent('Marathon 3:30:59');

    act(() => prognosisCharts.live()[0]!.options.onHover(null));
    expect(readout).toHaveTextContent('Today');
    expect(readout).toHaveTextContent('Marathon 3:26:57');
  });

  it('asks for heart-rate runs and Thresholds, with a link to Settings, when there is no prognosis', async () => {
    stubApi({ [BASE_URL]: NO_PROGNOSIS });
    renderDashboard();

    const section = await card();
    expect(
      await within(section).findByText(
        /Record steady runs with heart rate and set your max and resting heart rate in/,
      ),
    ).toBeInTheDocument();
    expect(within(section).getByRole('link', { name: 'Settings' })).toHaveAttribute(
      'href',
      '/settings',
    );
    expect(within(section).queryByRole('list', { name: 'Predicted finish times' })).toBeNull();
    expect(prognosisCharts.all).toHaveLength(0);
  });

  it('keeps the trend, with a note, after a break with no recent qualifying runs', async () => {
    stubApi({ [BASE_URL]: { current: null, days: PROGNOSIS.days } });
    renderDashboard();

    const section = await card();
    expect(await within(section).findByText(/No recent qualifying runs/)).toBeInTheDocument();
    expect(within(section).queryByText(/Record steady runs with heart rate/)).toBeNull();
    expect(within(section).getByRole('link', { name: 'Settings' })).toHaveAttribute(
      'href',
      '/settings',
    );
    expect(within(section).queryByRole('list', { name: 'Predicted finish times' })).toBeNull();
    await waitFor(() => expect(prognosisCharts.live()).toHaveLength(1));
    // With no today in the data, the latest day is read out by its date, not as "Today".
    expect(within(section).getByRole('status')).toHaveTextContent('24 Nov 2026');
  });

  it('keeps the typed conditions and the open form when the derated query fails', async () => {
    stubApi({
      [BASE_URL]: PROGNOSIS,
      [`${BASE_URL}?temperatureC=25&elevationGainM=200`]: 500,
    });
    renderDashboard();

    const section = await card();
    await within(section).findByText('55.5');
    fireEvent.click(within(section).getByText('Race-day conditions'));
    fireEvent.change(within(section).getByLabelText('Temperature (°C)'), {
      target: { value: '25' },
    });
    fireEvent.change(within(section).getByLabelText('Course climb (m)'), {
      target: { value: '200' },
    });
    fireEvent.click(within(section).getByRole('button', { name: 'Apply' }));

    expect(
      await within(section).findByText(/Your Race Prognosis could not be loaded/),
    ).toBeInTheDocument();
    expect(within(section).getByLabelText('Temperature (°C)')).toHaveValue('25');
    expect(within(section).getByLabelText('Course climb (m)')).toHaveValue('200');
    expect(within(section).getByText('Race-day conditions').closest('details')).toHaveAttribute(
      'open',
    );
  });

  it('keeps a failure inside the card', async () => {
    stubApi({ [BASE_URL]: 500 });
    renderDashboard();

    const section = await card();
    expect(
      await within(section).findByText(/Your Race Prognosis could not be loaded/),
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Running Personal Records' })).toBeInTheDocument();
  });

  it('re-queries with race-day conditions and shows the derated times', async () => {
    const fetch = stubApi({
      [BASE_URL]: PROGNOSIS,
      [`${BASE_URL}?temperatureC=25&elevationGainM=200`]: HOT_HILLY,
    });
    renderDashboard();

    const section = await card();
    await within(section).findByText('55.5');
    fireEvent.click(within(section).getByText('Race-day conditions'));
    fireEvent.change(within(section).getByLabelText('Temperature (°C)'), {
      target: { value: '25' },
    });
    fireEvent.change(within(section).getByLabelText('Course climb (m)'), {
      target: { value: '200' },
    });
    fireEvent.click(within(section).getByRole('button', { name: 'Apply' }));

    await waitFor(async () => expect((await finishTimes(section))[1]).toEqual(['10K', '42:27']));
    expect(prognosisRequests(fetch)).toEqual([
      BASE_URL,
      `${BASE_URL}?temperatureC=25&elevationGainM=200`,
    ]);
    expect(within(section).getByText('+9.6% for conditions')).toBeInTheDocument();

    fireEvent.click(within(section).getByRole('button', { name: 'Clear' }));
    await waitFor(async () => expect((await finishTimes(section))[1]).toEqual(['10K', '38:43']));
  });

  it('refuses conditions out of range without re-querying', async () => {
    const fetch = stubApi({ [BASE_URL]: PROGNOSIS });
    renderDashboard();

    const section = await card();
    await within(section).findByText('55.5');
    fireEvent.click(within(section).getByText('Race-day conditions'));
    fireEvent.change(within(section).getByLabelText('Temperature (°C)'), {
      target: { value: '60' },
    });
    fireEvent.click(within(section).getByRole('button', { name: 'Apply' }));

    expect(await within(section).findByText('Enter a number from -30 to 50')).toBeInTheDocument();
    expect(within(section).getByLabelText('Temperature (°C)')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    expect(prognosisRequests(fetch)).toEqual([BASE_URL]);
  });
});

describe('Race Prognosis helpers', () => {
  it('formats finish times as h:mm:ss, or m:ss under an hour', () => {
    expect(formatFinishTime(1196.6)).toBe('19:57');
    expect(formatFinishTime(5491.2)).toBe('1:31:31');
    expect(formatFinishTime(11439.7)).toBe('3:10:40');
  });

  it('turns each day into a pace per km per distance', () => {
    const data = alignedPrognosisData(PROGNOSIS.days);
    expect(data.paces.HALF_MARATHON[0]).toBeCloseTo(5485.35 / 21.0975, 9);
    expect(data.x).toHaveLength(2);
  });

  it('reads blank conditions as none and numbers in range as conditions', () => {
    expect(parseConditions({ temperatureC: ' ', elevationGainM: '' })).toEqual({
      ok: true,
      conditions: {},
    });
    expect(parseConditions({ temperatureC: '-30', elevationGainM: '5000' })).toEqual({
      ok: true,
      conditions: { temperatureC: -30, elevationGainM: 5000 },
    });
    expect(parseConditions({ temperatureC: 'warm', elevationGainM: '-1' })).toEqual({
      ok: false,
      errors: {
        temperatureC: 'Enter a number from -30 to 50',
        elevationGainM: 'Enter a number from 0 to 5000',
      },
    });
  });

  it("keys each set of conditions apart, under the athlete's Dashboard", () => {
    expect(racePrognosisQueryKey(7).slice(0, 3)).toEqual(['dashboard', 7, 'race-prognosis']);
    expect(racePrognosisQueryKey(7, { temperatureC: 25 })).not.toEqual(racePrognosisQueryKey(7));
    expect(racePrognosisQueryKey(8)).not.toEqual(racePrognosisQueryKey(7));
  });
});
