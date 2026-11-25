/**
 * A stand-in for the Pace-Duration Curve's uPlot adapter, since jsdom has no canvas. It records what the
 * Dashboard asks of it. Install with:
 *
 *   vi.mock('../src/features/pace-duration/paceDurationChart', () => import('./support/fakePaceDurationChart').then((m) => m.fakePaceDurationChartModule));
 */
import { vi } from 'vitest';
import type { PaceDurationChartOptions } from '../../src/features/pace-duration/paceDurationChart';

export interface FakePaceDurationChart {
  readonly options: PaceDurationChartOptions;
  readonly setTheme: ReturnType<typeof vi.fn>;
  readonly destroy: ReturnType<typeof vi.fn>;
}

export const paceDurationCharts = {
  all: [] as FakePaceDurationChart[],
  /** Charts not yet destroyed, i.e. currently on screen. */
  live(): FakePaceDurationChart[] {
    return this.all.filter((chart) => chart.destroy.mock.calls.length === 0);
  },
  reset() {
    this.all.length = 0;
  },
};

export const fakePaceDurationChartModule = {
  createPaceDurationChart(options: PaceDurationChartOptions): FakePaceDurationChart {
    const chart = { options, setTheme: vi.fn(), destroy: vi.fn() };
    paceDurationCharts.all.push(chart);
    return chart;
  },
};
