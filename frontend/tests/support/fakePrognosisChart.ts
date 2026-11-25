/**
 * A stand-in for the Race Prognosis trend's uPlot adapter, since jsdom has no canvas. It records what the
 * Dashboard asks of it. Install with:
 *
 *   vi.mock('../src/features/race-prognosis/prognosisChart', () => import('./support/fakePrognosisChart').then((m) => m.fakePrognosisChartModule));
 */
import { vi } from 'vitest';
import type { PrognosisChartOptions } from '../../src/features/race-prognosis/prognosisChart';

export interface FakePrognosisChart {
  readonly options: PrognosisChartOptions;
  readonly setTheme: ReturnType<typeof vi.fn>;
  readonly destroy: ReturnType<typeof vi.fn>;
}

export const prognosisCharts = {
  all: [] as FakePrognosisChart[],
  /** Charts not yet destroyed, i.e. currently on screen. */
  live(): FakePrognosisChart[] {
    return this.all.filter((chart) => chart.destroy.mock.calls.length === 0);
  },
  reset() {
    this.all.length = 0;
  },
};

export const fakePrognosisChartModule = {
  createPrognosisChart(options: PrognosisChartOptions): FakePrognosisChart {
    const chart = { options, setTheme: vi.fn(), destroy: vi.fn() };
    prognosisCharts.all.push(chart);
    return chart;
  },
};
