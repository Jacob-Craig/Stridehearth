import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Profiler } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  ActivityBestEffortDto,
  ActivityDetailDto,
  ActivityTelemetryResponseDto,
  ActivityTelemetryStreamsDto,
} from '../src/api/generated/types.gen';
import { zActivityTelemetryStreamsDto } from '../src/api/generated/zod.gen';
import { ActivityVisualization } from '../src/features/activity-detail/ActivityVisualization';
import {
  type DownsampleRequest,
  handleDownsampleRequest,
} from '../src/features/telemetry/downsamplerProtocol';
import { createAppRouter } from '../src/router';
import { stubColourScheme } from './support/colourScheme';
import { rendered } from './support/fakeTelemetryRenderers';

vi.mock('../src/features/telemetry/uplotChart', () =>
  import('./support/fakeTelemetryRenderers').then((m) => m.fakeUplotChartModule),
);
vi.mock('../src/features/telemetry/maplibreRouteMap', () =>
  import('./support/fakeTelemetryRenderers').then((m) => m.fakeRouteMapModule),
);

const coordinates = [
  [-0.16, 51.51],
  [-0.17, 51.52],
  [-0.18, 51.53],
  [-0.19, 51.54],
  [-0.2, 51.55],
];

/** Ten 1Hz samples, two per GPS fix, with heart rate and cadence but no power meter. */
function telemetry(
  streams: Partial<ActivityTelemetryStreamsDto> = {},
  route: ActivityTelemetryResponseDto['route'] = { type: 'LineString', coordinates },
  derivedStreams: ActivityTelemetryResponseDto['derivedStreams'] = { gapSpeed: null },
): ActivityTelemetryResponseDto {
  const unrecorded = Object.fromEntries(
    Object.keys(zActivityTelemetryStreamsDto.shape).map((stream) => [stream, null]),
  );
  return {
    activityId: 42,
    sport: 'RUN',
    route,
    bestEfforts: [],
    derivedStreams,
    telemetry: {
      ...unrecorded,
      t: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
      routeIndex: [0, 0, 1, 1, 2, 2, 3, 3, 4, 4],
      heartRate: [140, 141, 142, 143, 144, 145, 146, 147, 148, 149],
      cadence: [80, 81, 82, 83, 84, 85, 86, 87, 88, 89],
      power: Array(10).fill(null),
      ...streams,
    } as ActivityTelemetryStreamsDto,
  };
}

const effort: ActivityBestEffortDto = {
  id: 501,
  benchmarkDistance: 'FOUR_HUNDRED_METERS',
  benchmarkName: '400m',
  distanceMeters: 400,
  elapsedTimeSeconds: 4,
  window: {
    startIndex: 2,
    endIndex: 6,
    startTimeSeconds: 2,
    endTimeSeconds: 6,
    startDistanceMeters: 100,
    endDistanceMeters: 500,
  },
};

const activity: ActivityDetailDto = {
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
  bestEfforts: [effort],
  runMetrics: null,
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
}

function renderActivity(details: ActivityTelemetryResponseDto) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = new URL(input.toString(), 'http://localhost').pathname;
      if (path === '/api/activities/42') return json(activity);
      if (path === '/api/activities/42/telemetry') return json(details);
      throw new Error(`Unexpected request ${path}`);
    }),
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createAppRouter(
    {
      queryClient,
      auth: { user: { id: 1, username: 'runner_bob', email: 'bob@example.com' }, isLoading: false },
    },
    createMemoryHistory({ initialEntries: ['/activities/42'] }),
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

/** A manual clock standing in for `requestAnimationFrame`. */
function stubAnimationFrames() {
  let queued: FrameRequestCallback[] = [];
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    queued.push(callback);
    return queued.length;
  });
  vi.stubGlobal('cancelAnimationFrame', () => {
    queued = [];
  });
  return {
    flush() {
      const callbacks = queued;
      queued = [];
      for (const callback of callbacks) callback(performance.now());
    },
  };
}

const chartLabels = () => rendered.liveCharts().map((chart) => chart.options.series.label);

async function chartsAndMapReady(labels: string[] = ['Heart Rate', 'Cadence']) {
  await waitFor(() => {
    expect(chartLabels()).toEqual(labels);
    expect(rendered.liveMaps()).toHaveLength(1);
  });
  return { charts: rendered.liveCharts(), map: rendered.liveMaps()[0]! };
}

beforeEach(() => {
  rendered.reset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Activity Details route map and sensor charts', () => {
  it('charts only the sensor streams that were recorded, beside the route map', async () => {
    renderActivity(telemetry());

    const { map } = await chartsAndMapReady();
    const charts = screen.getByRole('list', { name: /sensor charts/i });
    expect(
      within(charts)
        .getAllByRole('figure')
        .map((figure) => figure.getAttribute('aria-label')),
    ).toEqual(['Heart Rate (bpm)', 'Cadence (/min)']);
    expect(map.options.coordinates).toEqual(coordinates);
    expect(rendered.liveCharts()[0]!.options.data).toEqual({
      x: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
      y: [140, 141, 142, 143, 144, 145, 146, 147, 148, 149],
    });
  });

  it('highlights the activated Best Effort window on every chart and the route, and clears it when toggled again', async () => {
    const user = userEvent.setup();
    renderActivity(telemetry());
    const { charts, map } = await chartsAndMapReady();

    const row = screen.getByRole('button', { name: /400m best effort/i });
    await user.click(row);

    for (const chart of charts) {
      expect(chart.setHighlight).toHaveBeenLastCalledWith(
        expect.objectContaining({ startTimeSeconds: 2, endTimeSeconds: 6 }),
      );
    }
    expect(map.setHighlight).toHaveBeenLastCalledWith([
      [-0.17, 51.52],
      [-0.18, 51.53],
      [-0.19, 51.54],
    ]);

    row.focus();
    await user.keyboard('{Enter}');

    for (const chart of charts) expect(chart.setHighlight).toHaveBeenLastCalledWith(null);
    expect(map.setHighlight).toHaveBeenLastCalledWith([]);
  });

  it('scrubbing one chart moves the other crosshairs and the route marker on the next frame', async () => {
    renderActivity(telemetry());
    const [heartRate, cadence] = (await chartsAndMapReady()).charts;
    const { map } = await chartsAndMapReady();
    const frames = stubAnimationFrames();

    heartRate!.options.onScrub(4);
    expect(cadence!.setCursorTime).not.toHaveBeenCalled();
    frames.flush();

    expect(cadence!.setCursorTime).toHaveBeenCalledWith(4);
    expect(heartRate!.setCursorTime).not.toHaveBeenCalled();
    expect(map.marker.moveTo).toHaveBeenCalledWith([-0.18, 51.53]);

    heartRate!.options.onScrub(null);
    frames.flush();
    expect(cadence!.setCursorTime).toHaveBeenLastCalledWith(null);
    expect(map.marker.hide).toHaveBeenCalled();
  });

  it('hovering the route moves every chart crosshair to when that point was reached', async () => {
    renderActivity(telemetry());
    const { charts, map } = await chartsAndMapReady();
    const frames = stubAnimationFrames();

    map.options.onRouteVertexHover(3);
    frames.flush();

    for (const chart of charts) expect(chart.setCursorTime).toHaveBeenCalledWith(6);
  });

  it('scrubs without re-rendering React', async () => {
    let commits = 0;
    render(
      <Profiler id="visualization" onRender={() => commits++}>
        <ActivityVisualization telemetry={telemetry()} selectedEffort={effort} />
      </Profiler>,
    );
    const { charts, map } = await chartsAndMapReady();
    const settled = commits;
    const frames = stubAnimationFrames();

    for (let second = 0; second < 10; second++) {
      charts[second % 2]!.options.onScrub(second);
      frames.flush();
      map.options.onRouteVertexHover(second % 5);
      frames.flush();
    }

    // Five scrubs of the heart rate chart and ten route hovers reached the cadence chart.
    expect(charts[1]!.setCursorTime).toHaveBeenCalledTimes(15);
    expect(map.marker.moveTo).toHaveBeenCalled();
    expect(commits).toBe(settled);
  });

  it('opens the route map in 2D and toggles it to 3D', async () => {
    const user = userEvent.setup();
    renderActivity(telemetry());
    const { map } = await chartsAndMapReady();
    const threeD = screen.getByRole('button', { name: '3D' });
    const twoD = screen.getByRole('button', { name: '2D' });
    expect(map.options.perspective).toBe('2d');
    expect(twoD).toHaveAttribute('aria-pressed', 'true');

    await user.click(threeD);

    expect(map.setPerspective).toHaveBeenLastCalledWith('3d');
    expect(threeD).toHaveAttribute('aria-pressed', 'true');
    expect(twoD).toHaveAttribute('aria-pressed', 'false');
  });

  it('follows the dark colour scheme on the map and charts', async () => {
    stubColourScheme('dark');
    renderActivity(telemetry());
    const { charts, map } = await chartsAndMapReady();

    expect(map.options.theme).toBe('dark');
    for (const chart of charts) expect(chart.options.theme).toBe('dark');
  });

  it('restyles the live map and charts when the colour scheme changes, without rebuilding them', async () => {
    const scheme = { dark: false, listeners: new Set<() => void>() };
    vi.stubGlobal(
      'matchMedia',
      (query: string) =>
        ({
          get matches() {
            return query === '(prefers-color-scheme: dark)' && scheme.dark;
          },
          addEventListener: (_type: string, listener: () => void) => scheme.listeners.add(listener),
          removeEventListener: (_type: string, listener: () => void) =>
            scheme.listeners.delete(listener),
        }) as unknown as MediaQueryList,
    );
    renderActivity(telemetry());
    const { charts, map } = await chartsAndMapReady();

    act(() => {
      scheme.dark = true;
      for (const listener of scheme.listeners) listener();
    });

    for (const chart of charts) expect(chart.setTheme).toHaveBeenLastCalledWith('dark');
    expect(map.setTheme).toHaveBeenLastCalledWith('dark');
    expect(rendered.charts).toHaveLength(2);
    expect(rendered.maps).toHaveLength(1);
  });

  it('reserves fixed heights for each chart card and its plot', async () => {
    renderActivity(telemetry());
    await chartsAndMapReady();

    const cards = within(screen.getByRole('list', { name: /sensor charts/i })).getAllByRole(
      'listitem',
    );
    for (const card of cards) expect(card).toHaveStyle({ height: '168px' });
    expect(rendered.liveCharts()[0]!.options.container).toHaveStyle({ height: '140px' });
  });

  it('charts an indoor Activity without a route map', async () => {
    renderActivity(telemetry({ routeIndex: null }, null));

    await waitFor(() => expect(chartLabels()).toEqual(['Heart Rate', 'Cadence']));
    expect(rendered.maps).toHaveLength(0);
    expect(
      screen.getByText("No route was recorded in this Activity's Activity Details."),
    ).toBeInTheDocument();
  });

  it('explains when the Activity Details recorded no sensor or route data', async () => {
    renderActivity(telemetry({ heartRate: null, cadence: null, routeIndex: null }, null));

    expect(
      await screen.findByText(
        "No sensor readings were recorded in this Activity's Activity Details.",
      ),
    ).toBeInTheDocument();
    expect(rendered.charts).toHaveLength(0);
  });
});

describe('Grade-Adjusted Pace overlay', () => {
  const hilly = {
    enhancedAltitude: [30, 31, 32, 33, 34, 34, 33, 32, 31, 30],
    enhancedSpeed: [3, 3, 3, 3, 3, 3, 3, 3, 3, 3],
  };
  // 3 m/s with GAP faster uphill and slower downhill; m/s, as the server sends it.
  const gapSpeed = [3.5, 3.5, 3.5, 3.5, 3, 3, 2.5, 2.5, 2.5, 2.5];
  const speedChart = () =>
    rendered.liveCharts().find((chart) => chart.options.series.label === 'Speed')!;

  it('overlays GAP in km/h on the Speed chart, shown by default, with a pressed toggle in its caption', async () => {
    renderActivity(telemetry(hilly, undefined, { gapSpeed }));
    await chartsAndMapReady(['Elevation', 'Speed', 'Heart Rate', 'Cadence']);

    const overlay = speedChart().options.overlay!;
    expect(overlay).toMatchObject({ label: 'GAP', visible: true });
    expect(overlay.samples.x).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
    expect(overlay.samples.y.map((kmh) => Math.round(kmh! * 10) / 10)).toEqual([
      12.6, 12.6, 12.6, 12.6, 10.8, 10.8, 9, 9, 9, 9,
    ]);
    const speedFigure = screen.getByRole('figure', { name: 'Speed (km/h)' });
    expect(within(speedFigure).getByRole('button', { name: 'GAP' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    // Only the Speed chart has the overlay and the toggle.
    expect(rendered.liveCharts().filter((chart) => chart.options.overlay)).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'GAP' })).toHaveLength(1);
  });

  it('toggles GAP off and on again in place, without rebuilding the chart', async () => {
    const user = userEvent.setup();
    renderActivity(telemetry(hilly, undefined, { gapSpeed }));
    await chartsAndMapReady(['Elevation', 'Speed', 'Heart Rate', 'Cadence']);
    const chart = speedChart();
    const toggle = screen.getByRole('button', { name: 'GAP' });

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
    expect(chart.setOverlayVisible).toHaveBeenLastCalledWith(false);

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(chart.setOverlayVisible).toHaveBeenLastCalledWith(true);
    expect(chart.destroy).not.toHaveBeenCalled();
    expect(speedChart()).toBe(chart);
  });

  it('leaves the charts unchanged when the Activity has no GAP stream', async () => {
    renderActivity(telemetry(hilly, undefined, { gapSpeed: null }));
    await chartsAndMapReady(['Elevation', 'Speed', 'Heart Rate', 'Cadence']);

    expect(rendered.liveCharts().some((chart) => chart.options.overlay)).toBe(false);
    expect(screen.queryByRole('button', { name: 'GAP' })).not.toBeInTheDocument();
  });

  it('offers no GAP toggle without elevation, where GAP repeats speed', async () => {
    renderActivity(telemetry({ enhancedSpeed: hilly.enhancedSpeed }, undefined, { gapSpeed }));
    await chartsAndMapReady(['Speed', 'Heart Rate', 'Cadence']);

    expect(speedChart().options.overlay).toBeUndefined();
    expect(screen.queryByRole('button', { name: 'GAP' })).not.toBeInTheDocument();
  });
});

describe('ultra-long Activity downsampling', () => {
  /** Runs the real worker protocol in-thread, since jsdom has no Web Worker implementation. */
  class InThreadDownsamplerWorker {
    static spawned = 0;
    onmessage: ((event: MessageEvent) => void) | null = null;
    onerror: (() => void) | null = null;
    constructor() {
      InThreadDownsamplerWorker.spawned++;
    }
    postMessage(request: DownsampleRequest) {
      queueMicrotask(() =>
        this.onmessage?.({ data: handleDownsampleRequest(request) } as MessageEvent),
      );
    }
    terminate() {}
  }

  function longActivity(samples: number) {
    const t = Array.from({ length: samples }, (_, i) => i);
    return telemetry({
      t,
      routeIndex: t.map((i) => Math.min(Math.floor(i / 4000), 4)),
      heartRate: t.map((i) => 140 + (i % 30)),
      cadence: null,
      power: null,
    });
  }

  beforeEach(() => {
    InThreadDownsamplerWorker.spawned = 0;
    vi.stubGlobal('Worker', InThreadDownsamplerWorker);
  });

  it('charts up to 15,000 samples at full resolution on the main thread', async () => {
    renderActivity(longActivity(15_000));

    await chartsAndMapReady(['Heart Rate']);
    expect(rendered.liveCharts()[0]!.options.data.x).toHaveLength(15_000);
    expect(InThreadDownsamplerWorker.spawned).toBe(0);
  });

  it('downsamples longer Activities in a Web Worker, keeping Best Efforts and the route exact by time', async () => {
    const user = userEvent.setup();
    renderActivity(longActivity(15_001));

    const { charts, map } = await chartsAndMapReady(['Heart Rate']);
    expect(InThreadDownsamplerWorker.spawned).toBe(1);
    expect(charts[0]!.options.data.x).toHaveLength(2_000);

    await user.click(screen.getByRole('button', { name: /400m best effort/i }));
    // The effort stays on one route vertex, so its path widens to the next one to remain a line.
    expect(map.setHighlight).toHaveBeenLastCalledWith([
      [-0.16, 51.51],
      [-0.17, 51.52],
    ]);

    const frames = stubAnimationFrames();
    act(() => charts[0]!.options.onScrub(12_345));
    frames.flush();
    expect(map.marker.moveTo).toHaveBeenCalledWith([-0.19, 51.54]);
  });
});
