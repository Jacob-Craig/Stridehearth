/**
 * A stand-in for the Personal Record Progression's uPlot adapter, since jsdom has no canvas. It
 * records what the Dashboard asks of it. Install with:
 *
 *   vi.mock('../src/features/pr-progression/progressionChart', () => import('./support/fakeProgressionChart').then((m) => m.fakeProgressionChartModule));
 */
import { vi } from 'vitest';
import type { ProgressionChartOptions } from '../../src/features/pr-progression/progressionChart';

export interface FakeProgressionChart {
  readonly options: ProgressionChartOptions;
  readonly setTheme: ReturnType<typeof vi.fn>;
  readonly destroy: ReturnType<typeof vi.fn>;
}

export const progressionCharts = {
  all: [] as FakeProgressionChart[],
  /** Charts not yet destroyed, i.e. currently on screen. */
  live(): FakeProgressionChart[] {
    return this.all.filter((chart) => chart.destroy.mock.calls.length === 0);
  },
  reset() {
    this.all.length = 0;
  },
};

export const fakeProgressionChartModule = {
  createProgressionChart(options: ProgressionChartOptions): FakeProgressionChart {
    const chart = { options, setTheme: vi.fn(), destroy: vi.fn() };
    progressionCharts.all.push(chart);
    return chart;
  },
};
