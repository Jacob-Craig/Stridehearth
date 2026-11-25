import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { HeatmapRendererDto, HeatmapStartingViewDto } from '../src/api/generated/types.gen';
import type { HeatmapLayerOptions, HeatmapTileKey } from '../src/features/heatmap/heatmapLayers';
import { createAppRouter } from '../src/router';

const layers = vi.hoisted(() => ({
  created: [] as Array<{
    options: HeatmapLayerOptions;
    destroy: ReturnType<typeof vi.fn>;
    setTileKey: ReturnType<typeof vi.fn>;
    setPerspective: ReturnType<typeof vi.fn>;
  }>,
}));

vi.mock('../src/features/heatmap/rasterDensityHeatmapLayer', () => ({
  createHeatmapLayer(options: HeatmapLayerOptions) {
    const layer = { options, destroy: vi.fn(), setTileKey: vi.fn(), setPerspective: vi.fn() };
    layers.created.push(layer);
    return layer;
  },
}));

const renderer: HeatmapRendererDto = {
  id: 'raster-density',
  tileUrlTemplate: '/api/heatmap/tiles/raster-density-v1/{revision}/{z}/{x}/{y}',
  maxZoom: 16,
};

const london: HeatmapStartingViewDto = {
  bounds: { west: -0.28, south: 51.46, east: -0.08, north: 51.56 },
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
}

const requests: URL[] = [];

const NO_MATCH = 'No Activities with routes match this filter.';
const INVERTED_RANGE = 'The From date is after the To date.';

/**
 * Serves the Heatmap API.
 * - The starting view for the default filter is [startingView] on the first request and
 *   [laterStartingView] after it; a filtered starting view comes from [filteredView], so a test can make a
 *   filter match nothing.
 * - Each revision request gets the next of [revisions], then the last.
 */
function serveHeatmap(
  startingView: HeatmapStartingViewDto,
  {
    rendererId = renderer.id,
    revisions = ['7-123'],
    laterStartingView = startingView,
    filteredView = () => startingView,
  }: {
    rendererId?: string;
    revisions?: string[];
    laterStartingView?: HeatmapStartingViewDto;
    filteredView?: (
      query: URLSearchParams,
    ) => HeatmapStartingViewDto | Promise<HeatmapStartingViewDto>;
  } = {},
) {
  const remaining = [...revisions];
  let startingViews = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(input.toString(), 'http://localhost');
      requests.push(url);
      const path = url.pathname;
      if (path === '/api/heatmap/renderer') return json({ ...renderer, id: rendererId });
      if (path === '/api/heatmap/revision')
        return json({ revision: remaining.length > 1 ? remaining.shift() : remaining[0] });
      if (path === '/api/heatmap/years') return json({ years: [2026, 2025, 2023] });
      if (path === '/api/heatmap/starting-view') {
        if (url.search !== '') return json(await filteredView(url.searchParams));
        return json(startingViews++ === 0 ? startingView : laterStartingView);
      }
      if (path === '/api/dashboard') return json({ personalRecordSlots: [] });
      throw new Error(`Unexpected request ${path}`);
    }),
  );
  return {
    revisionRequests: () =>
      requests.filter((url) => url.pathname === '/api/heatmap/revision').length,
  };
}

/** The revisions the live layer has been moved to, in order. */
const revisionsSet = (layer: (typeof layers.created)[number]) =>
  layer.setTileKey.mock.calls.map(([tileKey]) => (tileKey as HeatmapTileKey).revision);

function renderApp(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const history = createMemoryHistory({ initialEntries: [path] });
  const router = createAppRouter(
    {
      queryClient,
      auth: { user: { id: 1, username: 'runner_bob', email: 'bob@example.com' }, isLoading: false },
    },
    history,
  );
  const { unmount } = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return Object.assign(history, { unmount });
}

const THREE_D_NOTE = '3D loads more map data and may be slower on large Heatmaps';

/** A `localStorage` that throws on every access, as browsers do when site storage is blocked. */
function blockStorage() {
  const blocked = () => {
    throw new DOMException('Storage is blocked', 'SecurityError');
  };
  vi.stubGlobal('localStorage', {
    getItem: blocked,
    setItem: blocked,
    removeItem: blocked,
    clear: blocked,
    key: blocked,
    length: 0,
  });
}

async function turnOn3d() {
  await userEvent.click(await screen.findByRole('button', { name: '3D' }));
}

async function heatmapLayer(index = 0) {
  await waitFor(() => expect(layers.created.length).toBeGreaterThan(index));
  return layers.created[index]!;
}

const cityCentre = { center: [-0.17, 51.515] as [number, number], zoom: 13.5 };

describe('Heatmap page', () => {
  beforeEach(() => {
    layers.created.length = 0;
    window.localStorage.clear();
    requests.length = 0;
    // A fixed browser timezone, five hours behind UTC in winter, so local-time boundaries are visible.
    vi.stubEnv('TZ', 'America/New_York');
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('is reached from the Heatmap entry in the main navigation', async () => {
    serveHeatmap(london);
    const history = renderApp('/dashboard');

    await userEvent.click(await screen.findByRole('link', { name: 'Heatmap' }));

    await waitFor(() => expect(history.location.pathname).toBe('/heatmap'));
    expect(await screen.findByRole('region', { name: 'Heatmap' })).toBeInTheDocument();
  });

  it('shows the renderer tiles at the athlete revision, opening on the starting view on a first visit', async () => {
    serveHeatmap(london);
    renderApp('/heatmap');

    expect((await heatmapLayer()).options).toMatchObject({
      renderer,
      tileKey: { revision: '7-123', filterParams: '' },
      startingBounds: london.bounds,
      view: null,
    });
  });

  it('reopens on the last viewed area on a later visit', async () => {
    serveHeatmap(london);
    const firstVisit = renderApp('/heatmap');
    const first = await heatmapLayer();
    act(() => first.options.onViewChange(cityCentre));
    firstVisit.unmount();

    renderApp('/heatmap');

    expect((await heatmapLayer(1)).options).toMatchObject({
      view: cityCentre,
      startingBounds: london.bounds,
    });
  });

  it('opens on the world, not a remembered view, once no routes match', async () => {
    serveHeatmap(london);
    const firstVisit = renderApp('/heatmap');
    const first = await heatmapLayer();
    act(() => first.options.onViewChange(cityCentre));
    firstVisit.unmount();
    serveHeatmap({ bounds: null });

    renderApp('/heatmap');

    expect(await screen.findByText(/no activities with a route yet/i)).toBeInTheDocument();
    expect((await heatmapLayer(1)).options).toMatchObject({ startingBounds: null, view: null });
  });

  it('does not remember the world view, so the first visit with routes opens on the starting view', async () => {
    serveHeatmap({ bounds: null });
    const emptyVisit = renderApp('/heatmap');
    const empty = await heatmapLayer();
    act(() => empty.options.onViewChange({ center: [12, 30], zoom: 1.5 }));
    emptyVisit.unmount();
    serveHeatmap(london);

    renderApp('/heatmap');

    expect((await heatmapLayer(1)).options).toMatchObject({
      startingBounds: london.bounds,
      view: null,
    });
  });

  it('opens on the starting view when only reading browser storage throws', async () => {
    serveHeatmap(london);
    const firstVisit = renderApp('/heatmap');
    const first = await heatmapLayer();
    act(() => first.options.onViewChange(cityCentre));
    firstVisit.unmount();
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('The operation is insecure.', 'SecurityError');
    });
    const errors = vi.spyOn(console, 'error');

    renderApp('/heatmap');

    expect((await heatmapLayer(1)).options).toMatchObject({
      startingBounds: london.bounds,
      view: null,
    });
    expect(errors).not.toHaveBeenCalled();
  });

  it('opens on the starting view without errors when browser storage throws', async () => {
    const blocked = () => {
      throw new DOMException('The operation is insecure.', 'SecurityError');
    };
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked);
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked);
    const errors = vi.spyOn(console, 'error');
    serveHeatmap(london);
    const firstVisit = renderApp('/heatmap');

    const first = await heatmapLayer();
    expect(first.options).toMatchObject({ startingBounds: london.bounds, view: null });
    expect(() => act(() => first.options.onViewChange(cityCentre))).not.toThrow();
    expect(write).toHaveBeenCalled();
    firstVisit.unmount();
    renderApp('/heatmap');

    expect((await heatmapLayer(1)).options).toMatchObject({
      startingBounds: london.bounds,
      view: null,
    });
    expect(screen.getByRole('region', { name: 'Heatmap' })).toBeInTheDocument();
    expect(errors).not.toHaveBeenCalled();
  });

  it('ignores a remembered view it cannot read, opening on the starting view', async () => {
    serveHeatmap(london);
    const firstVisit = renderApp('/heatmap');
    const first = await heatmapLayer();
    act(() => first.options.onViewChange(cityCentre));
    firstVisit.unmount();
    // Whatever was saved is now unreadable, as after a change of format or a hand edit.
    const keys = Array.from({ length: window.localStorage.length }, (_, i) =>
      window.localStorage.key(i),
    );
    expect(keys).not.toHaveLength(0);
    for (const key of keys) window.localStorage.setItem(key!, '{"center":"Hyde Park"}');

    renderApp('/heatmap');

    expect((await heatmapLayer(1)).options.view).toBeNull();
  });

  it('checks the revision when the window regains focus, keeping the tiles when it is unchanged', async () => {
    const api = serveHeatmap(london);
    renderApp('/heatmap');
    const layer = await heatmapLayer();
    await waitFor(() => expect(layer.setTileKey).toHaveBeenCalled());

    act(() => {
      window.dispatchEvent(new Event('focus'));
    });

    await waitFor(() => expect(api.revisionRequests()).toBe(2));
    expect(revisionsSet(layer).every((revision) => revision === '7-123')).toBe(true);
    expect(layers.created).toHaveLength(1);
  });

  it('moves to the new revision tiles when the window regains focus after a change, keeping the camera', async () => {
    serveHeatmap(london, { revisions: ['7-123', '8-130'] });
    renderApp('/heatmap');
    const layer = await heatmapLayer();

    act(() => {
      window.dispatchEvent(new Event('focus'));
    });

    await waitFor(() => expect(revisionsSet(layer).at(-1)).toBe('8-130'));
    expect(layers.created).toHaveLength(1);
    expect(layer.destroy).not.toHaveBeenCalled();
  });

  it('drops the empty state when the window regains focus after the first routes arrive', async () => {
    serveHeatmap({ bounds: null }, { revisions: ['0-0', '1-9'], laterStartingView: london });
    renderApp('/heatmap');
    expect(await screen.findByText(/no activities with a route yet/i)).toBeInTheDocument();
    const layer = await heatmapLayer();

    act(() => {
      window.dispatchEvent(new Event('focus'));
    });

    await waitFor(() =>
      expect(screen.queryByText(/no activities with a route yet/i)).not.toBeInTheDocument(),
    );
    expect(revisionsSet(layer).at(-1)).toBe('1-9');
    expect(layers.created).toHaveLength(1);
  });

  it('shows a world map and a note when the athlete has no routes', async () => {
    serveHeatmap({ bounds: null });
    renderApp('/heatmap');

    expect(await screen.findByText(/no activities with a route yet/i)).toBeInTheDocument();
    await waitFor(() => expect(layers.created).toHaveLength(1));
    expect(layers.created[0]!.options.startingBounds).toBeNull();
  });

  it('says so when the server uses a renderer this app cannot draw', async () => {
    serveHeatmap(london, { rendererId: 'counted-segments' });
    renderApp('/heatmap');

    expect(await screen.findByText(/cannot be displayed/i)).toBeInTheDocument();
    expect(layers.created).toHaveLength(0);
  });

  it('opens in 2D and switches the map between 2D and 3D', async () => {
    serveHeatmap(london);
    renderApp('/heatmap');
    await waitFor(() => expect(layers.created).toHaveLength(1));
    const layer = layers.created[0]!;
    expect(layer.options.perspective).toBe('2d');
    expect(screen.getByRole('button', { name: '2D' })).toHaveAttribute('aria-pressed', 'true');

    await turnOn3d();
    await waitFor(() => expect(layer.setPerspective).toHaveBeenLastCalledWith('3d'));
    expect(screen.getByRole('button', { name: '3D' })).toHaveAttribute('aria-pressed', 'true');

    await userEvent.click(screen.getByRole('button', { name: '2D' }));
    await waitFor(() => expect(layer.setPerspective).toHaveBeenLastCalledWith('2d'));
    expect(layers.created).toHaveLength(1);
  });

  it('notes that 3D loads more map data the first time 3D is turned on, and never again', async () => {
    serveHeatmap(london);
    renderApp('/heatmap');
    expect(screen.queryByText(THREE_D_NOTE)).not.toBeInTheDocument();

    await turnOn3d();
    expect(await screen.findByText(THREE_D_NOTE)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '2D' }));
    expect(screen.queryByText(THREE_D_NOTE)).not.toBeInTheDocument();
    await turnOn3d();
    expect(screen.queryByText(THREE_D_NOTE)).not.toBeInTheDocument();
  });

  it('does not show the 3D note on a later visit once it has been shown', async () => {
    serveHeatmap(london);
    renderApp('/heatmap');
    await turnOn3d();
    await screen.findByText(THREE_D_NOTE);
    cleanup();

    renderApp('/heatmap');
    await turnOn3d();

    expect(screen.getByRole('button', { name: '3D' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText(THREE_D_NOTE)).not.toBeInTheDocument();
  });

  it('lets the athlete dismiss the 3D note', async () => {
    serveHeatmap(london);
    renderApp('/heatmap');
    await turnOn3d();

    await userEvent.click(await screen.findByRole('button', { name: /dismiss/i }));

    expect(screen.queryByText(THREE_D_NOTE)).not.toBeInTheDocument();
  });

  it('still switches between 2D and 3D when storage is blocked, showing the note once per visit', async () => {
    blockStorage();
    serveHeatmap(london);
    renderApp('/heatmap');
    await waitFor(() => expect(layers.created).toHaveLength(1));
    const layer = layers.created[0]!;

    await turnOn3d();
    expect(await screen.findByText(THREE_D_NOTE)).toBeInTheDocument();
    await waitFor(() => expect(layer.setPerspective).toHaveBeenLastCalledWith('3d'));

    await userEvent.click(screen.getByRole('button', { name: '2D' }));
    await waitFor(() => expect(layer.setPerspective).toHaveBeenLastCalledWith('2d'));
    await turnOn3d();

    await waitFor(() => expect(layer.setPerspective).toHaveBeenLastCalledWith('3d'));
    expect(screen.getByRole('button', { name: '3D' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByText(THREE_D_NOTE)).not.toBeInTheDocument();
    expect(screen.queryByText(/cannot be displayed/i)).not.toBeInTheDocument();
    expect(layers.created).toHaveLength(1);
  });

  describe('Heatmap Filter', () => {
    /** The live layer's latest tile parameters, decoded. */
    const latestFilter = () => {
      const layer = layers.created[0]!;
      const calls = layer.setTileKey.mock.calls;
      const tileKey =
        calls.length > 0 ? (calls.at(-1)![0] as HeatmapTileKey) : layer.options.tileKey;
      return new URLSearchParams(tileKey.filterParams);
    };

    const filterControls = async () =>
      within(await screen.findByRole('form', { name: 'Heatmap Filter' }));

    it('starts at all time and all sports, showing the full Heatmap', async () => {
      serveHeatmap(london);
      renderApp('/heatmap');

      const controls = await filterControls();
      await waitFor(() => expect(layers.created).toHaveLength(1));
      expect(layers.created[0]!.options.tileKey.filterParams).toBe('');
      expect(controls.getByLabelText('From')).toHaveValue('');
      expect(controls.getByLabelText('To')).toHaveValue('');
      for (const sport of ['Run', 'Walk', 'Hike', 'Ride', 'Swim', 'Workout']) {
        expect(controls.getByRole('checkbox', { name: sport })).toBeChecked();
      }
      expect(controls.getByRole('button', { name: 'All time' })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
      expect(screen.queryByText(/match this filter/i)).not.toBeInTheDocument();
    });

    it('offers a shortcut for every year with Activities, counted in the browser timezone', async () => {
      serveHeatmap(london);
      renderApp('/heatmap');

      const controls = await filterControls();
      expect(await controls.findByRole('button', { name: '2026' })).toBeInTheDocument();
      expect(controls.getByRole('button', { name: '2025' })).toBeInTheDocument();
      expect(controls.getByRole('button', { name: '2023' })).toBeInTheDocument();
      const yearsRequest = requests.find((url) => url.pathname === '/api/heatmap/years')!;
      expect(yearsRequest.searchParams.get('timeZone')).toBe('America/New_York');
    });

    it('sets a year shortcut to that year local-time boundaries and sends them as new tile parameters', async () => {
      serveHeatmap(london);
      renderApp('/heatmap');
      const controls = await filterControls();
      await waitFor(() => expect(layers.created).toHaveLength(1));

      await userEvent.click(await controls.findByRole('button', { name: '2025' }));

      expect(controls.getByLabelText('From')).toHaveValue('2025-01-01');
      expect(controls.getByLabelText('To')).toHaveValue('2025-12-31');
      expect(controls.getByRole('button', { name: '2025' })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
      // Midnight in New York, so a run at 23:30 on New Year's Eve counts in 2025.
      await waitFor(() =>
        expect(Object.fromEntries(latestFilter())).toEqual({
          from: '2025-01-01T05:00:00.000Z',
          to: '2026-01-01T05:00:00.000Z',
        }),
      );
      expect(layers.created).toHaveLength(1);

      await userEvent.click(controls.getByRole('button', { name: 'All time' }));

      expect(controls.getByLabelText('From')).toHaveValue('');
      await waitFor(() => expect(latestFilter().toString()).toBe(''));
    });

    it('sends a date range open at either end, with the To date included in full', async () => {
      serveHeatmap(london);
      renderApp('/heatmap');
      const controls = await filterControls();
      await waitFor(() => expect(layers.created).toHaveLength(1));

      fireEvent.change(controls.getByLabelText('From'), { target: { value: '2025-07-01' } });
      await waitFor(() =>
        expect(Object.fromEntries(latestFilter())).toEqual({ from: '2025-07-01T04:00:00.000Z' }),
      );

      fireEvent.change(controls.getByLabelText('From'), { target: { value: '' } });
      fireEvent.change(controls.getByLabelText('To'), { target: { value: '2025-03-15' } });
      await waitFor(() =>
        expect(Object.fromEntries(latestFilter())).toEqual({ to: '2025-03-16T04:00:00.000Z' }),
      );
    });

    it('sends the chosen sports as new tile parameters', async () => {
      serveHeatmap(london);
      renderApp('/heatmap');
      const controls = await filterControls();
      await waitFor(() => expect(layers.created).toHaveLength(1));

      for (const sport of ['Walk', 'Hike', 'Swim', 'Workout']) {
        await userEvent.click(controls.getByRole('checkbox', { name: sport }));
      }

      await waitFor(() => expect(latestFilter().getAll('sports')).toEqual(['RUN', 'RIDE']));
      expect(layers.created).toHaveLength(1);
    });

    it('shows the empty state when no sports are selected', async () => {
      serveHeatmap(london);
      renderApp('/heatmap');
      const controls = await filterControls();
      await waitFor(() => expect(layers.created).toHaveLength(1));

      for (const sport of ['Run', 'Walk', 'Hike', 'Ride', 'Swim', 'Workout']) {
        await userEvent.click(controls.getByRole('checkbox', { name: sport }));
      }

      expect(await screen.findByText(NO_MATCH)).toBeInTheDocument();
      await waitFor(() => expect(latestFilter().toString()).toBe('sports='));
    });

    it('shows the empty state when the filter matches no routes', async () => {
      serveHeatmap(london, {
        filteredView: (query) =>
          query.get('from')?.startsWith('2023') ? { bounds: null } : london,
      });
      renderApp('/heatmap');
      const controls = await filterControls();

      await userEvent.click(await controls.findByRole('button', { name: '2023' }));

      expect(await screen.findByText(NO_MATCH)).toBeInTheDocument();

      await userEvent.click(controls.getByRole('button', { name: '2025' }));

      await waitFor(() => expect(screen.queryByText(NO_MATCH)).not.toBeInTheDocument());
    });

    it('does not carry an earlier no-match answer through All time to the next filter', async () => {
      let release2025 = () => {};
      const pending2025 = new Promise<void>((resolve) => {
        release2025 = resolve;
      });
      serveHeatmap(london, {
        filteredView: async (query) => {
          const from = query.get('from') ?? '';
          if (from.startsWith('2023')) return { bounds: null };
          if (from.startsWith('2025')) await pending2025;
          return london;
        },
      });
      renderApp('/heatmap');
      const controls = await filterControls();

      await userEvent.click(await controls.findByRole('button', { name: '2023' }));
      expect(await screen.findByText(NO_MATCH)).toBeInTheDocument();
      await userEvent.click(controls.getByRole('button', { name: 'All time' }));
      await waitFor(() => expect(screen.queryByText(NO_MATCH)).not.toBeInTheDocument());

      await userEvent.click(controls.getByRole('button', { name: '2025' }));

      // While 2025 loads, nothing says it matches nothing.
      await waitFor(() =>
        expect(requests.some((url) => url.searchParams.get('from')?.startsWith('2025'))).toBe(true),
      );
      expect(screen.queryByText(NO_MATCH)).not.toBeInTheDocument();
      release2025();
      await waitFor(() => expect(layers.created).toHaveLength(1));
      expect(screen.queryByText(NO_MATCH)).not.toBeInTheDocument();
    });

    it('says the date range is back to front when From is after To, rather than that nothing matches', async () => {
      serveHeatmap(london, { filteredView: () => ({ bounds: null }) });
      renderApp('/heatmap');
      const controls = await filterControls();
      await waitFor(() => expect(layers.created).toHaveLength(1));

      fireEvent.change(controls.getByLabelText('From'), { target: { value: '2025-07-01' } });
      fireEvent.change(controls.getByLabelText('To'), { target: { value: '2025-03-01' } });

      expect(await screen.findByText(INVERTED_RANGE)).toBeInTheDocument();
      expect(screen.queryByText(NO_MATCH)).not.toBeInTheDocument();
      expect(controls.getByLabelText('From')).toHaveAttribute('aria-invalid', 'true');
      expect(controls.getByLabelText('To')).toHaveAttribute('aria-invalid', 'true');

      fireEvent.change(controls.getByLabelText('To'), { target: { value: '2025-07-01' } });

      await waitFor(() => expect(screen.queryByText(INVERTED_RANGE)).not.toBeInTheDocument());
      expect(controls.getByLabelText('From')).not.toHaveAttribute('aria-invalid', 'true');
    });
  });
});
