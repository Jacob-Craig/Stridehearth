import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AthleteThresholdsResponseDto, UserResponseDto } from '../src/api/generated/types.gen';
import { currentUserQueryOptions } from '../src/auth/AuthContext';
import { formatPace, parsePace } from '../src/features/settings/pace';
import { createAppRouter } from '../src/router';

const runner: UserResponseDto = {
  id: 1,
  username: 'runner_bob',
  email: 'bob@example.com',
  timeZone: 'Europe/London',
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

function renderAt(path: string, user: UserResponseDto) {
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
  return { router, queryClient };
}

const empty: AthleteThresholdsResponseDto = {
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
};

const populated: AthleteThresholdsResponseDto = {
  entries: [
    { id: 3, kind: 'LTHR', effectiveDate: '2026-06-01', value: 170 },
    { id: 2, kind: 'THRESHOLD_PACE', effectiveDate: '2026-01-01', value: 1000 / 240 },
    { id: 1, kind: 'LTHR', effectiveDate: '2026-01-01', value: 160 },
  ],
  current: {
    maxHr: null,
    restingHr: null,
    lthr: 170,
    thresholdSpeedMps: 1000 / 240,
    criticalPowerWatts: null,
  },
  heartRateZones: [
    { zone: 1, lowerBpm: null, upperBpm: 145, seilerZone: 1 },
    { zone: 2, lowerBpm: 145, upperBpm: 153, seilerZone: 1 },
    { zone: 3, lowerBpm: 153, upperBpm: 162, seilerZone: 2 },
    { zone: 4, lowerBpm: 162, upperBpm: 170, seilerZone: 2 },
    { zone: 5, lowerBpm: 170, upperBpm: null, seilerZone: 3 },
  ],
  paceZones: [
    { zone: 1, lowerSpeedMps: null, upperSpeedMps: 1000 / 309.6, seilerZone: 1 },
    { zone: 2, lowerSpeedMps: 1000 / 309.6, upperSpeedMps: 1000 / 273.6, seilerZone: 1 },
    { zone: 3, lowerSpeedMps: 1000 / 273.6, upperSpeedMps: 1000 / 254.4, seilerZone: 2 },
    { zone: 4, lowerSpeedMps: 1000 / 254.4, upperSpeedMps: 1000 / 237.6, seilerZone: 2 },
    { zone: 5, lowerSpeedMps: 1000 / 237.6, upperSpeedMps: null, seilerZone: 3 },
  ],
  proposals: [{ id: 9, kind: 'LTHR', value: 172, effectiveDate: '2026-07-01', source: 'CS_FIT' }],
};

describe('pace conversion', () => {
  it('reads 4:00 per km as 1000 / 240 = 4.1667 m/s', () => {
    expect(parsePace('4:00')).toBeCloseTo(4.1667, 4);
    expect(parsePace('5:30')).toBeCloseTo(1000 / 330, 9);
  });

  it('refuses text that is not minutes and seconds', () => {
    expect(parsePace('4')).toBeNull();
    expect(parsePace('4:75')).toBeNull();
    expect(parsePace('abc')).toBeNull();
    expect(parsePace('0:00')).toBeNull();
  });

  it('shows a speed as minutes and seconds per km, rounding to the second', () => {
    expect(formatPace(1000 / 240)).toBe('4:00');
    expect(formatPace(1000 / 309.6)).toBe('5:10');
    expect(formatPace(1000 / 237.6)).toBe('3:58');
  });
});

describe('Settings', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('is reached from the app shell', async () => {
    stubApi({
      'GET /api/athlete/thresholds': () => ({ status: 200, body: empty }),
      'GET /api/dashboard': () => ({ status: 200, body: { personalRecordSlots: [] } }),
    });
    const { router } = renderAt('/dashboard', runner);

    await userEvent.setup().click(await screen.findByRole('link', { name: 'Settings' }));

    await waitFor(() => expect(router.state.location.pathname).toBe('/settings'));
    expect(await screen.findByRole('heading', { name: 'Settings', level: 1 })).toBeInTheDocument();
  });

  it('explains what is missing when no Thresholds are recorded', async () => {
    stubApi({ 'GET /api/athlete/thresholds': () => ({ status: 200, body: empty }) });
    renderAt('/settings', runner);

    expect(await screen.findByText(/no thresholds recorded yet/i)).toBeInTheDocument();
    expect(screen.getByText(/record an lthr to see your heart-rate zones/i)).toBeInTheDocument();
    expect(screen.getByText(/record a threshold pace to see your pace zones/i)).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /suggested updates/i })).not.toBeInTheDocument();
  });

  it('lists the history and shows the heart-rate and pace zones with their Seiler grouping', async () => {
    stubApi({ 'GET /api/athlete/thresholds': () => ({ status: 200, body: populated }) });
    renderAt('/settings', runner);

    const history = await screen.findByRole('table', { name: /threshold history/i });
    expect(within(history).getAllByRole('row')).toHaveLength(4);
    expect(within(history).getByText('4:00 /km')).toBeInTheDocument();
    expect(within(history).getByText('170 bpm')).toBeInTheDocument();

    const hrZones = screen.getByRole('table', { name: /heart-rate zones/i });
    const hrRows = within(hrZones).getAllByRole('row').slice(1);
    expect(hrRows.map((row) => within(row).getAllByRole('cell')[0]?.textContent)).toEqual([
      'Below 145 bpm',
      '145–152 bpm',
      '153–161 bpm',
      '162–169 bpm',
      '170 bpm and above',
    ]);
    expect(hrRows.map((row) => within(row).getAllByRole('cell')[1]?.textContent)).toEqual([
      '1',
      '1',
      '2',
      '2',
      '3',
    ]);

    const paceZones = screen.getByRole('table', { name: /pace zones/i });
    const paceRows = within(paceZones).getAllByRole('row').slice(1);
    expect(paceRows.map((row) => within(row).getAllByRole('cell')[0]?.textContent)).toEqual([
      'Slower than 5:10 /km',
      '5:10–4:34 /km',
      '4:34–4:14 /km',
      '4:14–3:58 /km',
      'Faster than 3:58 /km',
    ]);
  });

  it('records a threshold pace entered as minutes and seconds as a speed in m/s', async () => {
    const fetchMock = stubApi({
      'GET /api/athlete/thresholds': () => ({ status: 200, body: empty }),
      'POST /api/athlete/thresholds': (init) => {
        const sent = JSON.parse(String(init?.body));
        return { status: 200, body: { id: 5, ...sent } };
      },
    });
    renderAt('/settings', runner);
    const user = userEvent.setup();

    await user.selectOptions(await screen.findByLabelText('Threshold'), 'THRESHOLD_PACE');
    const date = screen.getByLabelText('Effective from');
    await user.clear(date);
    await user.type(date, '2026-03-01');
    await user.type(screen.getByLabelText(/pace \(min:sec per km\)/i), '4:00');
    await user.click(screen.getByRole('button', { name: 'Add entry' }));

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([url, init]) => url === '/api/athlete/thresholds' && init?.method === 'POST',
        ),
      ).toBe(true),
    );
    const post = fetchMock.mock.calls.find(
      ([url, init]) => url === '/api/athlete/thresholds' && init?.method === 'POST',
    );
    const body = JSON.parse(String(post?.[1]?.body));
    expect(body.kind).toBe('THRESHOLD_PACE');
    expect(body.effectiveDate).toBe('2026-03-01');
    expect(body.value).toBeCloseTo(4.1667, 4);
    expect(await screen.findByText(/recalculated in the background/i)).toBeInTheDocument();
  });

  it('refuses a threshold pace outside the allowed range in paces, without asking the Server', async () => {
    const fetchMock = stubApi({
      'GET /api/athlete/thresholds': () => ({ status: 200, body: empty }),
    });
    renderAt('/settings', runner);
    const user = userEvent.setup();

    await user.selectOptions(await screen.findByLabelText('Threshold'), 'THRESHOLD_PACE');
    const pace = screen.getByLabelText(/pace \(min:sec per km\)/i);
    await user.type(pace, '20:00');
    await user.click(screen.getByRole('button', { name: 'Add entry' }));
    expect(
      await screen.findByText('Enter a pace between 2:05 and 16:40 per km'),
    ).toBeInTheDocument();

    await user.clear(pace);
    await user.type(pace, '1:30');
    await user.click(screen.getByRole('button', { name: 'Add entry' }));
    expect(
      await screen.findByText('Enter a pace between 2:05 and 16:40 per km'),
    ).toBeInTheDocument();

    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  });

  it("shows the Server's field errors for a refused entry", async () => {
    stubApi({
      'GET /api/athlete/thresholds': () => ({ status: 200, body: empty }),
      'POST /api/athlete/thresholds': () => ({
        status: 400,
        body: {
          kind: 'VALIDATION_FAILED',
          message: 'Validation failed',
          fieldErrors: { value: 'Must be between 60.0 and 240.0 bpm' },
        },
      }),
    });
    renderAt('/settings', runner);
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText(/value \(bpm\)/i), '300');
    await user.click(screen.getByRole('button', { name: 'Add entry' }));

    expect(await screen.findByText('Must be between 60.0 and 240.0 bpm')).toBeInTheDocument();
  });

  it('deletes an entry from the history', async () => {
    const fetchMock = stubApi({
      'GET /api/athlete/thresholds': () => ({ status: 200, body: populated }),
      'DELETE /api/athlete/thresholds/3': () => ({ status: 204, body: null }),
    });
    renderAt('/settings', runner);

    await userEvent
      .setup()
      .click(await screen.findByRole('button', { name: 'Delete the LTHR entry from 1 Jun 2026' }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/athlete/thresholds/3',
        expect.objectContaining({ method: 'DELETE' }),
      ),
    );
  });

  it('accepts and dismisses suggested updates', async () => {
    const fetchMock = stubApi({
      'GET /api/athlete/thresholds': () => ({ status: 200, body: populated }),
      'POST /api/athlete/thresholds/proposals/9/accept': () => ({
        status: 200,
        body: { id: 6, kind: 'LTHR', effectiveDate: '2026-07-01', value: 172 },
      }),
      'POST /api/athlete/thresholds/proposals/9/dismiss': () => ({ status: 204, body: null }),
    });
    renderAt('/settings', runner);
    const user = userEvent.setup();

    const suggestions = await screen.findByRole('region', { name: /suggested updates/i });
    expect(within(suggestions).getByText(/172 bpm/)).toBeInTheDocument();
    await user.click(within(suggestions).getByRole('button', { name: 'Accept' }));
    await user.click(within(suggestions).getByRole('button', { name: 'Dismiss' }));

    await waitFor(() => {
      const urls = fetchMock.mock.calls.map(([url, init]) => `${init?.method ?? 'GET'} ${url}`);
      expect(urls).toContain('POST /api/athlete/thresholds/proposals/9/accept');
      expect(urls).toContain('POST /api/athlete/thresholds/proposals/9/dismiss');
    });
  });

  it('names a Critical Speed suggestion by its source, not its code', async () => {
    stubApi({
      'GET /api/athlete/thresholds': () => ({
        status: 200,
        body: {
          ...populated,
          proposals: [
            {
              id: 10,
              kind: 'THRESHOLD_PACE',
              value: 4.0,
              effectiveDate: '2026-11-19',
              source: 'CRITICAL_SPEED',
            },
          ],
        },
      }),
    });
    renderAt('/settings', runner);

    const suggestions = await screen.findByRole('region', { name: /suggested updates/i });
    expect(within(suggestions).getByText(/Critical Speed/)).toBeInTheDocument();
    expect(within(suggestions).queryByText(/CRITICAL_SPEED/)).not.toBeInTheDocument();
  });

  it("changes the athlete's time zone", async () => {
    const fetchMock = stubApi({
      'GET /api/athlete/thresholds': () => ({ status: 200, body: empty }),
      'PUT /api/athlete/time-zone': (init) => ({
        status: 200,
        body: { ...runner, timeZone: JSON.parse(String(init?.body)).timeZone },
      }),
    });
    const { queryClient } = renderAt('/settings', runner);
    const user = userEvent.setup();

    const select = await screen.findByLabelText('Time zone');
    expect(select).toHaveValue('Europe/London');
    await user.selectOptions(select, 'America/New_York');
    await user.click(screen.getByRole('button', { name: 'Save time zone' }));

    await waitFor(() =>
      expect(
        queryClient.getQueryData<UserResponseDto | null>(currentUserQueryOptions.queryKey)
          ?.timeZone,
      ).toBe('America/New_York'),
    );
    const put = fetchMock.mock.calls.find(([url]) => url === '/api/athlete/time-zone');
    expect(JSON.parse(String(put?.[1]?.body))).toEqual({ timeZone: 'America/New_York' });
  });

  it("sets an athlete's time zone from the browser once, when they have none", async () => {
    const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const fetchMock = stubApi({
      'GET /api/dashboard': () => ({ status: 200, body: { personalRecordSlots: [] } }),
      'GET /api/athlete/thresholds': () => ({ status: 200, body: empty }),
      'PUT /api/athlete/time-zone': (init) => ({
        status: 200,
        body: { ...runner, timeZone: JSON.parse(String(init?.body)).timeZone },
      }),
    });
    const { router, queryClient } = renderAt('/dashboard', { ...runner, timeZone: null });

    await waitFor(() =>
      expect(
        queryClient.getQueryData<UserResponseDto | null>(currentUserQueryOptions.queryKey)
          ?.timeZone,
      ).toBe(browserZone),
    );
    await router.navigate({ to: '/settings' });
    await screen.findByRole('heading', { name: 'Settings', level: 1 });

    const puts = fetchMock.mock.calls.filter(([url]) => url === '/api/athlete/time-zone');
    expect(puts).toHaveLength(1);
    expect(JSON.parse(String(puts[0]?.[1]?.body))).toEqual({ timeZone: browserZone });
  });

  it('leaves a time zone the athlete already has alone', async () => {
    const fetchMock = stubApi({
      'GET /api/athlete/thresholds': () => ({ status: 200, body: empty }),
    });
    renderAt('/settings', runner);

    await screen.findByRole('heading', { name: 'Settings', level: 1 });
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/athlete/time-zone')).toBe(false);
  });
});
