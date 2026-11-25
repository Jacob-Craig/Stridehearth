import { describe, expect, it, vi } from 'vitest';
import { createElevationProfileChart } from '../src/features/race-split-plan/elevationProfileChart';

/** A stand-in for uPlot (jsdom has no canvas) that keeps what it was built with. */
const fakeUplot = vi.hoisted(() => {
  const instances: FakeUplot[] = [];
  interface FakeOptions {
    axes: Array<{ values?: (u: FakeUplot, ticks: number[]) => string[] }>;
    series: Array<{ stroke?: () => string; fill?: () => string }>;
    hooks: { draw: Array<(u: FakeUplot) => void> };
  }
  class FakeUplot {
    readonly bbox = { top: 10, height: 100 };
    readonly setSize = vi.fn();
    readonly destroy = vi.fn();
    readonly redraw = vi.fn();
    readonly ctx = {
      save: vi.fn(),
      restore: vi.fn(),
      beginPath: vi.fn(),
      moveTo: vi.fn(),
      lineTo: vi.fn(),
      stroke: vi.fn(),
      strokeStyle: '',
      globalAlpha: 1,
      lineWidth: 1,
    };
    constructor(
      readonly opts: FakeOptions,
      readonly data: number[][],
      readonly target: HTMLElement,
    ) {
      instances.push(this);
    }
    /** 100 px per unit of distance. */
    valToPos(value: number) {
      return value * 100;
    }
  }
  return { FakeUplot, instances };
});

vi.mock('uplot', () => ({ default: fakeUplot.FakeUplot }));

function chart() {
  const handle = createElevationProfileChart({
    container: document.createElement('div'),
    data: { x: [0, 1, 2.5], altitude: [10, 40, 25], boundaries: [1, 2], unitLabel: 'km' },
    theme: 'light',
  });
  return { handle, plot: fakeUplot.instances.at(-1)! };
}

describe('elevation profile adapter', () => {
  it('plots altitude against distance with labelled axes', () => {
    const { plot } = chart();

    expect(plot.data).toEqual([
      [0, 1, 2.5],
      [10, 40, 25],
    ]);
    expect(plot.opts.axes[0]!.values!(plot, [0, 1])).toEqual(['0 km', '1 km']);
    expect(plot.opts.axes[1]!.values!(plot, [20])).toEqual(['20 m']);
  });

  it('draws a vertical line at each split boundary', () => {
    const { plot } = chart();

    for (const hook of plot.opts.hooks.draw) hook(plot);

    expect(plot.ctx.moveTo.mock.calls).toEqual([
      [100, 10],
      [200, 10],
    ]);
    expect(plot.ctx.lineTo.mock.calls).toEqual([
      [100, 110],
      [200, 110],
    ]);
  });

  it('restyles in place for a colour scheme, and tears down on destroy', () => {
    const { handle, plot } = chart();
    const line = plot.opts.series[1]!.stroke!;
    const light = line();

    handle.setTheme('dark');

    expect(plot.redraw).toHaveBeenCalledTimes(1);
    expect(line()).not.toBe(light);
    handle.destroy();
    expect(plot.destroy).toHaveBeenCalledTimes(1);
  });
});
