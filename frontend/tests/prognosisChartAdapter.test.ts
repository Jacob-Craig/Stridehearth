import { describe, expect, it, vi } from 'vitest';
import type { RacePrognosisDayDto } from '../src/api/generated/types.gen';
import { createPrognosisChart } from '../src/features/race-prognosis/prognosisChart';
import {
  PROGNOSIS_PALETTE,
  alignedPrognosisData,
} from '../src/features/race-prognosis/prognosisSeries';

/** A stand-in for uPlot (jsdom has no canvas) that keeps what it was built with. */
const fakeUplot = vi.hoisted(() => {
  const instances: FakeUplot[] = [];
  interface FakeOptions {
    scales: { y: { dir: number } };
    axes: Array<{ values?: (u: FakeUplot, ticks: number[]) => string[] }>;
    series: Array<{ label?: string; stroke?: () => string }>;
    hooks: { setCursor: Array<(u: FakeUplot) => void> };
  }
  class FakeUplot {
    readonly cursor: { idx: number | null } = { idx: null };
    readonly setSize = vi.fn();
    readonly destroy = vi.fn();
    readonly redraw = vi.fn();
    constructor(
      readonly opts: FakeOptions,
      readonly data: number[][],
      readonly target: HTMLElement,
    ) {
      instances.push(this);
    }
    hover(idx: number | null) {
      this.cursor.idx = idx;
      for (const hook of this.opts.hooks.setCursor) hook(this);
    }
  }
  return { FakeUplot, instances };
});

vi.mock('uplot', () => ({ default: fakeUplot.FakeUplot }));

const DAYS: RacePrognosisDayDto[] = [
  {
    day: '2026-11-23',
    fiveKSeconds: 1100,
    tenKSeconds: 2340,
    halfSeconds: 5485.35,
    marathonSeconds: 12658.5,
  },
];

function chart() {
  const onHover = vi.fn();
  const handle = createPrognosisChart({
    container: document.createElement('div'),
    data: alignedPrognosisData(DAYS),
    theme: 'light',
    onHover,
  });
  return { handle, onHover, plot: fakeUplot.instances.at(-1)! };
}

describe('Race Prognosis trend adapter', () => {
  it('plots each distance in pace per km, shortest first, faster higher', () => {
    const { plot } = chart();
    expect(plot.opts.scales.y.dir).toBe(-1);
    expect(plot.opts.series.slice(1).map((s) => s.label)).toEqual([
      '5K',
      '10K',
      'Half Marathon',
      'Marathon',
    ]);
    // 1100 s / 5 km = 220 s/km; 2340 / 10 = 234; 5485.35 / 21.0975 = 260; 12658.5 / 42.195 = 300.
    expect(plot.data[1]).toEqual([220]);
    expect(plot.data[2]).toEqual([234]);
    expect(plot.data[3]![0]).toBeCloseTo(260, 9);
    expect(plot.data[4]![0]).toBeCloseTo(300, 9);
    expect(plot.opts.axes[1]!.values!(plot, [240, 300])).toEqual(['4:00 /km', '5:00 /km']);
  });

  it('reports the day under the cursor, and null when it leaves', () => {
    const { plot, onHover } = chart();
    plot.hover(0);
    plot.hover(null);
    expect(onHover.mock.calls).toEqual([[0], [null]]);
  });

  it('restyles in place for a colour scheme', () => {
    const { handle, plot } = chart();
    expect(plot.opts.series[4]!.stroke!()).toBe(PROGNOSIS_PALETTE.light.MARATHON);
    handle.setTheme('dark');
    expect(plot.redraw).toHaveBeenCalledTimes(1);
    expect(plot.opts.series[4]!.stroke!()).toBe(PROGNOSIS_PALETTE.dark.MARATHON);
    handle.destroy();
    expect(plot.destroy).toHaveBeenCalled();
  });
});
