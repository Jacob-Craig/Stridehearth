/**
 * Thin adapter over `uPlot`: a **Race Split Plan**'s elevation profile, altitude against distance from the
 * start in the plan's unit, with a faint line at each split boundary. Only this module touches the uPlot API
 * for it.
 *
 * Callback parameters named `u` are uPlot instances, following uPlot's own documentation and types.
 */
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import type { ColorScheme } from '../../lib/colorScheme';
import { AXIS_PALETTE } from '../charts/chartTheme';
import { type ElevationProfileData, PROFILE_PLOT_HEIGHT_PX } from './splitPlan';

const PROFILE_PALETTE: Record<ColorScheme, { readonly line: string; readonly fill: string }> = {
  light: { line: '#2563eb', fill: 'rgba(37, 99, 235, 0.15)' },
  dark: { line: '#60a5fa', fill: 'rgba(96, 165, 250, 0.2)' },
};

export interface ElevationProfileOptions {
  readonly container: HTMLElement;
  readonly data: ElevationProfileData;
  readonly theme: ColorScheme;
}

export interface ElevationProfileHandle {
  /** Restyles the live chart for a colour scheme. */
  setTheme(theme: ColorScheme): void;
  destroy(): void;
}

export function createElevationProfileChart(
  options: ElevationProfileOptions,
): ElevationProfileHandle {
  const { container, data } = options;
  let theme = options.theme;

  const axis = {
    stroke: () => AXIS_PALETTE[theme].axis,
    grid: { stroke: () => AXIS_PALETTE[theme].grid, width: 1 },
    ticks: { show: false },
  };
  const plot = new uPlot(
    {
      width: container.clientWidth,
      height: PROFILE_PLOT_HEIGHT_PX,
      legend: { show: false },
      cursor: { show: false },
      scales: { x: { time: false } },
      axes: [
        {
          ...axis,
          values: (_u: uPlot, ticks: number[]) => ticks.map((t) => `${t} ${data.unitLabel}`),
        },
        { ...axis, size: 56, values: (_u: uPlot, ticks: number[]) => ticks.map((t) => `${t} m`) },
      ],
      series: [
        {},
        {
          label: 'Altitude',
          stroke: () => PROFILE_PALETTE[theme].line,
          fill: () => PROFILE_PALETTE[theme].fill,
          width: 2,
          points: { show: false },
        },
      ],
      hooks: {
        draw: [
          (u: uPlot) => {
            const { ctx } = u;
            const { top, height } = u.bbox;
            ctx.save();
            ctx.strokeStyle = AXIS_PALETTE[theme].axis;
            ctx.globalAlpha = 0.35;
            ctx.lineWidth = 1;
            for (const boundary of data.boundaries) {
              const x = Math.round(u.valToPos(boundary, 'x', true));
              ctx.beginPath();
              ctx.moveTo(x, top);
              ctx.lineTo(x, top + height);
              ctx.stroke();
            }
            ctx.restore();
          },
        ],
      },
    },
    // uPlot does not mutate its data; it is typed as mutable only.
    [data.x, data.altitude],
    container,
  );

  const resizeObserver =
    typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(() =>
          plot.setSize({ width: container.clientWidth, height: PROFILE_PLOT_HEIGHT_PX }),
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
