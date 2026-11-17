import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dashboardQueryKey } from '../src/api/dashboard';
import type {
  ActivityDetailDto,
  ActivitySummaryDto,
  PagedResponseDtoActivitySummaryDto,
  UserResponseDto,
} from '../src/api/generated/types.gen';
import { createAppRouter } from '../src/router';

const runner: UserResponseDto = { id: 1, username: 'runner_bob', email: 'bob@example.com' };

function activity(overrides: Partial<ActivitySummaryDto> = {}): ActivitySummaryDto {
  return {
    id: 1,
    title: 'Morning Run',
    sport: 'RUN',
    startedAt: '2026-03-01T08:00:00Z',
    distance: 10,
    timeTakenSeconds: 3000,
    averagePaceKmh: 12,
    averageHeartRate: 152,
    calories: 640,
    source: 'MANUAL',
    hasProtectedMetrics: false,
    ...overrides,
  };
}

/** The `GET /api/activities/{id}` body for an activity with no Laps, history or Best Efforts. */
function detailOf(summary: ActivitySummaryDto): ActivityDetailDto {
  return {
    ...summary,
    ownerUsername: 'runner_bob',
    laps: [],
    history: [],
    bestEfforts: [],
    runMetrics: null,
  };
}

/** `GET /api/activities/{id}/telemetry` for an activity without Activity Details. */
const noActivityDetails = () =>
  json({ kind: 'RESOURCE_NOT_FOUND', message: 'No Activity Details' }, 404);

function pageOf(
  items: ActivitySummaryDto[],
  page: number,
  totalElements: number,
  size = 10,
): PagedResponseDtoActivitySummaryDto {
  const totalPages = Math.ceil(totalElements / size);
  return {
    items,
    page,
    size,
    totalElements,
    totalPages,
    hasNext: page + 1 < totalPages,
    hasPrevious: page > 0,
  };
}

interface RecordedRequest {
  method: string;
  path: string;
  search: URLSearchParams;
  body: BodyInit | null | undefined;
}

type Handler = (request: RecordedRequest) => Response | Promise<Response>;

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A fake `/api` server: routes are keyed by `METHOD /path` (query string excluded). */
function stubApi(routes: Record<string, Handler>) {
  const requests: RecordedRequest[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input.toString(), 'http://localhost');
    const request: RecordedRequest = {
      method: init?.method?.toUpperCase() ?? 'GET',
      path: url.pathname,
      search: url.searchParams,
      body: init?.body,
    };
    requests.push(request);
    const handler = routes[`${request.method} ${request.path}`];
    if (!handler) throw new Error(`Unexpected request ${request.method} ${url.pathname}`);
    return handler(request);
  });
  vi.stubGlobal('fetch', fetchMock);
  return requests;
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

/** Caches a **Dashboard** for the athlete so tests can observe its invalidation. */
function primeDashboard(queryClient: QueryClient) {
  queryClient.setQueryData(dashboardQueryKey(runner.id), { personalRecordSlots: [] });
}

const dashboardInvalidated = (queryClient: QueryClient) =>
  queryClient.getQueryState(dashboardQueryKey(runner.id))?.isInvalidated;

beforeEach(() => {
  vi.unstubAllGlobals();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('/activities', () => {
  it('renders a page of activities with formatted tabular metrics and actions', async () => {
    stubApi({
      'GET /api/activities': () =>
        json(
          pageOf(
            [
              activity({ id: 7, title: 'Tempo Tuesday' }),
              activity({
                id: 8,
                title: 'Hill Hike',
                sport: 'HIKE',
                averageHeartRate: null,
                calories: null,
                averagePaceKmh: null,
              }),
            ],
            0,
            2,
          ),
        ),
    });
    renderApp('/activities');

    const row = (await screen.findByText('Tempo Tuesday')).closest('tr')!;
    expect(within(row).getByText('Run')).toBeInTheDocument();
    expect(within(row).getByText('1 Mar 2026')).toBeInTheDocument();
    expect(within(row).getByText('10.00 km')).toHaveClass('tabular-nums');
    expect(within(row).getByText('50:00')).toHaveClass('tabular-nums');
    expect(within(row).getByText('05:00 /km')).toHaveClass('tabular-nums');
    expect(within(row).getByText('152 bpm')).toHaveClass('tabular-nums');
    expect(within(row).getByText('640 kcal')).toHaveClass('tabular-nums');
    expect(within(row).getByRole('link', { name: /view tempo tuesday/i })).toHaveAttribute(
      'href',
      '/activities/7',
    );
    expect(within(row).getByRole('link', { name: /edit tempo tuesday/i })).toHaveAttribute(
      'href',
      '/activities/7/edit',
    );
    expect(within(row).getByRole('button', { name: /delete tempo tuesday/i })).toBeInTheDocument();

    const hikeRow = screen.getByText('Hill Hike').closest('tr')!;
    expect(within(hikeRow).getByText('Hike')).toBeInTheDocument();
    expect(within(hikeRow).getAllByText('—')).toHaveLength(3);
  });

  it('pages through activities with next and previous controls', async () => {
    const requests = stubApi({
      'GET /api/activities': ({ search }) =>
        search.get('page') === '1'
          ? json(pageOf([activity({ id: 11, title: 'Older Run' })], 1, 11))
          : json(pageOf([activity({ id: 1, title: 'Newer Run' })], 0, 11)),
    });
    const user = userEvent.setup();
    const { history } = renderApp('/activities');

    await screen.findByText('Newer Run');
    expect(screen.getByText(/page 1 of 2/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: /next/i }));

    await screen.findByText('Older Run');
    expect(history.location.search).toContain('page=1');
    expect(screen.getByText(/page 2 of 2/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();
    expect(requests.map((r) => r.search.get('page'))).toEqual(['0', '1']);
    expect(requests.every((r) => r.search.get('size') === '10')).toBe(true);
  });

  it('confirms deletion in an accessible alert dialog and removes the activity from the list', async () => {
    let items = [
      activity({ id: 7, title: 'Tempo Tuesday' }),
      activity({ id: 8, title: 'Long Sunday' }),
    ];
    const requests = stubApi({
      'GET /api/activities': () => json(pageOf(items, 0, items.length)),
      'DELETE /api/activities/7': () => {
        items = items.filter((a) => a.id !== 7);
        return new Response(null, { status: 204 });
      },
    });
    const user = userEvent.setup();
    const { queryClient } = renderApp('/activities');
    primeDashboard(queryClient);

    await user.click(await screen.findByRole('button', { name: /delete tempo tuesday/i }));
    const dialog = await screen.findByRole('alertdialog');
    expect(dialog).toHaveAccessibleName(/delete activity/i);
    expect(dialog).toHaveTextContent('Tempo Tuesday');

    await user.click(within(dialog).getByRole('button', { name: /cancel/i }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(requests.some((r) => r.method === 'DELETE')).toBe(false);

    await user.click(screen.getByRole('button', { name: /delete tempo tuesday/i }));
    await user.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', { name: /^delete$/i }),
    );

    await waitFor(() => expect(screen.queryByText('Tempo Tuesday')).not.toBeInTheDocument());
    expect(screen.getByText('Long Sunday')).toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(requests.filter((r) => r.method === 'DELETE').map((r) => r.path)).toEqual([
      '/api/activities/7',
    ]);
    // Deleting may recalibrate Personal Records, so the cached Dashboard must be refetched.
    expect(dashboardInvalidated(queryClient)).toBe(true);
  });

  it('rejects a response that breaks the API contract', async () => {
    stubApi({ 'GET /api/activities': () => json({ items: 'not a list' }) });
    renderApp('/activities');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The server sent an unexpected response',
    );
  });

  it('keeps the dialog open with an error when deletion fails', async () => {
    stubApi({
      'GET /api/activities': () =>
        json(pageOf([activity({ id: 7, title: 'Tempo Tuesday' })], 0, 1)),
      'DELETE /api/activities/7': () =>
        json({ kind: 'RESOURCE_NOT_FOUND', message: 'Activity 7 not found' }, 404),
    });
    const user = userEvent.setup();
    renderApp('/activities');

    await user.click(await screen.findByRole('button', { name: /delete tempo tuesday/i }));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: /^delete$/i }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Activity 7 not found');
  });

  it('shows an empty state when the athlete has no activities', async () => {
    stubApi({ 'GET /api/activities': () => json(pageOf([], 0, 0)) });
    renderApp('/activities');

    expect(await screen.findByText(/no activities yet/i)).toBeInTheDocument();
  });
});

function bodyOf(request: RecordedRequest | undefined): unknown {
  return JSON.parse(String(request?.body));
}

describe('/activities/add_activity manual creation', () => {
  it('logs a manual activity and navigates to it', async () => {
    const requests = stubApi({
      'POST /api/activities': () => json(activity({ id: 31, title: 'Lunch Run' }), 201),
    });
    const user = userEvent.setup();
    const { history } = renderApp('/activities/add_activity');

    await user.type(await screen.findByLabelText(/title/i), 'Lunch Run');
    await user.selectOptions(screen.getByLabelText(/sport/i), 'WALK');
    await user.type(screen.getByLabelText(/start time/i), '2026-05-02T12:30');
    await user.type(screen.getByLabelText(/distance/i), '8.5');
    await user.type(screen.getByLabelText(/duration/i), '45:00');
    await user.type(screen.getByLabelText(/average heart rate/i), '148');
    await user.click(screen.getByRole('button', { name: /save activity/i }));

    await waitFor(() => expect(history.location.pathname).toBe('/activities/31'));
    const post = requests.find((r) => r.method === 'POST');
    expect(bodyOf(post)).toEqual({
      title: 'Lunch Run',
      sport: 'WALK',
      startedAt: new Date(2026, 4, 2, 12, 30).toISOString(),
      distance: 8.5,
      timeTakenSeconds: 2700,
      averagePaceKmh: null,
      averageHeartRate: 148,
      calories: null,
    });
  });

  it('shows client-side validation errors without contacting the server', async () => {
    const requests = stubApi({});
    const user = userEvent.setup();
    renderApp('/activities/add_activity');

    await user.click(await screen.findByRole('button', { name: /save activity/i }));

    expect(await screen.findByText('Title cannot be empty')).toBeInTheDocument();
    expect(screen.getByText('Start time is required')).toBeInTheDocument();
    expect(screen.getByText('Distance must be greater than zero')).toBeInTheDocument();
    expect(screen.getByLabelText(/title/i)).toHaveAttribute('aria-invalid', 'true');
    expect(requests).toHaveLength(0);
  });

  it('shows server-side validation errors against the offending fields', async () => {
    stubApi({
      'POST /api/activities': () =>
        json(
          {
            kind: 'VALIDATION_FAILED',
            message: 'Validation failed',
            fieldErrors: { title: 'Title must be between 1 and 255 characters' },
          },
          400,
        ),
    });
    const user = userEvent.setup();
    const { history } = renderApp('/activities/add_activity');

    await user.type(await screen.findByLabelText(/title/i), 'x');
    await user.type(screen.getByLabelText(/start time/i), '2026-05-02T12:30');
    await user.type(screen.getByLabelText(/distance/i), '5');
    await user.type(screen.getByLabelText(/duration/i), '25:00');
    await user.click(screen.getByRole('button', { name: /save activity/i }));

    expect(
      await screen.findByText('Title must be between 1 and 255 characters'),
    ).toBeInTheDocument();
    expect(history.location.pathname).toBe('/activities/add_activity');
  });
});

describe('/activities/add_activity single FIT upload', () => {
  const fitFile = () => new File([new Uint8Array([14, 16])], 'run.fit', { type: '' });

  async function uploadFit(response: Response) {
    const requests = stubApi({
      'POST /api/activities/upload': () => response,
      'GET /api/activities': () => json(pageOf([activity({ id: 5, title: 'Listed Run' })], 0, 1)),
    });
    const user = userEvent.setup();
    const app = renderApp('/activities/add_activity');
    await user.upload(await screen.findByLabelText(/activity \.fit file/i), fitFile());
    await user.click(screen.getByRole('button', { name: /upload/i }));
    return { ...app, requests };
  }

  it('routes a CREATED activity to its page', async () => {
    const { history, requests } = await uploadFit(json({ kind: 'CREATED', activityId: 42 }));

    await waitFor(() => expect(history.location.pathname).toBe('/activities/42'));
    const upload = requests.find((r) => r.path === '/api/activities/upload');
    expect(upload?.body).toBeInstanceOf(FormData);
    expect((upload?.body as FormData).get('file')).toHaveProperty('name', 'run.fit');
  });

  it('routes an UPGRADED activity to its page', async () => {
    const { history } = await uploadFit(json({ kind: 'UPGRADED', activityId: 9 }));

    await waitFor(() => expect(history.location.pathname).toBe('/activities/9'));
  });

  it('routes a DUPLICATE file to the activity list with a notification banner', async () => {
    const { history } = await uploadFit(json({ kind: 'DUPLICATE' }));

    await waitFor(() => expect(history.location.pathname).toBe('/activities'));
    expect(await screen.findByRole('status')).toHaveTextContent(
      'That activity was already imported',
    );
    expect(screen.getByText('Listed Run')).toBeInTheDocument();
  });

  it('routes a MULTI_PROCESSED file to the activity list with a notification banner', async () => {
    const { history } = await uploadFit(json({ kind: 'MULTI_PROCESSED', processedCount: 3 }));

    await waitFor(() => expect(history.location.pathname).toBe('/activities'));
    expect(await screen.findByRole('status')).toHaveTextContent('Processed 3 activities');
  });

  it('shows an inline error for an INVALID_ACTIVITY_FILE', async () => {
    const { history } = await uploadFit(
      json({ kind: 'INVALID_ACTIVITY_FILE', message: 'FIT file contains no valid sessions' }, 400),
    );

    const form = screen.getByRole('form', { name: /upload a \.fit file/i });
    expect(await within(form).findByRole('alert')).toHaveTextContent(
      'FIT file contains no valid sessions',
    );
    expect(history.location.pathname).toBe('/activities/add_activity');
  });
});

describe('/activities/$activityId/edit', () => {
  it('edits every field of a manual activity without Protected Metrics', async () => {
    const original = activity({
      id: 12,
      title: 'Easy Run',
      startedAt: new Date(2026, 0, 9, 6, 5).toISOString(),
      distance: 5,
      timeTakenSeconds: 1500,
      averagePaceKmh: null,
      averageHeartRate: null,
      calories: null,
    });
    const requests = stubApi({
      'GET /api/activities/12': () => json(detailOf(original)),
      'GET /api/activities/12/telemetry': noActivityDetails,
      'PUT /api/activities/12': () => json({ ...original, distance: 6 }),
    });
    const user = userEvent.setup();
    const { history } = renderApp('/activities/12/edit');

    const distance = await screen.findByLabelText(/distance/i);
    expect(screen.getByLabelText(/title/i)).toHaveValue('Easy Run');
    expect(screen.getByLabelText(/duration/i)).toHaveValue('25:00');
    expect(distance).not.toHaveAttribute('readonly');
    await user.clear(distance);
    await user.type(distance, '6');
    await user.click(screen.getByRole('button', { name: /update activity/i }));

    await waitFor(() => expect(history.location.pathname).toBe('/activities/12'));
    expect(bodyOf(requests.find((r) => r.method === 'PUT'))).toEqual({
      title: 'Easy Run',
      sport: 'RUN',
      startedAt: original.startedAt,
      distance: 6,
      timeTakenSeconds: 1500,
      averagePaceKmh: null,
      averageHeartRate: null,
      calories: null,
    });
  });

  it('locks Protected Metrics as read-only and sends only the title and sport', async () => {
    const imported = activity({
      id: 13,
      title: 'Imported Run',
      source: 'FIT_UPLOAD',
      hasProtectedMetrics: true,
    });
    const requests = stubApi({
      'GET /api/activities/13': () => json(detailOf(imported)),
      'GET /api/activities/13/telemetry': noActivityDetails,
      'PUT /api/activities/13': () => json({ ...imported, title: 'Renamed', sport: 'HIKE' }),
    });
    const user = userEvent.setup();
    const { history, queryClient } = renderApp('/activities/13/edit');
    primeDashboard(queryClient);

    const title = await screen.findByLabelText(/title/i);
    expect(screen.getByText(/protected metrics/i)).toBeInTheDocument();
    for (const label of [
      /start time/i,
      /distance/i,
      /duration/i,
      /average speed/i,
      /average heart rate/i,
      /calories/i,
    ]) {
      expect(screen.getByLabelText(label)).toHaveAttribute('readonly');
    }
    expect(screen.getByLabelText(/distance/i)).toHaveValue('10');
    expect(screen.getByLabelText(/sport/i)).toBeEnabled();
    expect(screen.queryByLabelText(/activity \.fit file/i)).not.toBeInTheDocument();

    await user.clear(title);
    await user.type(title, 'Renamed');
    await user.selectOptions(screen.getByLabelText(/sport/i), 'HIKE');
    await user.click(screen.getByRole('button', { name: /update activity/i }));

    await waitFor(() => expect(history.location.pathname).toBe('/activities/13'));
    expect(bodyOf(requests.find((r) => r.method === 'PUT'))).toEqual({
      title: 'Renamed',
      sport: 'HIKE',
    });
    // Changing the sport away from RUN may recalibrate Personal Records.
    expect(dashboardInvalidated(queryClient)).toBe(true);
  });

  it('sends an untouched start time back unchanged, seconds included, on a title-only edit', async () => {
    const original = activity({ id: 14, startedAt: '2026-01-09T06:05:37Z' });
    const requests = stubApi({
      'GET /api/activities/14': () => json(detailOf(original)),
      'GET /api/activities/14/telemetry': noActivityDetails,
      'PUT /api/activities/14': () => json({ ...original, title: 'Renamed' }),
    });
    const user = userEvent.setup();
    const { history } = renderApp('/activities/14/edit');

    const title = await screen.findByLabelText(/title/i);
    await user.clear(title);
    await user.type(title, 'Renamed');
    await user.click(screen.getByRole('button', { name: /update activity/i }));

    await waitFor(() => expect(history.location.pathname).toBe('/activities/14'));
    const sent = bodyOf(requests.find((r) => r.method === 'PUT')) as { startedAt: string };
    expect(new Date(sent.startedAt).getTime()).toBe(new Date(original.startedAt).getTime());
  });

  it('shows an error when the activity cannot be found', async () => {
    stubApi({
      'GET /api/activities/99': () =>
        json({ kind: 'RESOURCE_NOT_FOUND', message: 'Activity 99 not found' }, 404),
    });
    renderApp('/activities/99/edit');

    expect(await screen.findByRole('alert')).toHaveTextContent('Activity 99 not found');
  });
});
