import { useEffect, useMemo, useState } from 'react';
import { createDownsamplerClient } from './downsamplerClient';
import { LTTB_TARGET_POINTS, type XySeries, needsDownsampling } from './lttb';
import type { ChartSeries } from './sensorStreams';

/** The points to plot for each charted series, in order. */
export type ChartData =
  /** An ultra-long Activity's series are still being downsampled. */
  { readonly kind: 'loading' } | { readonly kind: 'ready'; readonly points: readonly XySeries[] };

const LOADING: ChartData = { kind: 'loading' };

/**
 * The points to plot for each of `series` against elapsed time `t`: the full-resolution streams, or
 * for ultra-long Activities their LTTB downsampling from a Web Worker. If the worker fails, the full
 * streams are plotted instead (uPlot can still draw them, with more overdraw).
 */
export function useChartData(t: readonly number[], series: readonly ChartSeries[]): ChartData {
  const downsample = needsDownsampling(t.length);
  const fullResolution = useMemo<ChartData>(
    () => ({ kind: 'ready', points: series.map((sensor) => ({ x: t, y: sensor.values })) }),
    [t, series],
  );
  const [decimated, setDecimated] = useState<{
    readonly source: readonly ChartSeries[];
    readonly data: ChartData;
  } | null>(null);

  useEffect(() => {
    if (!downsample) return;
    const client = createDownsamplerClient();
    let cancelled = false;
    const settle = (data: ChartData) => {
      if (!cancelled) setDecimated({ source: series, data });
    };
    client
      .downsample(
        t,
        series.map((sensor) => sensor.values),
        LTTB_TARGET_POINTS,
      )
      .then(
        (points) => settle({ kind: 'ready', points }),
        () => settle(fullResolution),
      );
    return () => {
      cancelled = true;
      client.dispose();
    };
  }, [downsample, t, series, fullResolution]);

  if (!downsample) return fullResolution;
  return decimated?.source === series ? decimated.data : LOADING;
}
