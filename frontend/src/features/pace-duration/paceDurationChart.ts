/**
 * Thin adapter over `uPlot`: the **Pace-Duration Curve** on a log duration axis, with the 90-day and All-Time
 * Peak Effort curves and the Critical Speed (or Critical Power) model. Only this module touches the uPlot API
 * for it.
 *
 * Callback parameters named `u` are uPlot instances, following uPlot's own documentation and types.
 */
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import type { ColorScheme } from '../../lib/colorScheme';
import { AXIS_PALETTE } from '../charts/chartTheme';
import {
  type AlignedPdcData,
  durationTickLabel,
  formatPdcValue,
  nearestPeak,
  PDC_MAX_SECONDS,
  PDC_MIN_SECONDS,
  PDC_PALETTE,
  PDC_PLOT_HEIGHT_PX,
  PDC_TICKS_SECONDS,
  type PdcMode,
  type PeakCurves,
} from './paceDurationSeries';

/** How near, in CSS pixels, a click must land to a peak to open its Activity. */
const CLICK_RADIUS_PX = 16;

export interface PaceDurationChartOptions {
  readonly container: HTMLElement;
  readonly data: AlignedPdcData;
  /** The peaks behind the plotted points, to find the Activity a click lands on. */
  readonly curves: PeakCurves;
  readonly mode: PdcMode;
  readonly theme: ColorScheme;
  /** Called with the Activity of the peak the athlete clicked. */
  readonly onSelectActivity: (activityId: number) => void;
}

export interface PaceDurationChartHandle {
  /** Restyles the live chart for a colour scheme. */
  setTheme(theme: ColorScheme): void;
  destroy(): void;
}

export function createPaceDurationChart(
  options: PaceDurationChartOptions,
): PaceDurationChartHandle {
  const { container, data, curves, mode, onSelectActivity } = options;
  let theme = options.theme;

  // Strokes are read from `theme` on every draw, so a colour-scheme change is a redraw, not a rebuild.
  const axis = {
    stroke: () => AXIS_PALETTE[theme].axis,
    grid: { stroke: () => AXIS_PALETTE[theme].grid, width: 1 },
    ticks: { show: false },
  };
  const peakSeries = (label: string, color: () => string) => ({
    label,
    stroke: color,
    width: 2,
    spanGaps: true,
    points: { show: true, size: 7, stroke: color, fill: color },
  });
  const plot = new uPlot(
    {
      width: container.clientWidth,
      height: PDC_PLOT_HEIGHT_PX,
      legend: { show: false },
      cursor: { drag: { x: false, y: false } },
      scales: {
        x: { time: false, distr: 3, log: 10, range: () => [PDC_MIN_SECONDS, PDC_MAX_SECONDS] },
      },
      axes: [
        {
          ...axis,
          splits: () => [...PDC_TICKS_SECONDS],
          // Keep every Benchmark Duration: uPlot's default log10 filter would blank all but its 1-2-5 ticks.
          filter: (_u: uPlot, splits: number[]) => splits,
          values: (_u, ticks) => ticks.map(durationTickLabel),
        },
        {
          ...axis,
          size: mode === 'pace' ? 80 : 60,
          values: (_u, ticks) => ticks.map((tick) => formatPdcValue(mode, tick)),
        },
      ],
      series: [
        {},
        peakSeries('90 days', () => PDC_PALETTE[theme].recent),
        peakSeries('All-Time', () => PDC_PALETTE[theme].allTime),
        {
          label: 'Model',
          stroke: () => PDC_PALETTE[theme].model,
          width: 2,
          dash: [6, 4],
          spanGaps: true,
          points: { show: false },
        },
      ],
    },
    // uPlot does not mutate its data; it is typed as mutable only.
    [data.x, data.recent, data.allTime, data.model],
    container,
  );

  // A click opens the Activity of the nearest peak on either curve, if it lands close enough to one.
  const onClick = () => {
    const { left, top } = plot.cursor;
    if (left === undefined || top === undefined || left < 0) return;
    const durationSeconds = plot.posToVal(left, 'x');
    let chosen: { activityId: number; distance: number } | null = null;
    for (const curve of [curves.recent, curves.allTime]) {
      const peak = nearestPeak(curve, durationSeconds);
      if (!peak) continue;
      const distance = Math.hypot(
        plot.valToPos(peak.durationSeconds, 'x') - left,
        plot.valToPos(peak.value, 'y') - top,
      );
      if (distance <= CLICK_RADIUS_PX && (!chosen || distance < chosen.distance)) {
        chosen = { activityId: peak.activityId, distance };
      }
    }
    if (chosen) onSelectActivity(chosen.activityId);
  };
  plot.over.addEventListener('click', onClick);
  plot.over.style.cursor = 'pointer';

  const resizeObserver =
    typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(() =>
          plot.setSize({ width: container.clientWidth, height: PDC_PLOT_HEIGHT_PX }),
        );
  resizeObserver?.observe(container);

  return {
    setTheme(next) {
      if (next === theme) return;
      theme = next;
      plot.redraw(false);
    },
    destroy() {
      resizeObserver?.disconnect();
      plot.over.removeEventListener('click', onClick);
      plot.destroy();
    },
  };
}
