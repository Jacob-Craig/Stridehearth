/**
 * Stand-ins for the uPlot chart and MapLibre route map adapters, since jsdom has neither canvas nor
 * WebGL. Each records what the visualization asks of it. Install with:
 *
 *   vi.mock('../src/features/telemetry/uplotChart', () => import('./support/fakeTelemetryRenderers').then((m) => m.fakeUplotChartModule));
 *   vi.mock('../src/features/telemetry/maplibreRouteMap', () => import('./support/fakeTelemetryRenderers').then((m) => m.fakeRouteMapModule));
 */
import { vi } from 'vitest';
import type { RouteMapOptions } from '../../src/features/telemetry/maplibreRouteMap';
import type { TelemetryChartOptions } from '../../src/features/telemetry/uplotChart';

export interface FakeChart {
  readonly options: TelemetryChartOptions;
  readonly setCursorTime: ReturnType<typeof vi.fn>;
  readonly setHighlight: ReturnType<typeof vi.fn>;
  readonly setTheme: ReturnType<typeof vi.fn>;
  readonly destroy: ReturnType<typeof vi.fn>;
}

export interface FakeRouteMap {
  readonly options: RouteMapOptions;
  readonly marker: { moveTo: ReturnType<typeof vi.fn>; hide: ReturnType<typeof vi.fn> };
  readonly setHighlight: ReturnType<typeof vi.fn>;
  readonly setTheme: ReturnType<typeof vi.fn>;
  readonly setPerspective: ReturnType<typeof vi.fn>;
  readonly destroy: ReturnType<typeof vi.fn>;
}

export const rendered = {
  charts: [] as FakeChart[],
  maps: [] as FakeRouteMap[],
  /** Charts not yet destroyed, i.e. currently on screen. */
  liveCharts(): FakeChart[] {
    return this.charts.filter((chart) => chart.destroy.mock.calls.length === 0);
  },
  liveMaps(): FakeRouteMap[] {
    return this.maps.filter((map) => map.destroy.mock.calls.length === 0);
  },
  reset() {
    this.charts.length = 0;
    this.maps.length = 0;
  },
};

export const fakeUplotChartModule = {
  createTelemetryChart(options: TelemetryChartOptions): FakeChart {
    const chart = {
      options,
      setCursorTime: vi.fn(),
      setHighlight: vi.fn(),
      setTheme: vi.fn(),
      destroy: vi.fn(),
    };
    rendered.charts.push(chart);
    return chart;
  },
};

export const fakeRouteMapModule = {
  BEST_EFFORT_HIGHLIGHT_SOURCE: 'best-effort-highlight-source',
  createRouteMap(options: RouteMapOptions): FakeRouteMap {
    const map = {
      options,
      marker: { moveTo: vi.fn(), hide: vi.fn() },
      setHighlight: vi.fn(),
      setTheme: vi.fn(),
      setPerspective: vi.fn(),
      destroy: vi.fn(),
    };
    rendered.maps.push(map);
    return map;
  },
};
