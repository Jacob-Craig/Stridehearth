import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  ActivityDetailDto,
  RacePredictionDto,
  RacePrognosisResponseDto,
  RaceSplitDto,
  RaceSplitPlanResponseDto,
  UserResponseDto,
} from '../src/api/generated/types.gen';
import { raceSplitPlanQueryKey } from '../src/api/raceSplitPlan';
import { createAppRouter } from '../src/router';
import { elevationProfileCharts } from './support/fakeElevationProfileChart';

vi.mock('../src/features/race-split-plan/elevationProfileChart', () =>
  import('./support/fakeElevationProfileChart').then((m) => m.fakeElevationProfileChartModule),
);

const runner: UserResponseDto = { id: 1, username: 'runner_bob', email: 'bob@example.com' };

const COURSE: ActivityDetailDto = {
  id: 42,
  title: 'Riverside Half',
  sport: 'RUN',
  startedAt: '2026-03-01T08:00:00Z',
  distance: 21.3,
  timeTakenSeconds: 6300,
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
};

const prediction = (
  distance: RacePredictionDto['distance'],
  meters: number,
  predictedSeconds: number,
): RacePredictionDto => ({
  distance,
  meters,
  vdotSeconds: predictedSeconds,
  predictedSeconds,
  enduranceFactor: 1,
  courseFactor: 1,
});

/** A Half predicted at 1:30:00 on this course's 120 m climb. */
const PROGNOSIS: RacePrognosisResponseDto = {
  current: {
    day: '2026-11-24',
    effectiveVo2max: 52,
    marathonShapePct: 40,
    predictions: [
      prediction('FIVE_K', 5000, 1200),
      prediction('TEN_K', 10000, 2500),
      prediction('HALF_MARATHON', 21097.5, 5400),
      prediction('MARATHON', 42195, 11400),
    ],
  },
  days: [],
};

/**
 * The server's plan for a course of [courseM] with 120 m of climb, all of it in the first split: even splits
 * in the asked unit (the planner itself is tested on the server).
 */
function servedPlan(url: URL, courseM: number): RaceSplitPlanResponseDto {
  const goalSeconds = Number(url.searchParams.get('goalSeconds'));
  const unit = url.searchParams.get('unit') as RaceSplitPlanResponseDto['unit'];
  const biasPct = Number(url.searchParams.get('biasPct'));
  const unitM = unit === 'KM' ? 1000 : 1609.344;
  const splits: RaceSplitDto[] = [];
  for (let start = 0, index = 1; start < courseM; start += unitM, index++) {
    const end = Math.min(courseM, start + unitM);
    splits.push({
      index,
      startM: start,
      endM: end,
      distanceM: end - start,
      targetSeconds: (goalSeconds * (end - start)) / courseM,
      paceSecondsPerUnit: (goalSeconds * unitM) / courseM,
      cumulativeSeconds: (goalSeconds * end) / courseM,
      elevationGainM: index === 1 ? 120 : 0,
      elevationLossM: 0,
      avgGradePct: index === 1 ? (120 / (end - start)) * 100 : 0,
    });
  }
  return {
    activityId: 42,
    courseDistanceM: courseM,
    goalSeconds,
    unit,
    biasPct,
    elevationGainM: 120,
    splits,
    profile: [
      { distanceM: 0, altitudeM: 10 },
      { distanceM: courseM / 2, altitudeM: 130 },
      { distanceM: courseM, altitudeM: 130 },
    ],
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function stubApi({
  courseM = 21300,
  prognosis = PROGNOSIS,
}: {
  courseM?: number;
  prognosis?: RacePrognosisResponseDto;
} = {}) {
  const requests: URL[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://localhost');
      requests.push(url);
      switch (url.pathname) {
        case '/api/activities/42':
          return json(COURSE);
        case '/api/activities/42/race-split-plan':
          return json(servedPlan(url, courseM));
        case '/api/athlete/race-prognosis':
          return json(prognosis);
        default:
          return json({ kind: 'RESOURCE_NOT_FOUND', message: 'Not found' }, 404);
      }
    }),
  );
  return {
    plans: () =>
      requests
        .filter((url) => url.pathname.endsWith('/race-split-plan'))
        .map((url) => Object.fromEntries(url.searchParams)),
    prognoses: () =>
      requests
        .filter((url) => url.pathname === '/api/athlete/race-prognosis')
        .map((url) => Object.fromEntries(url.searchParams)),
  };
}

function renderPlan() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const history = createMemoryHistory({ initialEntries: ['/activities/42/split-plan'] });
  const router = createAppRouter(
    { queryClient, auth: { user: runner, isLoading: false } },
    history,
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

const splitsTable = () => screen.findByRole('table', { name: 'Splits' });

/** Each body row's cells, as text. */
function rows(table: HTMLElement): string[][] {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) =>
      within(row)
        .getAllByRole('cell')
        .map((cell) => cell.textContent ?? ''),
    );
}

describe('/activities/$activityId/split-plan', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    elevationProfileCharts.reset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("plans the course at the Activity's own time in kilometres with an even split", async () => {
    const api = stubApi();
    renderPlan();

    const table = await splitsTable();
    expect(api.plans()[0]).toEqual({ goalSeconds: '6300', unit: 'KM', biasPct: '0' });
    expect(screen.getByLabelText('Goal Time (h:mm:ss)')).toHaveValue('1:45:00');
    // 6300 s over 21.3 km: 295.77 s per km; 22 splits, the last 300 m.
    const cells = rows(table);
    expect(cells).toHaveLength(22);
    expect(cells[0]).toEqual(['1', '1.00', '4:56', '4:56', '4:56', '+120 / −0 m', '+12.0%']);
    expect(cells[21]).toEqual(['22', '21.30', '1:29', '4:56', '1:45:00', '+0 / −0 m', '0.0%']);
    expect(screen.getByRole('link', { name: 'Riverside Half' })).toHaveAttribute(
      'href',
      '/activities/42',
    );
  });

  it('draws the elevation profile in the plan unit with a line at each split boundary', async () => {
    stubApi();
    renderPlan();

    await waitFor(() => expect(elevationProfileCharts.live()).toHaveLength(1));
    const { data, theme } = elevationProfileCharts.live()[0]!.options;
    expect(theme).toBe('light');
    expect(data.x).toEqual([0, 10.65, 21.3]);
    expect(data.altitude).toEqual([10, 130, 130]);
    expect(data.boundaries).toHaveLength(21);
    expect(data.unitLabel).toBe('km');
  });

  it("pre-fills the Goal Time from the Race Prognosis, derated for the course's climb", async () => {
    const api = stubApi();
    renderPlan();
    const user = userEvent.setup();

    // 5400 × (21300 / 21097.5)^1.06 = 5454.96 s.
    const usePredicted = await screen.findByRole('button', { name: /Use predicted time/ });
    expect(usePredicted).toHaveTextContent('1:30:55 from Half Marathon');
    expect(api.prognoses()).toEqual([{ elevationGainM: '120' }]);

    await user.click(usePredicted);

    expect(screen.getByLabelText('Goal Time (h:mm:ss)')).toHaveValue('1:30:55');
    await waitFor(() =>
      expect(api.plans().at(-1)).toEqual({ goalSeconds: '5455', unit: 'KM', biasPct: '0' }),
    );
    await waitFor(() =>
      expect(rows(screen.getByRole('table', { name: 'Splits' }))[21]![4]).toBe('1:30:55'),
    );
  });

  it('offers no predicted time when the course is far from every race distance', async () => {
    stubApi({ courseM: 30000 });
    renderPlan();

    await splitsTable();
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /Use predicted time/ })).toBeNull(),
    );
    expect(screen.getByRole('button', { name: 'Apply' })).toBeInTheDocument();
  });

  it('offers no predicted time without a Race Prognosis', async () => {
    const api = stubApi({ prognosis: { current: null, days: [] } });
    renderPlan();

    await splitsTable();
    await waitFor(() => expect(api.prognoses()).toHaveLength(1));
    expect(screen.queryByRole('button', { name: /Use predicted time/ })).toBeNull();
  });

  it('re-plans for a typed Goal Time, and refuses a malformed one', async () => {
    const api = stubApi();
    renderPlan();
    const user = userEvent.setup();
    await splitsTable();
    const input = screen.getByLabelText('Goal Time (h:mm:ss)');

    await user.clear(input);
    await user.type(input, 'soon');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(screen.getByText(/Enter a time as h:mm:ss/)).toBeInTheDocument();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(api.plans()).toHaveLength(1);

    await user.clear(input);
    await user.type(input, '1:40:00');
    await user.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() =>
      expect(api.plans().at(-1)).toEqual({ goalSeconds: '6000', unit: 'KM', biasPct: '0' }),
    );
    expect(screen.queryByText(/Enter a time as h:mm:ss/)).toBeNull();
  });

  it('plans in miles', async () => {
    const api = stubApi();
    renderPlan();
    const user = userEvent.setup();
    await splitsTable();

    await user.click(screen.getByRole('button', { name: 'Mile' }));

    await waitFor(() => expect(api.plans().at(-1)).toMatchObject({ unit: 'MILE' }));
    expect(screen.getByRole('button', { name: 'Mile' })).toHaveAttribute('aria-pressed', 'true');
    // 21.3 km = 13 full miles and 378.528 m.
    await waitFor(() =>
      expect(rows(screen.getByRole('table', { name: 'Splits' }))).toHaveLength(14),
    );
    expect(screen.getByRole('columnheader', { name: 'Pace (/mi)' })).toBeInTheDocument();
  });

  it('asks for a Split Bias from the slider', async () => {
    const api = stubApi();
    renderPlan();
    await splitsTable();

    const slider = screen.getByRole('slider', { name: /Split Bias/ });
    expect(slider).toHaveAttribute('min', '-5');
    expect(slider).toHaveAttribute('max', '5');
    // jsdom has no slider dragging; a change event is what the browser sends.
    fireEvent.change(slider, { target: { value: '2.5' } });

    await waitFor(() => expect(api.plans().at(-1)).toMatchObject({ biasPct: '2.5' }));
    expect(
      screen.getByText(/\+2.5% \(positive split\)/, { selector: 'label span' }),
    ).toBeInTheDocument();
  });

  it('copies the splits to the clipboard as tab-separated text', async () => {
    stubApi();
    renderPlan();
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    await splitsTable();

    await user.click(screen.getByRole('button', { name: 'Copy splits' }));

    expect(writeText).toHaveBeenCalledTimes(1);
    const tsv = writeText.mock.calls[0]![0].split('\n');
    expect(tsv[0]).toBe(
      'Split\tTo (km)\tTarget\tPace (/km)\tCumulative\tGain (m)\tLoss (m)\tGrade (%)',
    );
    expect(tsv[1]).toBe('1\t1.00\t4:56\t4:56\t4:56\t120\t0\t12.0');
    expect(tsv).toHaveLength(23);
    expect(await screen.findByText('Splits copied')).toBeInTheDocument();
  });

  it('keeps a refused plan on the page as an error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = new URL(String(input), 'http://localhost');
        if (url.pathname === '/api/activities/42') return json(COURSE);
        return json(
          {
            kind: 'VALIDATION_FAILED',
            message: 'Validation failed',
            fieldErrors: { activityId: 'The Activity has no GPS route with distance' },
          },
          400,
        );
      }),
    );
    renderPlan();

    expect(await screen.findByText(/This Race Split Plan could not be made/)).toBeInTheDocument();
  });

  it('hides the navigation and controls when printed', async () => {
    stubApi();
    renderPlan();
    await splitsTable();

    expect(screen.getByRole('region', { name: 'Plan settings' })).toHaveClass('print:hidden');
    expect(screen.getByRole('button', { name: 'Copy splits' }).parentElement).toHaveClass(
      'print:hidden',
    );
    expect(screen.getByRole('link', { name: /Stridehearth/ }).closest('header')).toHaveClass(
      'print:hidden',
    );
  });

  it("keeps the page's spacing between the elevation profile and the splits", async () => {
    stubApi();
    renderPlan();
    await splitsTable();

    // A margin utility on the figure would override the page's space-y gap and butt the two cards together.
    const figure = screen.getByRole('figure', { name: 'Elevation profile' });
    expect(figure.parentElement).toHaveClass('space-y-6');
    expect(figure.className).not.toMatch(/(^|\s)m[by]?-/);
  });
});

describe('Race Split Plan query', () => {
  it("is keyed under the athlete's Activities, so a change to the course refreshes it", () => {
    const request = { goalSeconds: 5455, unit: 'KM', biasPct: 0 } as const;
    expect(raceSplitPlanQueryKey(7, 42, request)).toEqual([
      'activities',
      7,
      'race-split-plan',
      42,
      request,
    ]);
    expect(raceSplitPlanQueryKey(8, 42, request)).not.toEqual(
      raceSplitPlanQueryKey(7, 42, request),
    );
  });
});
