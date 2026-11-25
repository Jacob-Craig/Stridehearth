/**
 * A stand-in for the Race Split Plan elevation profile's uPlot adapter, since jsdom has no canvas. It records
 * what the page asks of it. Install with:
 *
 *   vi.mock('../src/features/race-split-plan/elevationProfileChart', () => import('./support/fakeElevationProfileChart').then((m) => m.fakeElevationProfileChartModule));
 */
import { vi } from 'vitest';
import type { ElevationProfileOptions } from '../../src/features/race-split-plan/elevationProfileChart';

export interface FakeElevationProfileChart {
  readonly options: ElevationProfileOptions;
  readonly setTheme: ReturnType<typeof vi.fn>;
  readonly destroy: ReturnType<typeof vi.fn>;
}

export const elevationProfileCharts = {
  all: [] as FakeElevationProfileChart[],
  /** Charts not yet destroyed, i.e. currently on screen. */
  live(): FakeElevationProfileChart[] {
    return this.all.filter((chart) => chart.destroy.mock.calls.length === 0);
  },
  reset() {
    this.all.length = 0;
  },
};

export const fakeElevationProfileChartModule = {
  createElevationProfileChart(options: ElevationProfileOptions): FakeElevationProfileChart {
    const chart = { options, setTheme: vi.fn(), destroy: vi.fn() };
    elevationProfileCharts.all.push(chart);
    return chart;
  },
};
