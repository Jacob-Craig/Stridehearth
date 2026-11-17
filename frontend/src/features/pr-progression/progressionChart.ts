/**
 * Thin adapter over `uPlot`: the **Personal Record Progression** of one Benchmark Distance as a
 * stepped line of record time against date. Only this module touches the uPlot API for it.
 *
 * Callback parameters named `u` are uPlot instances, following uPlot's own documentation and types.
 */
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import type { ColorScheme } from '../../lib/colorScheme';
import { formatDuration } from '../../lib/format';
import { AXIS_PALETTE } from '../charts/chartTheme';
import { PROGRESSION_PLOT_HEIGHT_PX, type StepPoints } from './progressionSeries';

/** The blue of the record figures on the Personal Record cards. */
const LINE_COLOR: Record<ColorScheme, string> = { light: '#2563eb', dark: '#60a5fa' };

const DATE_FORMAT = new Intl.DateTimeFormat('en-GB', {
  month: 'short',
  year: 'numeric',
  timeZone: 'UTC',
});

export interface ProgressionChartOptions {
  readonly container: HTMLElement;
  readonly points: StepPoints;
  /** How many leading points are records; any point after them only extends the last step to today. */
  readonly recordCount: number;
  readonly theme: ColorScheme;
}

export interface ProgressionChartHandle {
  /** Restyles the live chart for a colour scheme. */
  setTheme(theme: ColorScheme): void;
  destroy(): void;
}

export function createProgressionChart(options: ProgressionChartOptions): ProgressionChartHandle {
  const { container, points, recordCount } = options;
  const recordIndices = Array.from({ length: recordCount }, (_, i) => i);
  const stepped = uPlot.paths.stepped;
  let theme = options.theme;

  // Strokes are read from `theme` on every draw, so a colour-scheme change is a redraw, not a rebuild.
  const axis = {
    stroke: () => AXIS_PALETTE[theme].axis,
    grid: { stroke: () => AXIS_PALETTE[theme].grid, width: 1 },
    ticks: { show: false },
  };
  const plot = new uPlot(
    {
      width: container.clientWidth,
      height: PROGRESSION_PLOT_HEIGHT_PX,
      legend: { show: false },
      cursor: { drag: { x: false, y: false } },
      axes: [
        {
          ...axis,
          values: (_u, ticks) => ticks.map((tick) => DATE_FORMAT.format(new Date(tick * 1000))),
        },
        { ...axis, size: 70, values: (_u, ticks) => ticks.map((tick) => formatDuration(tick)) },
      ],
      series: [
        {},
        {
          label: 'Personal Record',
          stroke: () => LINE_COLOR[theme],
          width: 2,
          // A record holds until the next one is set, so the line steps after each point.
          ...(stepped ? { paths: stepped({ align: 1 }) } : {}),
          // A dot marks each record, not the end of the line at today.
          points: { show: true, size: 7, filter: () => recordIndices },
        },
      ],
    },
    // uPlot does not mutate its data; it is typed as mutable only.
    [points.x, points.y],
    container,
  );

  const resizeObserver =
    typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(() =>
          plot.setSize({ width: container.clientWidth, height: PROGRESSION_PLOT_HEIGHT_PX }),
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
      plot.destroy();
    },
  };
}
