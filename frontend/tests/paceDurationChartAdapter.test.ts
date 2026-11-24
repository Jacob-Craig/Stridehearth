import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPaceDurationChart } from '../src/features/pace-duration/paceDurationChart';
import {
  PDC_TICKS_SECONDS,
  type PeakCurves,
  alignedPdcData,
} from '../src/features/pace-duration/paceDurationSeries';

/**
 * A stand-in for uPlot (jsdom has no canvas). Its x scale is log10 from 5 s to 3600 s over 0-1000 px; its y
 * scale is linear, 100 px per unit with 7 at the top, so 6 m/s sits at y = 100 and 5 m/s at y = 200.
 */
const fakeUplot = vi.hoisted(() => {
  const instances: FakeUplot[] = [];
  interface FakeOptions {
    scales: { x: { distr: number; log: number; time: boolean; range: () => number[] } };
    axes: Array<{ splits?: () => number[]; values: (u: FakeUplot, ticks: number[]) => string[] }>;
    series: Array<{ label?: string; stroke?: () => string; dash?: number[] }>;
  }
  const lo = Math.log10(5);
  const hi = Math.log10(3600);

  class FakeUplot {
    readonly over = document.createElement('div');
    readonly cursor = { left: -10, top: -10 };
    readonly setSize = vi.fn();
    readonly destroy = vi.fn();
    readonly redraw = vi.fn();

    constructor(
      readonly opts: FakeOptions,
      readonly data: [number[], ...(number | null)[][]],
      readonly target: HTMLElement,
    ) {
      instances.push(this);
    }

    valToPos(value: number, scale: string) {
      return scale === 'x' ? ((Math.log10(value) - lo) / (hi - lo)) * 1000 : (7 - value) * 100;
    }

    posToVal(pos: number, scale: string) {
      return scale === 'x' ? 10 ** (lo + (pos / 1000) * (hi - lo)) : 7 - pos / 100;
    }

    click(durationSeconds: number, value: number) {
      this.cursor.left = this.valToPos(durationSeconds, 'x');
      this.cursor.top = this.valToPos(value, 'y');
      this.over.dispatchEvent(new MouseEvent('click'));
    }
  }
  return { FakeUplot, instances };
});

vi.mock('uplot', () => ({ default: fakeUplot.FakeUplot }));

const point = (durationSeconds: number, value: number, activityId: number) => ({
  durationSeconds,
  value,
  activityId,
  activityTitle: `Run ${activityId}`,
  activityStartedAt: '2026-10-01T08:00:00Z',
});

const curves: PeakCurves = {
  recent: [point(60, 5.0, 11), point(600, 4.0, 12)],
  allTime: [point(60, 6.0, 21), point(600, 4.6, 22)],
};

function chart(mode: 'pace' | 'power' = 'pace') {
  const onSelectActivity = vi.fn();
  const handle = createPaceDurationChart({
    container: document.createElement('div'),
    data: alignedPdcData(curves, null),
    curves,
    mode,
    theme: 'light',
    onSelectActivity,
  });
  const plot = fakeUplot.instances.at(-1)!;
  return { handle, plot, onSelectActivity };
}

beforeEach(() => {
  fakeUplot.instances.length = 0;
});

describe('Pace-Duration Curve uPlot adapter', () => {
  it('plots duration on a fixed log10 axis from 5 s to 60 min with a tick per Benchmark Duration', () => {
    const { plot } = chart();

    expect(plot.opts.scales.x).toMatchObject({ time: false, distr: 3, log: 10 });
    expect(plot.opts.scales.x.range()).toEqual([5, 3600]);
    expect(plot.opts.axes[0]!.splits!()).toEqual(PDC_TICKS_SECONDS);
    expect(plot.opts.axes[0]!.values(plot, [5, 60, 3600])).toEqual(['5s', '1m', '60m']);
    expect(plot.data).toEqual([
      [60, 600],
      [5.0, 4.0],
      [6.0, 4.6],
      [null, null],
    ]);
    expect(plot.opts.series.slice(1).map((s) => s.label)).toEqual(['90 days', 'All-Time', 'Model']);
  });

  it('labels the y axis as pace in pace mode and watts in power mode', () => {
    expect(chart('pace').plot.opts.axes[1]!.values(fakeUplot.instances[0]!, [5])).toEqual([
      '03:20 /km',
    ]);
    expect(chart('power').plot.opts.axes[1]!.values(fakeUplot.instances[1]!, [300])).toEqual([
      '300 W',
    ]);
  });

  it('opens the Activity of the peak clicked, on whichever curve it lies', () => {
    const { plot, onSelectActivity } = chart();

    plot.click(60, 6.0);
    plot.click(62, 5.05);
    plot.click(600, 4.6);

    expect(onSelectActivity.mock.calls).toEqual([[21], [11], [22]]);
  });

  it('ignores a click away from every peak', () => {
    const { plot, onSelectActivity } = chart();

    plot.click(200, 6.5);

    expect(onSelectActivity).not.toHaveBeenCalled();
  });

  it('stops listening once destroyed', () => {
    const { handle, plot, onSelectActivity } = chart();

    handle.destroy();
    plot.click(60, 6.0);

    expect(plot.destroy).toHaveBeenCalled();
    expect(onSelectActivity).not.toHaveBeenCalled();
  });
});
