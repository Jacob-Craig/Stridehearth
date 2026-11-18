/**
 * A stand-in for the **Performance Management Chart**'s uPlot adapter, since jsdom has no canvas. It records
 * what the panel asks of it and exposes the hover callback. Install with:
 *
 *   vi.mock('../src/features/pmc/pmcChart', () => import('./support/fakePmcChart').then((m) => m.fakePmcChartModule));
 */
import { vi } from 'vitest';
import type { PmcDayDto, PmcResponseDto } from '../../src/api/generated/types.gen';
import type { PmcChartOptions } from '../../src/features/pmc/pmcChart';

export interface FakePmcChart {
  readonly options: PmcChartOptions;
  readonly setRange: ReturnType<typeof vi.fn>;
  readonly setTheme: ReturnType<typeof vi.fn>;
  readonly destroy: ReturnType<typeof vi.fn>;
}

export const renderedPmcCharts = {
  charts: [] as FakePmcChart[],
  /** Charts not yet destroyed, i.e. currently on screen. */
  live(): FakePmcChart[] {
    return this.charts.filter((chart) => chart.destroy.mock.calls.length === 0);
  },
  reset() {
    this.charts.length = 0;
  },
};

export const fakePmcChartModule = {
  createPmcChart(options: PmcChartOptions): FakePmcChart {
    const chart = { options, setRange: vi.fn(), setTheme: vi.fn(), destroy: vi.fn() };
    renderedPmcCharts.charts.push(chart);
    return chart;
  },
};

/** One day of the PMC: an unloaded first day unless overridden. */
export function pmcDay(overrides: Partial<PmcDayDto> = {}): PmcDayDto {
  return {
    date: '2026-01-01',
    trainingLoad: 0,
    activityCount: 0,
    unscoredActivityCount: 0,
    ctl: 0,
    atl: 0,
    tsb: 0,
    acwr: null,
    monotony: null,
    strain: null,
    ...overrides,
  };
}

/** `count` consecutive days ending on `lastDate`, each with a Training Load of 50. */
export function pmcSeries(count: number, lastDate = '2026-11-18'): PmcResponseDto {
  const last = Date.parse(`${lastDate}T00:00:00Z`);
  const days = Array.from({ length: count }, (_, i) =>
    pmcDay({
      date: new Date(last - (count - 1 - i) * 86_400_000).toISOString().slice(0, 10),
      trainingLoad: 50,
      activityCount: 1,
      ctl: 30 + i * 0.1,
      atl: 40 + i * 0.1,
      tsb: -10 - i * 0.05,
    }),
  );
  return { timeZone: 'Europe/London', firstDay: days[0]?.date ?? null, days };
}
