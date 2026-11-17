import { useEffect, useMemo, useRef, useState } from 'react';
import { match } from 'ts-pattern';
import { useColorScheme } from '../../lib/colorScheme';
import { CHART_CAPTION_HEIGHT_PX, CHART_CARD_HEIGHT_PX, CHART_PLOT_HEIGHT_PX } from './chartLayout';
import { loadUplotChart } from './lazyRenderers';
import type { XySeries } from './lttb';
import type { TimeWindow } from './sampleLookup';
import type { TelemetryScrubberBus } from './scrubberBus';
import type { ChartSeries } from './sensorStreams';
import type { TelemetryChartHandle } from './uplotChart';
import { type ChartData, useChartData } from './useChartData';
import { type MountedRenderer, useLazyRenderer } from './useLazyRenderer';

type UplotChartModule = typeof import('./uplotChart');

/** Charts below this many are usually below the fold, under the route map. */
const CHARTS_ABOVE_FOLD = 2;

interface TelemetryChartGridProps {
  /** The elapsed-time stream of the **Activity Details**. */
  readonly t: readonly number[];
  /** The recorded sensor streams to chart, at least one, from `selectChartSeries`. */
  readonly series: readonly ChartSeries[];
  readonly bus: TelemetryScrubberBus;
  /** The selected **Best Effort**'s window, shaded on every chart; `null` when none is selected. */
  readonly highlight: TimeWindow | null;
}

/** The points one chart plots. */
type SeriesPlot =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly points: XySeries };

const plotOf = (chartData: ChartData, index: number): SeriesPlot =>
  match(chartData)
    .returnType<SeriesPlot>()
    .with({ kind: 'loading' }, () => ({ kind: 'loading' }))
    .with({ kind: 'ready' }, ({ points }) => {
      const seriesPoints = points[index];
      return seriesPoints ? { kind: 'ready', points: seriesPoints } : { kind: 'loading' };
    })
    .exhaustive();

/** One synchronized uPlot chart per recorded sensor stream of the **Activity Details**. */
export function TelemetryChartGrid({ t, series, bus, highlight }: TelemetryChartGridProps) {
  const chartData = useChartData(t, series);

  return (
    <ul aria-label="Sensor charts" className="space-y-3">
      {series.map((sensor, index) => (
        <TelemetryChartCard
          key={sensor.id}
          t={t}
          series={sensor}
          plot={plotOf(chartData, index)}
          bus={bus}
          highlight={highlight}
          belowFold={index >= CHARTS_ABOVE_FOLD}
        />
      ))}
    </ul>
  );
}

interface TelemetryChartCardProps {
  readonly t: readonly number[];
  readonly series: ChartSeries;
  /** While a downsampled series is being prepared, the card's height is reserved. */
  readonly plot: SeriesPlot;
  readonly bus: TelemetryScrubberBus;
  readonly highlight: TimeWindow | null;
  readonly belowFold: boolean;
}

function TelemetryChartCard({
  t,
  series,
  plot,
  bus,
  highlight,
  belowFold,
}: TelemetryChartCardProps) {
  const theme = useColorScheme();
  const container = useRef<HTMLDivElement>(null);
  const readout = useRef<HTMLOutputElement>(null);
  // The current theme, read when the chart is created; later changes restyle the live chart.
  const latestTheme = useRef(theme);
  useEffect(() => {
    latestTheme.current = theme;
  });
  // Whether the overlay (Grade-Adjusted Pace) is drawn; the live chart is toggled in place, not rebuilt.
  const [overlayVisible, setOverlayVisible] = useState(true);
  const latestOverlayVisible = useRef(overlayVisible);
  useEffect(() => {
    latestOverlayVisible.current = overlayVisible;
  });
  const title = `${series.label} (${series.unit})`;
  const points = plot.kind === 'ready' ? plot.points : null;
  const samples = useMemo(() => ({ x: t, y: series.values }), [t, series]);
  const overlaySamples = useMemo(
    () => series.overlay && { x: t, y: series.overlay.values },
    [t, series],
  );

  const mount = useMemo(
    () =>
      points &&
      (({
        createTelemetryChart,
      }: UplotChartModule): MountedRenderer<TelemetryChartHandle> | null => {
        if (!container.current || !readout.current) return null;
        const handle = createTelemetryChart({
          container: container.current,
          readout: readout.current,
          series,
          data: points,
          samples,
          theme: latestTheme.current,
          onScrub: (seconds) => bus.scrub(seconds, handle),
          ...(series.overlay &&
            overlaySamples && {
              overlay: {
                label: series.overlay.label,
                color: series.overlay.color,
                samples: overlaySamples,
                visible: latestOverlayVisible.current,
              },
            }),
        });
        const unregister = bus.registerChart(handle);
        return {
          handle,
          unmount() {
            unregister();
            handle.destroy();
          },
        };
      }),
    [series, points, samples, overlaySamples, bus],
  );
  const chart = useLazyRenderer(loadUplotChart, mount);
  const liveChart = chart.kind === 'ready' ? chart.handle : null;

  useEffect(() => {
    liveChart?.setHighlight(highlight);
  }, [liveChart, highlight]);
  useEffect(() => {
    liveChart?.setTheme(theme);
  }, [liveChart, theme]);
  useEffect(() => {
    liveChart?.setOverlayVisible(overlayVisible);
  }, [liveChart, overlayVisible]);

  return (
    <li
      style={{
        height: CHART_CARD_HEIGHT_PX,
        ...(belowFold && { containIntrinsicSize: `auto ${CHART_CARD_HEIGHT_PX}px` }),
      }}
      className={`overflow-hidden rounded-lg ring-1 ring-slate-200 dark:ring-slate-800 bg-white dark:bg-slate-900 shadow-sm ${
        belowFold ? '[content-visibility:auto]' : ''
      }`}
    >
      <figure aria-label={title} className="m-0">
        <figcaption
          style={{ height: CHART_CAPTION_HEIGHT_PX }}
          className="flex items-center justify-between px-3 text-xs"
        >
          <span className="flex items-center gap-2">
            <span className="font-semibold text-slate-700 dark:text-slate-200">{title}</span>
            {series.overlay && (
              <button
                type="button"
                aria-pressed={overlayVisible}
                title="Grade-Adjusted Pace"
                onClick={() => setOverlayVisible((visible) => !visible)}
                className={`rounded px-1.5 py-0.5 font-semibold ring-1 transition-colors ${
                  overlayVisible
                    ? 'bg-fuchsia-50 text-fuchsia-700 ring-fuchsia-300 dark:bg-fuchsia-950 dark:text-fuchsia-300 dark:ring-fuchsia-800'
                    : 'text-slate-500 ring-slate-300 dark:text-slate-400 dark:ring-slate-700'
                }`}
              >
                {series.overlay.label}
              </button>
            )}
          </span>
          <output
            aria-label={`${series.label} reading`}
            ref={readout}
            // A fixed-width box: the reading changes on every scrub and must not shift the layout.
            // An overlay's reading is read out beside the series', so its box is wider.
            className={`inline-block ${
              series.overlay ? 'w-80' : 'w-56'
            } shrink-0 whitespace-nowrap text-right tabular-nums text-slate-500 dark:text-slate-400`}
          >
            —
          </output>
        </figcaption>
        <div ref={container} style={{ height: CHART_PLOT_HEIGHT_PX }} className="relative w-full">
          {match(chart)
            .with({ kind: 'failed' }, () => (
              <p className="absolute inset-0 flex items-center justify-center text-sm text-slate-500 dark:text-slate-400">
                This chart could not be displayed.
              </p>
            ))
            .with({ kind: 'loading' }, { kind: 'ready' }, () => null)
            .exhaustive()}
        </div>
      </figure>
    </li>
  );
}
