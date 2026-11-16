import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dashboardQueryKey } from '../src/api/dashboard';
import type {
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

function pageOf(
  items: ActivitySummaryDto[],
  { page = 0, size = 20, totalElements = items.length } = {},
): PagedResponseDtoActivitySummaryDto {
  const totalPages = Math.max(1, Math.ceil(totalElements / size));
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

/** Every matching **Activity** in one page, as `GET /api/activities?size=all` returns them. */
const allOf = (items: ActivitySummaryDto[]) => pageOf(items, { size: Math.max(1, items.length) });

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
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
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
    }),
  );
  return requests;
}

const listRequests = (requests: RecordedRequest[]) =>
  requests.filter((r) => r.method === 'GET' && r.path === '/api/activities');

const bodyOf = (request: RecordedRequest | undefined): unknown => JSON.parse(String(request?.body));

function renderApp(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(dashboardQueryKey(runner.id), { personalRecordSlots: [] });
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
  return { history, queryClient };
}

const dashboardInvalidated = (queryClient: QueryClient) =>
  queryClient.getQueryState(dashboardQueryKey(runner.id))?.isInvalidated;

/** jsdom has no layout; give scroll containers a viewport so the virtualizer has room to render. */
function giveElementsAViewport(heightPx: number) {
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientHeight');
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', {
    configurable: true,
    get: () => heightPx,
  });
  return () => {
    if (original) Object.defineProperty(HTMLElement.prototype, 'clientHeight', original);
  };
}

const checkbox = (name: RegExp | string) => screen.getByRole('checkbox', { name });

// The table (for size=all) and the confirmation list are virtualized, so every test needs a viewport.
let restoreLayout: () => void;

beforeEach(() => {
  vi.unstubAllGlobals();
  restoreLayout = giveElementsAViewport(600);
});

afterEach(() => {
  vi.unstubAllGlobals();
  restoreLayout();
});

describe('/activities/manage filtering', () => {
  it('loads the activities matching the filters in the URL and shows them in the filter controls', async () => {
    const requests = stubApi({
      'GET /api/activities': () =>
        json(pageOf([activity({ id: 4, title: 'Tempo Spin', sport: 'RIDE' })], { page: 1 })),
    });
    renderApp('/activities/manage?q=tempo&sport=RIDE&source=MANUAL&size=50&page=1');

    const row = (await screen.findByText('Tempo Spin')).closest('tr')!;
    expect(within(row).getByText('Ride')).toBeInTheDocument();
    expect(within(row).getByText('Manual')).toBeInTheDocument();

    const [request] = listRequests(requests);
    expect(Object.fromEntries(request!.search)).toEqual({
      q: 'tempo',
      sport: 'RIDE',
      source: 'MANUAL',
      size: '50',
      page: '1',
    });
    expect(screen.getByLabelText(/title contains/i)).toHaveValue('tempo');
    expect(screen.getByLabelText(/^sport$/i)).toHaveValue('RIDE');
    expect(screen.getByLabelText(/^source$/i)).toHaveValue('MANUAL');
    expect(screen.getByLabelText(/per page/i)).toHaveValue('50');
  });

  it('applies new filters to the URL, resetting to the first page', async () => {
    const requests = stubApi({
      'GET /api/activities': ({ search }) =>
        json(
          search.get('q') === 'hill'
            ? pageOf([activity({ id: 9, title: 'Hill Repeats' })])
            : pageOf([activity({ id: 1, title: 'Easy Run' })], { page: 2, totalElements: 60 }),
        ),
    });
    const user = userEvent.setup();
    const { history } = renderApp('/activities/manage?page=2');

    await screen.findByText('Easy Run');
    await user.type(screen.getByLabelText(/title contains/i), 'hill');
    await user.selectOptions(screen.getByLabelText(/^source$/i), 'FIT_UPLOAD');
    await user.selectOptions(screen.getByLabelText(/per page/i), '100');
    await user.click(screen.getByRole('button', { name: /apply filters/i }));

    await screen.findByText('Hill Repeats');
    const search = new URLSearchParams(history.location.search);
    expect(search.get('q')).toBe('hill');
    expect(search.get('source')).toBe('FIT_UPLOAD');
    expect(search.get('size')).toBe('100');
    expect(search.has('page')).toBe(false);
    expect(Object.fromEntries(listRequests(requests).at(-1)!.search)).toEqual({
      q: 'hill',
      source: 'FIT_UPLOAD',
      size: '100',
      page: '0',
    });
  });

  it('falls back to the defaults for invalid search params', async () => {
    const requests = stubApi({ 'GET /api/activities': () => json(pageOf([activity()])) });
    renderApp('/activities/manage?size=999&sport=SKYDIVE&page=-3');

    await screen.findByText('Morning Run');
    expect(Object.fromEntries(listRequests(requests)[0]!.search)).toEqual({
      size: '20',
      page: '0',
    });
  });

  it('pages through results while keeping the filters', async () => {
    stubApi({
      'GET /api/activities': ({ search }) =>
        json(
          search.get('page') === '1'
            ? pageOf([activity({ id: 21, title: 'Older Run' })], { page: 1, totalElements: 21 })
            : pageOf([activity({ id: 1, title: 'Newer Run' })], { totalElements: 21 }),
        ),
    });
    const user = userEvent.setup();
    const { history } = renderApp('/activities/manage?sport=RUN');

    await screen.findByText('Newer Run');
    await user.click(screen.getByRole('button', { name: /next/i }));

    await screen.findByText('Older Run');
    const search = new URLSearchParams(history.location.search);
    expect(search.get('page')).toBe('1');
    expect(search.get('sport')).toBe('RUN');
  });
});

describe('/activities/manage size=all virtualization & selection', () => {
  const many = Array.from({ length: 2000 }, (_, i) =>
    activity({ id: i + 1, title: `Activity ${i + 1}` }),
  );

  it('renders only a window of rows and selects or deselects thousands of activities at once', async () => {
    const requests = stubApi({
      'GET /api/activities': () => json(allOf(many)),
      'POST /api/activities/batch-delete': () => json({ affectedCount: 1999, matchedCount: 1999 }),
    });
    const user = userEvent.setup();
    renderApp('/activities/manage?size=all');

    await screen.findByText('Activity 1');
    const renderedRows = screen.getAllByRole('checkbox', { name: /^select activity \d+$/i });
    expect(renderedRows.length).toBeGreaterThan(0);
    expect(renderedRows.length).toBeLessThan(100);
    expect(screen.queryByText('Activity 2000')).not.toBeInTheDocument();
    expect(screen.getByText(/2,000 activities/i)).toBeInTheDocument();

    await user.click(checkbox(/select all activities/i));
    expect(screen.getByText('2,000 selected')).toBeInTheDocument();
    expect(checkbox('Select Activity 1')).toBeChecked();

    await user.click(checkbox('Select Activity 1'));
    expect(screen.getByText('1,999 selected')).toBeInTheDocument();
    expect(checkbox(/select all activities/i)).not.toBeChecked();
    expect(checkbox(/select all activities/i)).toHaveProperty('indeterminate', true);

    await user.click(screen.getByRole('button', { name: /batch delete/i }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: /continue/i }));

    // Every affected Activity is listed for confirmation, but only the rows in view are mounted.
    const affected = within(dialog).getByRole('list', { name: /affected activities/i });
    const listed = within(affected).getAllByRole('listitem');
    expect(listed.length).toBeGreaterThan(0);
    expect(listed.length).toBeLessThan(100);
    expect(listed[0]).toHaveAttribute('aria-setsize', '1999');
    expect(within(dialog).queryByText(/more$/i)).not.toBeInTheDocument();
    expect(within(affected).queryByText('Activity 2000')).not.toBeInTheDocument();
    // Scroll the list to its end (jsdom has no layout, so use the list's own rendered height).
    const scroller = affected.parentElement!;
    scroller.scrollTop = Number.parseFloat(affected.style.height);
    fireEvent.scroll(scroller);
    expect(await within(affected).findByText('Activity 2000')).toBeInTheDocument();
    expect(within(affected).queryByText('Activity 2')).not.toBeInTheDocument();
    await user.click(
      within(dialog).getByRole('button', { name: /permanently delete 1,999 activities/i }),
    );

    await waitFor(() =>
      expect(requests.some((r) => r.path === '/api/activities/batch-delete')).toBe(true),
    );
    const deleted = bodyOf(requests.find((r) => r.path === '/api/activities/batch-delete')) as {
      activityIds: number[];
    };
    expect(deleted.activityIds).toHaveLength(1999);
    expect(deleted.activityIds).not.toContain(1);
  });

  it('clears the whole selection from the select-all checkbox', async () => {
    stubApi({ 'GET /api/activities': () => json(allOf(many)) });
    const user = userEvent.setup();
    renderApp('/activities/manage?size=all');

    await screen.findByText('Activity 1');
    await user.click(checkbox(/select all activities/i));
    await user.click(checkbox(/select all activities/i));

    expect(screen.getByText('0 selected')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /batch modify/i })).toBeDisabled();
  });
});

describe('/activities/manage batch modify', () => {
  it('configures a new title and sport, confirms the affected activities, then updates them', async () => {
    let items = [
      activity({ id: 7, title: 'Tempo Tuesday' }),
      activity({ id: 8, title: 'Long Sunday' }),
      activity({ id: 9, title: 'Recovery Jog' }),
    ];
    const requests = stubApi({
      'GET /api/activities': () => json(pageOf(items)),
      'PATCH /api/activities/batch': () => {
        items = items.map((a) =>
          a.id === 9 ? a : { ...a, title: 'Club Session', sport: 'WALK' as const },
        );
        return json({ affectedCount: 2, matchedCount: 2 });
      },
    });
    const user = userEvent.setup();
    const { history, queryClient } = renderApp('/activities/manage?q=day&source=MANUAL');

    await screen.findByText('Tempo Tuesday');
    expect(screen.getByRole('button', { name: /batch modify/i })).toBeDisabled();
    await user.click(checkbox('Select Tempo Tuesday'));
    await user.click(checkbox('Select Long Sunday'));
    expect(screen.getByText('2 selected')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /batch modify/i }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveAccessibleName(/modify 2 activities/i);
    await user.type(within(dialog).getByLabelText(/new title/i), 'Club Session');
    await user.selectOptions(within(dialog).getByLabelText(/new sport/i), 'WALK');
    await user.click(within(dialog).getByRole('button', { name: /review changes/i }));

    expect(dialog).toHaveAccessibleName(/confirm/i);
    const affected = within(dialog).getByRole('list', { name: /affected activities/i });
    expect(
      within(affected)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['Tempo Tuesday', 'Long Sunday']);
    expect(dialog).toHaveTextContent('Club Session');
    expect(dialog).toHaveTextContent('Walk');
    expect(requests.some((r) => r.method === 'PATCH')).toBe(false);

    await user.click(within(dialog).getByRole('button', { name: /yes, update 2 activities/i }));

    expect(await screen.findByText('Updated 2 activities')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(bodyOf(requests.find((r) => r.method === 'PATCH'))).toEqual({
      activityIds: [7, 8],
      title: 'Club Session',
      sport: 'WALK',
    });
    expect(await screen.findAllByText('Club Session')).toHaveLength(2);
    expect(screen.getByText('0 selected')).toBeInTheDocument();
    expect(history.location.search).toContain('q=day');
    expect(history.location.search).toContain('source=MANUAL');
    expect(dashboardInvalidated(queryClient)).toBe(true);
  });

  it('requires a title or sport before review and can step back from confirmation', async () => {
    const requests = stubApi({
      'GET /api/activities': () => json(pageOf([activity({ id: 7, title: 'Tempo Tuesday' })])),
    });
    const user = userEvent.setup();
    renderApp('/activities/manage');

    await user.click(await screen.findByRole('checkbox', { name: 'Select Tempo Tuesday' }));
    await user.click(screen.getByRole('button', { name: /batch modify/i }));
    const dialog = await screen.findByRole('dialog');

    await user.click(within(dialog).getByRole('button', { name: /review changes/i }));
    expect(within(dialog).getByRole('alert')).toHaveTextContent(/new title or sport/i);

    await user.type(within(dialog).getByLabelText(/new title/i), 'Renamed');
    await user.click(within(dialog).getByRole('button', { name: /review changes/i }));
    await user.click(within(dialog).getByRole('button', { name: /back/i }));
    expect(within(dialog).getByLabelText(/new title/i)).toHaveValue('Renamed');

    await user.click(within(dialog).getByRole('button', { name: /cancel/i }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(requests.some((r) => r.method === 'PATCH')).toBe(false);
  });

  it('keeps the confirmation open with the server error when the update fails', async () => {
    stubApi({
      'GET /api/activities': () => json(pageOf([activity({ id: 7, title: 'Tempo Tuesday' })])),
      'PATCH /api/activities/batch': () =>
        json(
          { kind: 'RESOURCE_NOT_FOUND', message: 'No matching activities found to update' },
          404,
        ),
    });
    const user = userEvent.setup();
    renderApp('/activities/manage');

    await user.click(await screen.findByRole('checkbox', { name: 'Select Tempo Tuesday' }));
    await user.click(screen.getByRole('button', { name: /batch modify/i }));
    const dialog = await screen.findByRole('dialog');
    await user.selectOptions(within(dialog).getByLabelText(/new sport/i), 'HIKE');
    await user.click(within(dialog).getByRole('button', { name: /review changes/i }));
    await user.click(within(dialog).getByRole('button', { name: /yes, update 1 activity/i }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'No matching activities found to update',
    );
    expect(within(dialog).getByRole('button', { name: /yes, update 1 activity/i })).toBeEnabled();
  });
});

describe('/activities/manage batch delete', () => {
  it('warns, lists the affected activities and deletes them after explicit confirmation', async () => {
    let items = [
      activity({ id: 7, title: 'Tempo Tuesday' }),
      activity({ id: 8, title: 'Long Sunday' }),
    ];
    const requests = stubApi({
      'GET /api/activities': () => json(pageOf(items)),
      'POST /api/activities/batch-delete': () => {
        items = items.filter((a) => a.id !== 8);
        return json({ affectedCount: 1, matchedCount: 1 });
      },
    });
    const user = userEvent.setup();
    const { queryClient } = renderApp('/activities/manage?sport=RUN');

    await user.click(await screen.findByRole('checkbox', { name: 'Select Long Sunday' }));
    await user.click(screen.getByRole('button', { name: /batch delete/i }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent(/permanently/i);
    expect(dialog).toHaveTextContent(/personal records/i);

    await user.click(within(dialog).getByRole('button', { name: /continue/i }));
    const affected = within(dialog).getByRole('list', { name: /affected activities/i });
    expect(within(affected).getByText('Long Sunday')).toBeInTheDocument();
    await user.click(
      within(dialog).getByRole('button', { name: /permanently delete 1 activity/i }),
    );

    expect(await screen.findByText('Permanently deleted 1 activity')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Long Sunday')).not.toBeInTheDocument());
    expect(bodyOf(requests.find((r) => r.path === '/api/activities/batch-delete'))).toEqual({
      activityIds: [8],
    });
    expect(dashboardInvalidated(queryClient)).toBe(true);
  });

  it('keeps the confirmation open with a retry message when a concurrent change wins a lock', async () => {
    stubApi({
      'GET /api/activities': () => json(pageOf([activity({ id: 8, title: 'Long Sunday' })])),
      'POST /api/activities/batch-delete': () =>
        json(
          {
            kind: 'CONCURRENT_CHANGE',
            message: 'Another change to your activities is in progress. Please try again.',
          },
          409,
        ),
    });
    const user = userEvent.setup();
    renderApp('/activities/manage');

    await user.click(await screen.findByRole('checkbox', { name: 'Select Long Sunday' }));
    await user.click(screen.getByRole('button', { name: /batch delete/i }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: /continue/i }));
    await user.click(
      within(dialog).getByRole('button', { name: /permanently delete 1 activity/i }),
    );

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Another change to your activities is in progress. Please try again.',
    );
    expect(
      within(dialog).getByRole('button', { name: /permanently delete 1 activity/i }),
    ).toBeEnabled();
  });

  it('steps back a page with the filters kept when a batch empties a later page', async () => {
    let lastPage = [activity({ id: 21, title: 'Last Run' })];
    const requests = stubApi({
      'GET /api/activities': ({ search }) =>
        json(
          search.get('page') === '1'
            ? pageOf(lastPage, { page: 1, totalElements: 20 + lastPage.length })
            : pageOf([activity({ id: 1, title: 'First Page Run' })], { totalElements: 20 }),
        ),
      'POST /api/activities/batch-delete': () => {
        lastPage = [];
        return json({ affectedCount: 1, matchedCount: 1 });
      },
    });
    const user = userEvent.setup();
    const { history } = renderApp('/activities/manage?sport=RUN&page=1');

    await user.click(await screen.findByRole('checkbox', { name: 'Select Last Run' }));
    await user.click(screen.getByRole('button', { name: /batch delete/i }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: /continue/i }));
    await user.click(
      within(dialog).getByRole('button', { name: /permanently delete 1 activity/i }),
    );

    expect(await screen.findByText('First Page Run')).toBeInTheDocument();
    expect(screen.getByText('Permanently deleted 1 activity')).toBeInTheDocument();
    const search = new URLSearchParams(history.location.search);
    expect(search.has('page')).toBe(false);
    expect(search.get('sport')).toBe('RUN');
    expect(listRequests(requests).every((r) => r.search.get('sport') === 'RUN')).toBe(true);
  });
});

describe('/activities/manage Best Efforts backfill', () => {
  it('backfills Best Efforts and summarizes the result', async () => {
    const requests = stubApi({
      'GET /api/activities': () => json(pageOf([activity()])),
      'POST /api/activities/backfill-best-efforts': () =>
        json({ activitiesProcessed: 3, bestEffortsCreated: 1 }),
    });
    const user = userEvent.setup();
    const { queryClient } = renderApp('/activities/manage');

    await user.click(await screen.findByRole('button', { name: /backfill best efforts/i }));

    expect(
      await screen.findByText('Backfilled Best Efforts across 3 activities (1 effort detected)'),
    ).toBeInTheDocument();
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    expect(dashboardInvalidated(queryClient)).toBe(true);
  });
});
