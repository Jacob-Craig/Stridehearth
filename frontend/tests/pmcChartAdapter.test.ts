import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PmcDayDto } from '../src/api/generated/types.gen';
import { createPmcChart } from '../src/features/pmc/pmcChart';
import { PMC_PALETTE } from '../src/features/pmc/pmcPalette';
import { pmcDay, pmcSeries } from './support/fakePmcChart';

/**
 * A stand-in for uPlot's canvas renderer (jsdom has no canvas). Its y scale is linear, 2px per unit with
 * the top of the plot at y = 30; moving the cursor snaps `cursor.idx` to the nearest day as uPlot does.
 */
const fakeUplot = vi.hoisted(() => {
  const instances: FakeUplot[] = [];

  type Hook = (u: FakeUplot) => void;
  type Stroke = (u: FakeUplot, index: number) => string;
  type Range = (u: FakeUplot, min: number | null, max: number | null, key: string) => number[];
  interface FakeOptions {
    hooks: { setCursor: Hook[]; drawClear?: Hook[] };
    cursor: { sync: { key: string } };
    scales: { x: { time: boolean }; y?: { range?: Range } };
    axes: Array<{
      stroke: Stroke;
      grid: { stroke: Stroke };
      space?: number;
      values?: (u: FakeUplot, ticks: number[]) => string[];
    }>;
    series: Array<{ label?: string; stroke?: Stroke }>;
    height: number;
    tzDate: (seconds: number) => Date;
  }

  class FakeUplot {
    static readonly sync = vi.fn();
    readonly cursor: { idx: number | null; left: number } = { idx: null, left: -10 };
    readonly bbox = { left: 5, top: 0, width: 1000, height: 140 };
    readonly scales = { y: { min: -40, max: 30 } };
    readonly fills: Array<{
      rect: [number, number, number, number];
      style: string;
      alpha: number;
    }> = [];
    readonly ctx = {
      save: vi.fn(),
      restore: vi.fn(),
      fillRect: (...rect: [number, number, number, number]) =>
        this.fills.push({ rect, style: this.ctx.fillStyle, alpha: this.ctx.globalAlpha }),
      fillStyle: '',
      globalAlpha: 1,
    };
    readonly setScale = vi.fn();
    readonly setSize = vi.fn();
    readonly destroy = vi.fn();
    readonly redraw = vi.fn(() => this.drawClear());

    constructor(
      readonly opts: FakeOptions,
      readonly data: [number[], ...(number | null)[][]],
      readonly target: HTMLElement,
    ) {
      instances.push(this);
    }

    valToPos(value: number, scale: string) {
      if (scale !== 'y') throw new Error(`unexpected scale ${scale}`);
      return (this.scales.y.max - value) * 2;
    }

    drawClear() {
      this.fills.length = 0;
      for (const hook of this.opts.hooks.drawClear ?? []) hook(this);
    }

    strokes() {
      return this.opts.series.slice(1).map((series, i) => series.stroke!(this, i + 1));
    }

    /** What uPlot does on a pointer move, or a move synced from a sibling pane. */
    hover(idx: number | null) {
      this.cursor.idx = idx;
      this.cursor.left = idx == null ? -10 : idx * 10;
      for (const hook of this.opts.hooks.setCursor) hook(this);
    }
  }

  return { FakeUplot, instances };
});

vi.mock('uplot', () => ({ default: fakeUplot.FakeUplot }));

const dayStart = (date: string) => Date.parse(`${date}T00:00:00Z`) / 1000;

function chart(
  days: readonly PmcDayDto[] = pmcSeries(400).days,
  range: '90d' | '1y' | 'all' = '90d',
) {
  const panes = {
    fitness: document.createElement('div'),
    form: document.createElement('div'),
    acwr: document.createElement('div'),
  };
  const onHover = vi.fn();
  const handle = createPmcChart({ panes, days, theme: 'light', range, onHover });
  const [fitness, form, acwr] = fakeUplot.instances.slice(-3) as [
    InstanceType<typeof fakeUplot.FakeUplot>,
    InstanceType<typeof fakeUplot.FakeUplot>,
    InstanceType<typeof fakeUplot.FakeUplot>,
  ];
  return { handle, panes, onHover, fitness, form, acwr, plots: [fitness, form, acwr] };
}

beforeEach(() => {
  fakeUplot.instances.length = 0;
});

describe('PMC uPlot adapter', () => {
  const days = [
    pmcDay({ date: '2026-03-01', ctl: 10, atl: 20, tsb: 0, acwr: null }),
    pmcDay({ date: '2026-03-02', ctl: 11, atl: 25, tsb: -10, acwr: 1.1 }),
  ];

  it('draws Fitness and Fatigue, Form, and ACWR in three panes on athlete-local days', () => {
    const { panes, fitness, form, acwr } = chart(days);
    const xs = [dayStart('2026-03-01'), dayStart('2026-03-02')];

    expect(fakeUplot.instances).toHaveLength(3);
    expect(fitness.target).toBe(panes.fitness);
    expect(fitness.data).toEqual([xs, [10, 11], [20, 25]]);
    expect(fitness.opts.series.slice(1).map((s) => s.label)).toEqual([
      'Fitness (CTL)',
      'Fatigue (ATL)',
    ]);
    expect(form.target).toBe(panes.form);
    expect(form.data).toEqual([xs, [0, -10]]);
    expect(acwr.target).toBe(panes.acwr);
    expect(acwr.data).toEqual([xs, [null, 1.1]]);
  });

  it('labels the days as calendar dates, never shifted into the viewer’s zone', () => {
    const { fitness } = chart(days);
    const date = fitness.opts.tzDate(dayStart('2026-03-02'));
    expect([date.getFullYear(), date.getMonth(), date.getDate(), date.getHours()]).toEqual([
      2026, 2, 2, 0,
    ]);
  });

  it('labels the date ticks as the rest of the app does: by day, or by month when ticks are far apart', () => {
    const { acwr } = chart(days);
    const values = acwr.opts.axes[0]!.values!;
    expect(values(acwr, [dayStart('2026-08-21'), dayStart('2026-08-28')])).toEqual([
      '21 Aug',
      '28 Aug',
    ]);
    expect(values(acwr, [dayStart('2026-03-01'), dayStart('2026-04-01')])).toEqual([
      'Mar 2026',
      'Apr 2026',
    ]);
  });

  it('spaces the date ticks wide enough for a month label', () => {
    const { acwr } = chart(days);
    expect(acwr.opts.axes[0]!.space).toBeGreaterThanOrEqual(80);
  });

  it('lets the short ACWR pane label the corridor with more than one tick', () => {
    // 120 px less the x-axis leaves ~70 px for a 0.4–1.7 scale: 0.5 steps are ~27 px apart.
    const { acwr } = chart(days);
    expect(acwr.opts.axes[1]!.space).toBeLessThanOrEqual(25);
  });

  it('shares one cursor between the panes, and none with another chart', () => {
    const first = chart(days);
    const second = chart(days);
    const keys = first.plots.map((u) => u.opts.cursor.sync.key);
    expect(new Set(keys).size).toBe(1);
    expect(second.fitness.opts.cursor.sync.key).not.toBe(keys[0]);
  });

  it('starts on the range it is given, on every pane', () => {
    const series = pmcSeries(400).days;
    const { plots } = chart(series, '90d');
    for (const u of plots) {
      expect(u.setScale).toHaveBeenLastCalledWith('x', {
        min: dayStart(series[310]!.date),
        max: dayStart(series[399]!.date),
      });
    }
  });

  it('moves every pane to a new range in place', () => {
    const series = pmcSeries(400).days;
    const { handle, plots } = chart(series, '90d');

    handle.setRange('1y');
    for (const u of plots) {
      expect(u.setScale).toHaveBeenLastCalledWith('x', {
        min: dayStart(series[35]!.date),
        max: dayStart(series[399]!.date),
      });
    }
    handle.setRange('all');
    for (const u of plots) {
      expect(u.setScale).toHaveBeenLastCalledWith('x', {
        min: dayStart(series[0]!.date),
        max: dayStart(series[399]!.date),
      });
      expect(u.destroy).not.toHaveBeenCalled();
    }
    expect(fakeUplot.instances).toHaveLength(3);
  });

  it('widens a single day to a whole day so the scale is not empty', () => {
    const { fitness } = chart([pmcDay({ date: '2026-03-01' })], 'all');
    expect(fitness.setScale).toHaveBeenLastCalledWith('x', {
      min: dayStart('2026-03-01') - 43_200,
      max: dayStart('2026-03-01') + 43_200,
    });
  });

  it('reports the day under the cursor once, however many panes it moves, and when it leaves', () => {
    const { fitness, form, acwr, onHover } = chart(days);

    fitness.hover(1);
    form.hover(1);
    acwr.hover(1);
    expect(onHover.mock.calls).toEqual([[1]]);

    form.hover(0);
    fitness.hover(0);
    form.hover(null);
    fitness.hover(null);
    expect(onHover.mock.calls).toEqual([[1], [0], [null]]);
  });

  it('shades the five Form Zones behind Form, clipped to the plotted range', () => {
    const { form } = chart(days);
    form.drawClear();

    // y = (30 - Form) * 2 over a scale of -40 to 30.
    const palette = PMC_PALETTE.light;
    expect(form.fills).toEqual([
      { rect: [5, 120, 1000, 20], style: palette.zones['high-risk'], alpha: palette.bandAlpha },
      { rect: [5, 80, 1000, 40], style: palette.zones.optimal, alpha: palette.bandAlpha },
      { rect: [5, 50, 1000, 30], style: palette.zones.grey, alpha: palette.bandAlpha },
      { rect: [5, 10, 1000, 40], style: palette.zones.fresh, alpha: palette.bandAlpha },
      { rect: [5, 0, 1000, 10], style: palette.zones.transition, alpha: palette.bandAlpha },
    ]);
  });

  it('shades the ACWR corridor from 0.80 to 1.30', () => {
    const { acwr } = chart(days);
    acwr.scales.y = { min: 0.5, max: 1.6 };
    acwr.drawClear();

    // y = (1.6 - ACWR) * 2: 1.30 at 0.6, 0.80 at 1.6.
    expect(acwr.fills).toHaveLength(1);
    const [left, top, width, height] = acwr.fills[0]!.rect;
    expect([left, width]).toEqual([5, 1000]);
    expect(top).toBeCloseTo(0.6);
    expect(height).toBeCloseTo(1.0);
    expect(acwr.fills[0]!.style).toBe(PMC_PALETTE.light.corridor);
  });

  it('keeps every Form Zone and the corridor in view, widening for values beyond them', () => {
    const { form, acwr } = chart(days);
    const formRange = form.opts.scales.y!.range!;
    const acwrRange = acwr.opts.scales.y!.range!;

    expect(formRange(form, -12, 4, 'y')).toEqual([-40, 30]);
    expect(formRange(form, -52, 41, 'y')).toEqual([-57, 46]);
    expect(acwrRange(acwr, 1.0, 1.1, 'y')).toEqual([0.5, 1.6]);
    expect(acwrRange(acwr, null, null, 'y')).toEqual([0.5, 1.6]);
    expect(acwrRange(acwr, 0.2, 2.4, 'y')).toEqual([0.1, 2.5]);
  });

  it('restyles every pane in place on a colour-scheme change', () => {
    const { handle, plots, fitness, form } = chart(days);
    expect(fitness.strokes()).toEqual([PMC_PALETTE.light.fitness, PMC_PALETTE.light.fatigue]);

    handle.setTheme('dark');

    for (const u of plots) expect(u.redraw).toHaveBeenCalledWith(false);
    expect(fitness.strokes()).toEqual([PMC_PALETTE.dark.fitness, PMC_PALETTE.dark.fatigue]);
    expect(form.strokes()).toEqual([PMC_PALETTE.dark.form]);
    expect(form.fills[0]!.alpha).toBe(PMC_PALETTE.dark.bandAlpha);
    expect(fakeUplot.instances).toHaveLength(3);
  });

  it('tears down every pane', () => {
    const { handle, plots } = chart(days);
    handle.destroy();
    for (const u of plots) expect(u.destroy).toHaveBeenCalledOnce();
  });
});
