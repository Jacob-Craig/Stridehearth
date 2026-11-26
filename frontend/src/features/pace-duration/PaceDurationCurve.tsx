import { Link, useNavigate } from '@tanstack/react-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { match } from 'ts-pattern';
import type {
  CriticalSpeedResponseDto,
  PeakEffortDto,
  PeakEffortsResponseDto,
} from '../../api/generated/types.gen';
import { ErrorAlert } from '../../components/ErrorAlert';
import { useColorScheme } from '../../lib/colorScheme';
import { MISSING } from '../../lib/format';
import { parseApiError } from '../../utils/error';
import { loadPaceDurationChart } from '../charts/lazyCharts';
import { type MountedRenderer, useLazyRenderer } from '../telemetry/useLazyRenderer';
import type { PaceDurationChartHandle } from './paceDurationChart';
import {
  alignedPdcData,
  durationTickLabel,
  formatModelLegend,
  formatPdcValue,
  modelCurve,
  PDC_PALETTE,
  PDC_PLOT_HEIGHT_PX,
  type PdcMode,
  type PeakCurves,
  peakCurves,
} from './paceDurationSeries';

type PaceDurationChartModule = typeof import('./paceDurationChart');

const HEADING_ID = 'pdc-heading';

/** A query as the card sees it: each loads and fails on its own, apart from the Dashboard. */
export type QueryState<T> =
  | { status: 'pending' }
  | { status: 'error'; error: unknown }
  | { status: 'success'; data: T };

interface PaceDurationCurveProps {
  readonly peakEfforts: QueryState<PeakEffortsResponseDto>;
  readonly criticalSpeed: QueryState<CriticalSpeedResponseDto>;
}

const MODES: ReadonlyArray<{ id: PdcMode; label: string }> = [
  { id: 'pace', label: 'Pace' },
  { id: 'power', label: 'Power' },
];

/**
 * The athlete's **Pace-Duration Curve**: their best Peak Efforts per Benchmark Duration over 90 days and all
 * time, on a log duration axis, with their Critical Speed (or Critical Power) model.
 */
export function PaceDurationCurve({ peakEfforts, criticalSpeed }: PaceDurationCurveProps) {
  const [chosenMode, setMode] = useState<PdcMode>('pace');
  const peaks = peakEfforts.status === 'success' ? peakEfforts.data : null;
  const hasPower = (peaks?.power.length ?? 0) > 0;
  const mode = hasPower ? chosenMode : 'pace';

  return (
    <section
      aria-labelledby={HEADING_ID}
      className="rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-5 shadow-sm"
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 id={HEADING_ID} className="text-lg font-bold">
          Pace-Duration Curve
        </h2>
        {hasPower && (
          <fieldset className="flex gap-1 text-sm font-medium">
            <legend className="sr-only">Metric</legend>
            {MODES.map((option) => (
              <button
                key={option.id}
                type="button"
                aria-pressed={mode === option.id}
                onClick={() => setMode(option.id)}
                className={`rounded-md px-3 py-1 ring-1 transition-colors ${
                  mode === option.id
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
      {match(peakEfforts)
        .with({ status: 'pending' }, () => (
          <p className="py-2 text-slate-500 dark:text-slate-400">Loading…</p>
        ))
        .with({ status: 'error' }, ({ error }) => (
          <ErrorAlert>
            Your Pace-Duration Curve could not be loaded. {parseApiError(error).generalError}
          </ErrorAlert>
        ))
        .with({ status: 'success' }, ({ data }) => (
          <PaceDurationBody peaks={data} criticalSpeed={criticalSpeed} mode={mode} />
        ))
        .exhaustive()}
    </section>
  );
}

function PaceDurationBody({
  peaks,
  criticalSpeed,
  mode,
}: {
  peaks: PeakEffortsResponseDto;
  criticalSpeed: QueryState<CriticalSpeedResponseDto>;
  mode: PdcMode;
}) {
  const theme = useColorScheme();
  const slots = mode === 'pace' ? peaks.pace : peaks.power;
  const curves = useMemo(() => peakCurves(slots), [slots]);
  const fit =
    criticalSpeed.status === 'success'
      ? mode === 'pace'
        ? criticalSpeed.data.pace
        : criticalSpeed.data.power
      : null;
  const model = useMemo(() => modelCurve(fit), [fit]);

  if (curves.allTime.length === 0) {
    return (
      <div className="py-2 text-slate-500 dark:text-slate-400">
        <p className="text-lg">Not enough data yet</p>
        <p className="mt-1 text-sm">
          Import runs with telemetry to see your best efforts from 5 seconds to 60 minutes.
        </p>
      </div>
    );
  }

  const windowLabel = `${peaks.windowDays} days`;
  const legend = formatModelLegend(mode, fit);
  const modelName = mode === 'pace' ? 'Critical Speed' : 'Critical Power';
  const swatch = PDC_PALETTE[theme];
  return (
    <>
      <ul
        aria-label="Legend"
        className="mb-2 flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate-600 dark:text-slate-300"
      >
        <LegendItem color={swatch.recent} label={windowLabel} />
        <LegendItem color={swatch.allTime} label="All-Time" />
        {legend && <LegendItem color={swatch.model} label={legend} dashed />}
      </ul>
      {!legend && criticalSpeed.status !== 'pending' && (
        <p className="mb-2 text-sm text-slate-500 dark:text-slate-400">
          {criticalSpeed.status === 'error'
            ? `Your ${modelName} model could not be loaded.`
            : `No ${modelName} model yet: it needs 3 ${
                mode === 'power' ? 'power ' : ''
              }Peak Efforts between 2 and 20 min in the last ${peaks.windowDays} days.`}
        </p>
      )}
      <PaceDurationFigure curves={curves} model={model} mode={mode} windowLabel={windowLabel} />
    </>
  );
}

function LegendItem({ color, label, dashed }: { color: string; label: string; dashed?: boolean }) {
  return (
    <li className="flex items-center gap-2">
      <span
        aria-hidden="true"
        className="inline-block w-4 border-t-2"
        style={{ borderColor: color, borderStyle: dashed ? 'dashed' : 'solid' }}
      />
      <span className="tabular-nums">{label}</span>
    </li>
  );
}

function PaceDurationFigure({
  curves,
  model,
  mode,
  windowLabel,
}: {
  curves: PeakCurves;
  model: ReturnType<typeof modelCurve>;
  mode: PdcMode;
  windowLabel: string;
}) {
  const theme = useColorScheme();
  const navigate = useNavigate();
  const container = useRef<HTMLDivElement>(null);
  // The current theme and navigate, read when the chart is created or clicked; later theme changes restyle it.
  const latest = useRef({ theme, navigate });
  useEffect(() => {
    latest.current = { theme, navigate };
  });
  const data = useMemo(() => alignedPdcData(curves, model), [curves, model]);

  const mount = useMemo(
    () =>
      ({
        createPaceDurationChart,
      }: PaceDurationChartModule): MountedRenderer<PaceDurationChartHandle> | null => {
        if (!container.current) return null;
        const handle = createPaceDurationChart({
          container: container.current,
          data,
          curves,
          mode,
          theme: latest.current.theme,
          onSelectActivity: (activityId) =>
            void latest.current.navigate({
              to: '/activities/$activityId',
              params: { activityId: String(activityId) },
            }),
        });
        return { handle, unmount: () => handle.destroy() };
      },
    [data, curves, mode],
  );
  const chart = useLazyRenderer(loadPaceDurationChart, mount);
  const liveChart = chart.kind === 'ready' ? chart.handle : null;
  useEffect(() => {
    liveChart?.setTheme(theme);
  }, [liveChart, theme]);

  const durations = [
    ...new Set([...curves.recent, ...curves.allTime].map((p) => p.durationSeconds)),
  ].sort((a, b) => a - b);
  const at = (points: PeakCurves['recent'], seconds: number) =>
    points.find((p) => p.durationSeconds === seconds) ?? null;

  return (
    <figure aria-label="Pace-Duration Curve chart" className="m-0">
      <div ref={container} style={{ height: PDC_PLOT_HEIGHT_PX }} className="relative w-full">
        {match(chart)
          .with({ kind: 'failed' }, () => (
            <p className="absolute inset-0 flex items-center justify-center text-sm text-slate-500 dark:text-slate-400">
              This chart could not be displayed.
            </p>
          ))
          .with({ kind: 'loading' }, { kind: 'ready' }, () => null)
          .exhaustive()}
      </div>
      {/* A table ignores sr-only's 1 px width and widens the page on phones, so a div hides it. */}
      <div className="sr-only">
        <table>
          <caption>Pace-Duration Curve peaks</caption>
          <thead>
            <tr>
              <th scope="col">Duration</th>
              <th scope="col">{windowLabel}</th>
              <th scope="col">All-Time</th>
            </tr>
          </thead>
          <tbody>
            {durations.map((seconds) => (
              <tr key={seconds}>
                <th scope="row">{durationTickLabel(seconds)}</th>
                <PeakCell mode={mode} peak={at(curves.recent, seconds)} />
                <PeakCell mode={mode} peak={at(curves.allTime, seconds)} />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </figure>
  );
}

function PeakCell({ mode, peak }: { mode: PdcMode; peak: PeakEffortDto | null }) {
  if (!peak) return <td>{MISSING}</td>;
  return (
    <td>
      {formatPdcValue(mode, peak.value)}{' '}
      <Link to="/activities/$activityId" params={{ activityId: String(peak.activityId) }}>
        {peak.activityTitle}
      </Link>
    </td>
  );
}
