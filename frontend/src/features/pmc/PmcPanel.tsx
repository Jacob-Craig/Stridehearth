import { Link } from '@tanstack/react-router';
import { type RefObject, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { PmcResponseDto } from '../../api/generated/types.gen';
import { useColorScheme } from '../../lib/colorScheme';
import { loadPmcChart } from '../charts/lazyCharts';
import { type MountedRenderer, useLazyRenderer } from '../telemetry/useLazyRenderer';
import { ACWR_CORRIDOR, FORM_ZONES } from './formZones';
import type { PmcChartHandle } from './pmcChart';
import { PMC_PALETTE, PMC_PANE_HEIGHT_PX } from './pmcPalette';
import { DEFAULT_PMC_RANGE, PMC_RANGES, type PmcRange } from './pmcRange';
import { formatPmcReadout } from './pmcReadout';

type PmcChartModule = typeof import('./pmcChart');

const HEADING_ID = 'pmc-heading';
const CHART_FAILED = 'This chart could not be displayed.';

/** The PMC query as the panel sees it: a failure stays inside the panel instead of failing the Dashboard. */
export interface PmcQueryState {
  readonly data: PmcResponseDto | undefined;
  readonly isError: boolean;
}

interface PmcPanelProps {
  readonly pmc: PmcQueryState;
}

/**
 * The athlete's **Performance Management Chart** on the Dashboard. The whole series is loaded once; the
 * range picker only changes what the chart shows, so it never refetches.
 */
export function PmcPanel({ pmc: { data: pmc, isError } }: PmcPanelProps) {
  const [range, setRange] = useState<PmcRange>(DEFAULT_PMC_RANGE);
  // The range the chart is created with; later picks move the live chart in place.
  const latestRange = useRef(range);
  const chart = useRef<PmcChartHandle | null>(null);
  const empty = pmc?.days.length === 0;

  const pick = (next: PmcRange) => {
    latestRange.current = next;
    setRange(next);
    chart.current?.setRange(next);
  };

  return (
    <section
      aria-labelledby={HEADING_ID}
      className="rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-5 shadow-sm"
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 id={HEADING_ID} className="text-lg font-bold">
          Performance Management Chart
        </h2>
        {pmc && !empty && (
          <fieldset className="flex gap-1 text-sm font-medium">
            <legend className="sr-only">Range</legend>
            {PMC_RANGES.map((option) => (
              <button
                key={option.id}
                type="button"
                aria-pressed={range === option.id}
                onClick={() => pick(option.id)}
                className={`rounded-md px-3 py-1 ring-1 transition-colors ${
                  range === option.id
                    ? 'bg-blue-600 text-white ring-blue-600'
                    : 'text-slate-600 ring-slate-300 hover:bg-slate-100 dark:text-slate-300 dark:ring-slate-700 dark:hover:bg-slate-800'
                }`}
              >
                {option.label}
              </button>
            ))}
          </fieldset>
        )}
      </div>
      {pmc === undefined ? (
        // No data and no error yet: the first request is pending, or React Query is retrying a failure.
        <p className="text-sm text-slate-500 dark:text-slate-400">
          {isError ? CHART_FAILED : 'Loading…'}
        </p>
      ) : empty ? (
        <EmptyPmc />
      ) : (
        <PmcChart pmc={pmc} latestRange={latestRange} liveChartRef={chart} />
      )}
    </section>
  );
}

function EmptyPmc() {
  return (
    <div className="py-6 text-center text-slate-500 dark:text-slate-400">
      <p className="text-lg font-semibold text-slate-600 dark:text-slate-300">
        No Training Load yet
      </p>
      <p className="mt-1 text-sm">
        Activities get a Training Load once they have Activity Details and your Thresholds are set.
      </p>
      <Link
        to="/settings"
        className="mt-3 inline-block text-sm font-semibold text-blue-600 dark:text-blue-400 hover:underline"
      >
        Set your Thresholds in Settings
      </Link>
    </div>
  );
}

interface PmcChartProps {
  readonly pmc: PmcResponseDto;
  readonly latestRange: { readonly current: PmcRange };
  /** Set to the chart once it is drawn, so a range pick can move it in place. */
  readonly liveChartRef: { current: PmcChartHandle | null };
}

function PmcChart({ pmc, latestRange, liveChartRef }: PmcChartProps) {
  const theme = useColorScheme();
  const latestTheme = useRef(theme);
  useEffect(() => {
    latestTheme.current = theme;
  });
  const fitness = useRef<HTMLDivElement>(null);
  const form = useRef<HTMLDivElement>(null);
  const acwr = useRef<HTMLDivElement>(null);
  const readout = useRef<HTMLOutputElement>(null);
  const { days } = pmc;
  // The latest day's reading, shown whenever no day is hovered, so the values never need a pointer.
  const latestReading = useRef('');
  useLayoutEffect(() => {
    const last = days.at(-1);
    latestReading.current = last ? formatPmcReadout(last) : '';
    // Written to the DOM, not rendered, because hovering rewrites the same node directly.
    if (readout.current) readout.current.textContent = latestReading.current;
  }, [days]);

  const mount = useMemo(
    () =>
      ({ createPmcChart }: PmcChartModule): MountedRenderer<PmcChartHandle> | null => {
        if (!fitness.current || !form.current || !acwr.current) return null;
        const handle = createPmcChart({
          panes: { fitness: fitness.current, form: form.current, acwr: acwr.current },
          days,
          theme: latestTheme.current,
          range: latestRange.current,
          // Written straight to the DOM: a React render per pointer move would be wasted work.
          onHover: (dayIndex) => {
            const day = dayIndex == null ? undefined : days[dayIndex];
            if (readout.current) {
              readout.current.textContent = day ? formatPmcReadout(day) : latestReading.current;
            }
          },
        });
        // Published at once, not after the next render, so a range picked in between still moves it.
        liveChartRef.current = handle;
        return {
          handle,
          unmount: () => {
            if (liveChartRef.current === handle) liveChartRef.current = null;
            handle.destroy();
            // The day under the cursor belonged to this chart's series, which a redraw may replace.
            if (readout.current) readout.current.textContent = latestReading.current;
          },
        };
      },
    [days, latestRange, liveChartRef],
  );
  const chart = useLazyRenderer(loadPmcChart, mount);
  const liveChart = chart.kind === 'ready' ? chart.handle : null;
  useEffect(() => {
    liveChart?.setTheme(theme);
  }, [liveChart, theme]);

  const unscored = days.reduce((sum, day) => sum + day.unscoredActivityCount, 0);
  const palette = PMC_PALETTE[theme];

  return (
    <div className="space-y-2">
      <output
        aria-label="Latest day, or the day under the cursor"
        // Rewritten on every pointer move, so it is read on demand rather than announced.
        aria-live="off"
        ref={readout}
        // Reserves the lines a full day's reading wraps to (four on a phone, two from the sm breakpoint), so
        // hovering never shifts the chart under the pointer.
        className="block min-h-20 sm:min-h-10 text-sm tabular-nums text-slate-600 dark:text-slate-300"
      />
      {chart.kind === 'failed' && (
        <p className="text-sm text-slate-500 dark:text-slate-400">{CHART_FAILED}</p>
      )}
      <Pane
        paneRef={fitness}
        height={PMC_PANE_HEIGHT_PX.fitness}
        keys={[
          { label: 'Fitness (CTL)', color: palette.fitness },
          { label: 'Fatigue (ATL)', color: palette.fatigue },
        ]}
      />
      <Pane
        paneRef={form}
        height={PMC_PANE_HEIGHT_PX.form}
        keys={[
          { label: 'Form (TSB)', color: palette.form },
          ...FORM_ZONES.map((zone) => ({
            label: zone.label,
            color: palette.zones[zone.id],
            band: true,
          })),
        ]}
      />
      <Pane
        paneRef={acwr}
        height={PMC_PANE_HEIGHT_PX.acwr}
        keys={[
          { label: 'ACWR', color: palette.acwr },
          {
            label: `Corridor ${ACWR_CORRIDOR.min.toFixed(2)}–${ACWR_CORRIDOR.max.toFixed(2)}`,
            color: palette.corridor,
            band: true,
          },
        ]}
      />
      {unscored > 0 && (
        <p className="text-xs text-slate-500 dark:text-slate-400">
          {unscored === 1
            ? '1 Activity has no Training Load and counts as 0.'
            : `${unscored} Activities have no Training Load and count as 0.`}
        </p>
      )}
    </div>
  );
}

interface PaneKey {
  readonly label: string;
  readonly color: string;
  /** A shaded band rather than a line. */
  readonly band?: boolean;
}

function Pane({
  paneRef,
  height,
  keys,
}: {
  paneRef: RefObject<HTMLDivElement | null>;
  height: number;
  keys: readonly PaneKey[];
}) {
  return (
    <figure className="m-0">
      <figcaption className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-slate-600 dark:text-slate-300">
        {keys.map((key) => (
          <span key={key.label} className="inline-flex items-center gap-1">
            <span
              aria-hidden="true"
              className={
                key.band
                  ? 'inline-block h-2.5 w-2.5 rounded-sm opacity-50'
                  : 'inline-block h-0.5 w-3'
              }
              style={{ backgroundColor: key.color }}
            />
            {key.label}
          </span>
        ))}
      </figcaption>
      <div ref={paneRef} style={{ height }} className="relative w-full" />
    </figure>
  );
}
