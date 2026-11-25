/**
 * Thin adapter over `uPlot`: one canvas time-series chart of a sensor stream, driven imperatively by
 * the scrubber bus. Only this module touches the uPlot API.
 *
 * Callback parameters named `u` are uPlot instances, following uPlot's own documentation and types.
 */
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import type { ColorScheme } from '../../lib/colorScheme';
import { formatDuration } from '../../lib/format';
import { AXIS_PALETTE } from '../charts/chartTheme';
import { BEST_EFFORT_HIGHLIGHT_COLOR } from './bestEffortHighlight';
import { CHART_PLOT_HEIGHT_PX } from './chartLayout';
import type { XySeries } from './lttb';
import { type TimeWindow, sampleIndexAtTime } from './sampleLookup';
import type { ScrubbableChart } from './scrubberBus';
import { readingAt, sensorDropouts } from './sensorDropouts';
import type { ChartOverlay, ChartSeries } from './sensorStreams';

/** The **Best Effort** band is the highlight colour, translucent over the series. */
const HIGHLIGHT_BAND_ALPHA = 0.22;

const HIDDEN_CURSOR = { left: -10, top: -10 };
/** The overlay is dashed so it stays distinguishable from the chart's own series without colour. */
const OVERLAY_DASH = [6, 4];
const NO_READING = '—';

export interface TelemetryChartOptions {
  readonly container: HTMLElement;
  /** The live reading under the cursor is written here directly, without a React render. */
  readonly readout: HTMLElement;
  readonly series: Pick<ChartSeries, 'label' | 'unit' | 'color' | 'yRange'>;
  /** `(elapsed seconds, value)` points to plot, possibly downsampled. */
  readonly data: XySeries;
  /** The full-resolution `(elapsed seconds, value)` samples, read out at the scrubbed time. */
  readonly samples: XySeries;
  readonly theme: ColorScheme;
  /** Called with the elapsed time under the pointer, or `null` when it leaves the chart. */
  readonly onScrub: (seconds: number | null) => void;
  /** A derived stream drawn over the series in the same unit, such as **Grade-Adjusted Pace**. */
  readonly overlay?: {
    readonly label: ChartOverlay['label'];
    readonly color: ChartOverlay['color'];
    /** Its full-resolution `(elapsed seconds, value)` samples; plotted at the points `data` plots. */
    readonly samples: XySeries;
    /** Whether it is drawn and read out when the chart is created. */
    readonly visible: boolean;
  };
}

export interface TelemetryChartHandle extends ScrubbableChart {
  /** Shades a **Best Effort** window, or clears the shading with `null`. */
  setHighlight(window: TimeWindow | null): void;
  /** Restyles the live chart for a colour scheme, keeping its cursor and reading. */
  setTheme(theme: ColorScheme): void;
  /** Shows or hides the overlay and its reading in place; does nothing on a chart without one. */
  setOverlayVisible(visible: boolean): void;
  destroy(): void;
}

const formatValue = (value: number) => (Number.isInteger(value) ? String(value) : value.toFixed(1));

/**
 * The overlay's values at each plotted point of the chart's own series. Both are parallel to the same
 * elapsed-time stream, so at full resolution they line up as they are; a downsampled series plots a
 * subset of sample times, which are looked up in the overlay's samples.
 */
function overlayAtPlottedPoints(data: XySeries, samples: XySeries, overlay: XySeries): XySeries {
  if (data === samples || data.x === overlay.x) return { x: data.x, y: overlay.y };
  return {
    x: data.x,
    y: data.x.map((seconds) => {
      const sample = sampleIndexAtTime(overlay.x, seconds);
      return sample == null ? null : (overlay.y[sample] ?? null);
    }),
  };
}

export function createTelemetryChart(options: TelemetryChartOptions): TelemetryChartHandle {
  const { container, readout, series, data, samples, onScrub, overlay } = options;
  let theme = options.theme;
  let highlight: TimeWindow | null = null;
  let overlayVisible = overlay?.visible ?? false;
  let readingSeconds: number | null = null;
  // The plotted line breaks at the plotted series' dropouts; the reading follows the full samples.
  const dropouts = sensorDropouts(data);
  const sampleDropouts = data === samples ? dropouts : sensorDropouts(samples);
  const overlayPlot = overlay && overlayAtPlottedPoints(data, samples, overlay.samples);
  const overlayDropouts = overlayPlot ? sensorDropouts(overlayPlot) : [];
  const overlaySampleDropouts = overlay ? sensorDropouts(overlay.samples) : [];

  const formatReading = (value: number | null) =>
    value == null ? NO_READING : `${formatValue(value)} ${series.unit}`;

  /** Reads out the full-resolution sample nearest `seconds`, whatever points the chart plots. */
  const showReadingAt = (seconds: number | null) => {
    readingSeconds = seconds;
    const sample = seconds == null ? null : sampleIndexAtTime(samples.x, seconds);
    const sampleSeconds = sample == null ? undefined : samples.x[sample];
    if (sample == null || sampleSeconds === undefined) {
      readout.textContent = NO_READING;
      return;
    }
    const reading = formatReading(readingAt(samples, sampleSeconds, sampleDropouts));
    const overlayReading =
      overlay && overlayVisible
        ? ` · ${overlay.label} ${formatReading(readingAt(overlay.samples, sampleSeconds, overlaySampleDropouts))}`
        : '';
    readout.textContent = `${formatDuration(sampleSeconds)} · ${reading}${overlayReading}`;
  };

  /** uPlot `gaps`: only true dropouts break a line, not every null between sparse readings. */
  const gapsAt =
    (windows: readonly TimeWindow[]) =>
    (u: uPlot): [number, number][] =>
      windows.map(({ startTimeSeconds, endTimeSeconds }) => [
        u.valToPos(startTimeSeconds, 'x', true),
        u.valToPos(endTimeSeconds, 'x', true),
      ]);

  /**
   * The elapsed time under the pointer itself, not the nearest plotted point (`cursor.idx`), which on
   * a downsampled chart can be several seconds away.
   */
  const pointerSeconds = (u: uPlot): number | null => {
    const left = u.cursor.left;
    return left == null || left < 0 ? null : u.posToVal(left, 'x');
  };

  const drawHighlight = (u: uPlot) => {
    if (!highlight) return;
    const x0 = u.valToPos(highlight.startTimeSeconds, 'x', true);
    const x1 = u.valToPos(highlight.endTimeSeconds, 'x', true);
    const left = Math.max(u.bbox.left, Math.min(x0, x1));
    const right = Math.min(u.bbox.left + u.bbox.width, Math.max(x0, x1));
    if (right <= left) return;
    u.ctx.save();
    u.ctx.globalAlpha = HIGHLIGHT_BAND_ALPHA;
    u.ctx.fillStyle = BEST_EFFORT_HIGHLIGHT_COLOR[theme];
    u.ctx.fillRect(left, u.bbox.top, right - left, u.bbox.height);
    u.ctx.restore();
  };

  // Strokes are read from `theme` on every draw, so a colour-scheme change is a redraw, not a rebuild.
  const axis = {
    stroke: () => AXIS_PALETTE[theme].axis,
    grid: { stroke: () => AXIS_PALETTE[theme].grid, width: 1 },
    ticks: { show: false },
  };
  const plot = new uPlot(
    {
      width: container.clientWidth,
      height: CHART_PLOT_HEIGHT_PX,
      legend: { show: false },
      cursor: { drag: { x: false, y: false }, y: false },
      // A fixed range (Stamina's 0-100 %) keeps the axis comparable between Activities; others auto-range.
      scales: {
        x: { time: false },
        ...(series.yRange && { y: { range: [series.yRange[0], series.yRange[1]] } }),
      },
      axes: [
        { ...axis, values: (_u, ticks) => ticks.map((tick) => formatDuration(tick)) },
        { ...axis, size: 50 },
      ],
      series: [
        {},
        {
          label: series.label,
          stroke: () => series.color[theme],
          width: 1.5,
          // uPlot treats every null as a gap; only true sensor dropouts break the line, so sparse
          // readings stay a line and a downsampled series (which has no nulls) still shows dropouts.
          spanGaps: false,
          gaps: gapsAt(dropouts),
        },
        ...(overlay
          ? [
              {
                label: overlay.label,
                stroke: () => overlay.color[theme],
                width: 1.5,
                dash: OVERLAY_DASH,
                show: overlayVisible,
                spanGaps: false,
                gaps: gapsAt(overlayDropouts),
              },
            ]
          : []),
      ],
      hooks: {
        setCursor: [
          (u) => {
            const seconds = pointerSeconds(u);
            showReadingAt(seconds);
            onScrub(seconds);
          },
        ],
        draw: [drawHighlight],
      },
    },
    // uPlot does not mutate its data; it is typed as mutable only.
    overlayPlot
      ? [data.x as number[], data.y as (number | null)[], overlayPlot.y as (number | null)[]]
      : [data.x as number[], data.y as (number | null)[]],
    container,
  );

  const resizeObserver =
    typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(() =>
          plot.setSize({ width: container.clientWidth, height: CHART_PLOT_HEIGHT_PX }),
        );
  resizeObserver?.observe(container);

  return {
    setCursorTime(seconds) {
      // fireHook = false: a scrub from the bus must not be published back to it.
      plot.setCursor(
        seconds == null ? HIDDEN_CURSOR : { left: plot.valToPos(seconds, 'x'), top: -1 },
        false,
      );
      showReadingAt(seconds);
    },
    setHighlight(window) {
      highlight = window;
      plot.redraw(false);
    },
    setTheme(next) {
      if (next === theme) return;
      theme = next;
      plot.redraw(false);
    },
    setOverlayVisible(visible) {
      if (!overlay) return;
      overlayVisible = visible;
      plot.setSeries(2, { show: visible });
      showReadingAt(readingSeconds);
    },
    destroy() {
      resizeObserver?.disconnect();
      plot.destroy();
    },
  };
}
