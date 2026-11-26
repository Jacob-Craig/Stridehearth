/**
 * Thin adapter over `uPlot`: the **Race Prognosis** trend, one line of predicted pace per race distance over
 * the athlete's days, on an inverted pace axis so that faster is higher. Only this module touches the uPlot
 * API for it.
 *
 * Callback parameters named `u` are uPlot instances, following uPlot's own documentation and types.
 */
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import type { ColorScheme } from '../../lib/colorScheme';
import { AXIS_PALETTE } from '../charts/chartTheme';
import { calendarDate, DATE_TICK_SPACE_PX, dateTickLabels } from '../charts/dateAxis';
import {
  type AlignedPrognosisData,
  formatPaceSeconds,
  PROGNOSIS_PALETTE,
  PROGNOSIS_PLOT_HEIGHT_PX,
  RACE_DISTANCES,
} from './prognosisSeries';

const Y_AXIS_SIZE = 72;

export interface PrognosisChartOptions {
  readonly container: HTMLElement;
  readonly data: AlignedPrognosisData;
  readonly theme: ColorScheme;
  /** Called with the index of the day under the cursor, or `null` when it leaves the chart. */
  readonly onHover: (dayIndex: number | null) => void;
}

export interface PrognosisChartHandle {
  /** Restyles the live chart for a colour scheme. */
  setTheme(theme: ColorScheme): void;
  destroy(): void;
}

export function createPrognosisChart(options: PrognosisChartOptions): PrognosisChartHandle {
  const { container, data, onHover } = options;
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
      height: PROGNOSIS_PLOT_HEIGHT_PX,
      legend: { show: false },
      tzDate: calendarDate,
      cursor: { drag: { x: false, y: false }, y: false },
      scales: {
        x: { time: true },
        // Faster paces (fewer seconds per km) are drawn higher.
        y: { dir: -1 },
      },
      axes: [
        {
          ...axis,
          space: DATE_TICK_SPACE_PX,
          // Days sit at midnight UTC, so their ticks are labelled in UTC.
          values: (_u: uPlot, ticks: number[]) => dateTickLabels(ticks, 'UTC'),
        },
        {
          ...axis,
          size: Y_AXIS_SIZE,
          values: (_u: uPlot, ticks: number[]) => ticks.map(formatPaceSeconds),
        },
      ],
      series: [
        {},
        ...RACE_DISTANCES.map(({ distance, label }) => ({
          label,
          stroke: () => PROGNOSIS_PALETTE[theme][distance],
          width: 2,
          points: { show: false },
        })),
      ],
      hooks: {
        setCursor: [
          (u: uPlot) => {
            const index = u.cursor.idx;
            onHover(index === undefined || index === null ? null : index);
          },
        ],
      },
    },
    // uPlot does not mutate its data; it is typed as mutable only.
    [data.x, ...RACE_DISTANCES.map(({ distance }) => data.paces[distance])],
    container,
  );

  const resizeObserver =
    typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(() =>
          plot.setSize({
            width: container.clientWidth,
            height: PROGNOSIS_PLOT_HEIGHT_PX,
          }),
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
