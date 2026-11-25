/**
 * Thin adapter over `uPlot` for the **Performance Management Chart**: three stacked panes sharing the x-axis
 * and one cursor. Only this module touches the uPlot API for the PMC.
 *
 * Callback parameters named `u` are uPlot instances, following uPlot's own documentation and types.
 */
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import type { PmcDayDto } from '../../api/generated/types.gen';
import type { ColorScheme } from '../../lib/colorScheme';
import { AXIS_PALETTE } from '../charts/chartTheme';
import { calendarDate, dayStartSeconds, SECONDS_PER_DAY } from '../charts/dateAxis';
import { ACWR_CORRIDOR, FORM_ZONES } from './formZones';
import { PMC_PALETTE, PMC_PANE_HEIGHT_PX } from './pmcPalette';
import { type PmcRange, visibleWindow } from './pmcRange';

/** The three stacked panes of the chart, which share the x-axis and the cursor. */
export interface PmcPaneContainers {
  /** Fitness (CTL) and Fatigue (ATL). */
  readonly fitness: HTMLElement;
  /** Form (TSB) over the **Form Zones**. */
  readonly form: HTMLElement;
  /** ACWR over the **ACWR Corridor**. */
  readonly acwr: HTMLElement;
}

export interface PmcChartOptions {
  readonly panes: PmcPaneContainers;
  /** The whole series, dense and ascending; the range only changes what is in view. */
  readonly days: readonly PmcDayDto[];
  readonly theme: ColorScheme;
  readonly range: PmcRange;
  /** Called with the index of the day under the cursor, or `null` when it leaves the chart. */
  readonly onHover: (dayIndex: number | null) => void;
}

export interface PmcChartHandle {
  /** Shows another range of the same series, without rebuilding the chart. */
  setRange(range: PmcRange): void;
  /** Restyles the live chart for a colour scheme. */
  setTheme(theme: ColorScheme): void;
  destroy(): void;
}

/** Every Form Zone stays in view, however calm the athlete's Form. */
const FORM_FLOOR = -40;
const FORM_CEILING = 30;
const FORM_PADDING = 5;
/** The ACWR Corridor stays in view with room on both sides. */
const ACWR_FLOOR = 0.5;
const ACWR_CEILING = 1.6;
const ACWR_PADDING = 0.1;
/** The width of the y-axis on every pane, so the three plots line up. */
const Y_AXIS_SIZE = 50;
/** The x-axis is labelled under the bottom pane only; the panes above keep its grid. */
const UNLABELLED_X_AXIS_SIZE = 8;

let nextSyncKey = 0;

export function createPmcChart(options: PmcChartOptions): PmcChartHandle {
  const { panes, days, onHover } = options;
  let theme = options.theme;
  let hovered: number | null = null;
  const syncKey = `pmc-${nextSyncKey++}`;
  const xs = days.map((day) => dayStartSeconds(day.date));

  const palette = () => PMC_PALETTE[theme];

  const reportHover = (u: uPlot) => {
    const left = u.cursor.left;
    const dayIndex = left == null || left < 0 ? null : (u.cursor.idx ?? null);
    // Every synced pane fires on one pointer move; the day is reported once.
    if (dayIndex === hovered) return;
    hovered = dayIndex;
    onHover(dayIndex);
  };

  /** Shades the y-band `[min, max]` across the plot, clipped to the y scale in view. */
  const shadeBand = (u: uPlot, min: number, max: number, color: string) => {
    const scale = u.scales.y;
    if (scale?.min == null || scale.max == null) return;
    const lo = Math.max(min, scale.min);
    const hi = Math.min(max, scale.max);
    if (hi <= lo) return;
    const top = u.valToPos(hi, 'y', true);
    const bottom = u.valToPos(lo, 'y', true);
    u.ctx.save();
    u.ctx.globalAlpha = palette().bandAlpha;
    u.ctx.fillStyle = color;
    u.ctx.fillRect(u.bbox.left, top, u.bbox.width, bottom - top);
    u.ctx.restore();
  };

  const drawFormZones = (u: uPlot) => {
    for (const zone of FORM_ZONES) shadeBand(u, zone.min, zone.max, palette().zones[zone.id]);
  };

  const drawCorridor = (u: uPlot) => {
    shadeBand(u, ACWR_CORRIDOR.min, ACWR_CORRIDOR.max, palette().corridor);
  };

  // Strokes are read from `theme` on every draw, so a colour-scheme change is a redraw, not a rebuild.
  const axis = {
    stroke: () => AXIS_PALETTE[theme].axis,
    grid: { stroke: () => AXIS_PALETTE[theme].grid, width: 1 },
    ticks: { show: false },
  };

  const pane = (
    container: HTMLElement,
    height: number,
    series: uPlot.Series[],
    data: uPlot.AlignedData,
    extra: { labelX: boolean; yRange?: uPlot.Scale.Range; drawClear?: (u: uPlot) => void },
  ) => {
    const plot = new uPlot(
      {
        width: container.clientWidth,
        height,
        legend: { show: false },
        tzDate: calendarDate,
        cursor: { drag: { x: false, y: false }, y: false, sync: { key: syncKey } },
        scales: {
          x: { time: true },
          ...(extra.yRange && { y: { range: extra.yRange } }),
        },
        axes: [
          extra.labelX
            ? axis
            : { ...axis, size: UNLABELLED_X_AXIS_SIZE, values: (_u, ticks) => ticks.map(() => '') },
          { ...axis, size: Y_AXIS_SIZE },
        ],
        series: [{}, ...series],
        hooks: {
          setCursor: [reportHover],
          ...(extra.drawClear && { drawClear: [extra.drawClear] }),
        },
      },
      data,
      container,
    );
    return { plot, container, height };
  };

  const line = (label: string, color: (p: ReturnType<typeof palette>) => string, width = 1.5) => ({
    label,
    stroke: () => color(palette()),
    width,
  });

  const panesDrawn = [
    pane(
      panes.fitness,
      PMC_PANE_HEIGHT_PX.fitness,
      [line('Fitness (CTL)', (p) => p.fitness, 2), line('Fatigue (ATL)', (p) => p.fatigue)],
      [xs, days.map((day) => day.ctl), days.map((day) => day.atl)],
      { labelX: false },
    ),
    pane(
      panes.form,
      PMC_PANE_HEIGHT_PX.form,
      [line('Form (TSB)', (p) => p.form)],
      [xs, days.map((day) => day.tsb)],
      {
        labelX: false,
        yRange: (_u, min, max) => [
          min == null ? FORM_FLOOR : Math.min(min - FORM_PADDING, FORM_FLOOR),
          max == null ? FORM_CEILING : Math.max(max + FORM_PADDING, FORM_CEILING),
        ],
        drawClear: drawFormZones,
      },
    ),
    pane(
      panes.acwr,
      PMC_PANE_HEIGHT_PX.acwr,
      // A withheld ACWR (the first 34 days) leaves a gap rather than a line to zero.
      [{ ...line('ACWR', (p) => p.acwr), spanGaps: false }],
      [xs, days.map((day) => day.acwr)],
      {
        labelX: true,
        yRange: (_u, min, max) => [
          min == null ? ACWR_FLOOR : Math.min(min - ACWR_PADDING, ACWR_FLOOR),
          max == null ? ACWR_CEILING : Math.max(max + ACWR_PADDING, ACWR_CEILING),
        ],
        drawClear: drawCorridor,
      },
    ),
  ];

  const setRange = (range: PmcRange) => {
    const window = visibleWindow(days, range);
    if (!window) return;
    let min = xs[window.fromIndex]!;
    let max = xs[window.toIndex]!;
    if (min === max) {
      min -= SECONDS_PER_DAY / 2;
      max += SECONDS_PER_DAY / 2;
    }
    for (const { plot } of panesDrawn) plot.setScale('x', { min, max });
  };
  setRange(options.range);

  const resizeObserver =
    typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(() => {
          for (const { plot, container, height } of panesDrawn) {
            plot.setSize({ width: container.clientWidth, height });
          }
        });
  for (const { container } of panesDrawn) resizeObserver?.observe(container);

  return {
    setRange,
    setTheme(next) {
      if (next === theme) return;
      theme = next;
      for (const { plot } of panesDrawn) plot.redraw(false);
    },
    destroy() {
      resizeObserver?.disconnect();
      for (const { plot } of panesDrawn) plot.destroy();
    },
  };
}
