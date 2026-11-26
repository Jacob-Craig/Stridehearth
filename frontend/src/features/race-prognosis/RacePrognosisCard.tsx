import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { type FormEvent, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { RacePrognosisCurrentDto, RacePrognosisDayDto } from '../../api/generated/types.gen';
import { type RaceConditions, racePrognosisQueryOptions } from '../../api/racePrognosis';
import { ErrorAlert } from '../../components/ErrorAlert';
import { useColorScheme } from '../../lib/colorScheme';
import { formatDate } from '../../lib/format';
import { parseApiError } from '../../utils/error';
import { loadPrognosisChart } from '../charts/lazyCharts';
import { type MountedRenderer, useLazyRenderer } from '../telemetry/useLazyRenderer';
import type { PrognosisChartHandle } from './prognosisChart';
import {
  alignedPrognosisData,
  type ConditionsForm,
  formatFinishTime,
  formatPaceSeconds,
  PROGNOSIS_PALETTE,
  PROGNOSIS_PLOT_HEIGHT_PX,
  parseConditions,
  RACE_DISTANCES,
} from './prognosisSeries';

type PrognosisChartModule = typeof import('./prognosisChart');

const HEADING_ID = 'race-prognosis-heading';
const BLANK_FORM: ConditionsForm = { temperatureC: '', elevationGainM: '' };

interface RacePrognosisCardProps {
  readonly userId: number | undefined;
}

/**
 * The athlete's **Race Prognosis** on the Dashboard: today's predicted 5K to Marathon finish times, from
 * their Effective VO2max and Marathon Shape, with optional race-day **Course Derating**, and the daily trend.
 * Each set of race-day conditions is its own query; a failure stays inside the card.
 */
export function RacePrognosisCard({ userId }: RacePrognosisCardProps) {
  const [conditions, setConditions] = useState<RaceConditions>({});
  const prognosis = useQuery({
    ...racePrognosisQueryOptions(userId, conditions),
    // The Dashboard loader has just fetched today's prognosis, so mounting does not fetch it again.
    refetchOnMount: false,
    // The last times stay on screen while derated ones load.
    placeholderData: keepPreviousData,
  });
  const hasConditions = Object.keys(conditions).length > 0;

  return (
    <section
      aria-labelledby={HEADING_ID}
      className="rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-5 shadow-sm"
    >
      <h2 id={HEADING_ID} className="mb-3 text-lg font-bold">
        Race Prognosis
      </h2>
      {prognosis.status === 'pending' ? (
        <p className="py-2 text-slate-500 dark:text-slate-400">Loading…</p>
      ) : prognosis.status === 'error' ? (
        <ErrorAlert>
          Your Race Prognosis could not be loaded. {parseApiError(prognosis.error).generalError}
        </ErrorAlert>
      ) : prognosis.data.current !== null ? (
        <Predictions current={prognosis.data.current} />
      ) : prognosis.data.days.length > 0 ? (
        <NoRecentRuns lastDay={prognosis.data.days.at(-1)!.day} />
      ) : (
        <EmptyPrognosis />
      )}
      {/* One form outside the status switch, so typed conditions survive a failed or empty derated query. */}
      {(hasConditions || prognosis.data?.current != null) && (
        <ConditionsDisclosure onApply={setConditions} />
      )}
      {prognosis.status === 'success' && prognosis.data.days.length > 0 && (
        <PrognosisTrend days={prognosis.data.days} today={prognosis.data.current?.day ?? null} />
      )}
    </section>
  );
}

/** After a break: no prognosis today, but the trend up to the last qualifying day still shows. */
function NoRecentRuns({ lastDay }: { lastDay: string }) {
  return (
    <p className="py-2 text-sm text-slate-500 dark:text-slate-400">
      No recent qualifying runs: your last prognosis was on {formatDate(lastDay)}. Record a steady
      run with heart rate, with your max and resting heart rate set in{' '}
      <Link
        to="/settings"
        className="font-semibold text-blue-600 dark:text-blue-400 hover:underline"
      >
        Settings
      </Link>
      , to update it.
    </p>
  );
}

function EmptyPrognosis() {
  return (
    <div className="py-2 text-slate-500 dark:text-slate-400">
      <p className="text-lg">No prognosis yet</p>
      <p className="mt-1 text-sm">
        Record steady runs with heart rate and set your max and resting heart rate in{' '}
        <Link
          to="/settings"
          className="font-semibold text-blue-600 dark:text-blue-400 hover:underline"
        >
          Settings
        </Link>{' '}
        to get a prognosis.
      </p>
    </div>
  );
}

function Predictions({ current }: { current: RacePrognosisCurrentDto }) {
  return (
    <>
      <dl className="mb-3 flex flex-wrap gap-x-8 gap-y-2 text-sm">
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Effective VO2max</dt>
          <dd className="tabular-nums text-xl font-bold">{current.effectiveVo2max.toFixed(1)}</dd>
        </div>
        <div>
          <dt className="text-slate-500 dark:text-slate-400">Marathon Shape</dt>
          <dd className="tabular-nums text-xl font-bold">
            {Math.round(current.marathonShapePct)}%
          </dd>
        </div>
      </dl>
      <ul aria-label="Predicted finish times" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {current.predictions.map((prediction) => {
          const label =
            RACE_DISTANCES.find((d) => d.distance === prediction.distance)?.label ??
            prediction.distance;
          const derating = prediction.courseFactor - 1;
          return (
            <li
              key={prediction.distance}
              className="rounded-md border border-slate-200 dark:border-slate-800 p-3"
            >
              <h3 className="text-sm font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">
                {label}
              </h3>
              <p
                data-testid="finish-time"
                className="tabular-nums text-2xl font-bold text-blue-600 dark:text-blue-400"
              >
                {formatFinishTime(prediction.predictedSeconds)}
              </p>
              <p className="tabular-nums text-sm font-semibold text-slate-600 dark:text-slate-300">
                {formatPaceSeconds(prediction.predictedSeconds / (prediction.meters / 1000))}
              </p>
              {derating > 0.0005 && (
                <p className="tabular-nums text-xs text-amber-700 dark:text-amber-400">
                  +{(derating * 100).toFixed(1)}% for conditions
                </p>
              )}
            </li>
          );
        })}
      </ul>
    </>
  );
}

const INPUT_CLASS =
  'mt-1 block w-32 rounded-md border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-950 px-2 py-1 text-sm tabular-nums aria-invalid:border-red-500';

function ConditionsDisclosure({ onApply }: { onApply: (conditions: RaceConditions) => void }) {
  const [form, setForm] = useState<ConditionsForm>(BLANK_FORM);
  const [errors, setErrors] = useState<Partial<Record<keyof ConditionsForm, string>>>({});

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseConditions(form);
    if (parsed.ok) {
      setErrors({});
      onApply(parsed.conditions);
    } else {
      setErrors(parsed.errors);
    }
  };
  const clear = () => {
    setForm(BLANK_FORM);
    setErrors({});
    onApply({});
  };

  const field = (name: keyof ConditionsForm, label: string) => {
    const id = `race-prognosis-${name}`;
    const error = errors[name];
    return (
      <div>
        <label htmlFor={id} className="text-slate-600 dark:text-slate-300">
          {label}
        </label>
        <input
          id={id}
          type="text"
          inputMode="decimal"
          value={form[name]}
          onChange={(event) => setForm({ ...form, [name]: event.target.value })}
          aria-invalid={error !== undefined}
          aria-describedby={error ? `${id}-error` : undefined}
          className={INPUT_CLASS}
        />
        {error && (
          <p id={`${id}-error`} className="mt-1 text-xs text-red-600 dark:text-red-400">
            {error}
          </p>
        )}
      </div>
    );
  };

  return (
    <details className="mt-3 text-sm">
      <summary className="cursor-pointer font-semibold text-slate-700 dark:text-slate-200">
        Race-day conditions
      </summary>
      <form onSubmit={submit} noValidate className="mt-2 flex flex-wrap items-start gap-4">
        {field('temperatureC', 'Temperature (°C)')}
        {field('elevationGainM', 'Course climb (m)')}
        <div className="flex gap-2 self-end">
          <button
            type="submit"
            className="rounded-md bg-blue-600 px-3 py-1 font-medium text-white hover:bg-blue-700"
          >
            Apply
          </button>
          <button
            type="button"
            onClick={clear}
            className="rounded-md px-3 py-1 font-medium text-slate-600 ring-1 ring-slate-300 hover:bg-slate-100 dark:text-slate-300 dark:ring-slate-700 dark:hover:bg-slate-800"
          >
            Clear
          </button>
        </div>
      </form>
    </details>
  );
}

/** One day's finish times as read out under the cursor, e.g. `Today · 5K 18:13 · … · Marathon 3:26:57`. */
function formatDayReading(day: RacePrognosisDayDto, today: string | null): string {
  return [
    day.day === today ? 'Today' : formatDate(day.day),
    ...RACE_DISTANCES.map(({ label, dayKey }) => `${label} ${formatFinishTime(day[dayKey])}`),
  ].join(' · ');
}

/** `today` is null after a break, when the latest day is not today and is read out by its date. */
function PrognosisTrend({
  days,
  today,
}: {
  days: readonly RacePrognosisDayDto[];
  today: string | null;
}) {
  const theme = useColorScheme();
  const latestTheme = useRef(theme);
  useEffect(() => {
    latestTheme.current = theme;
  });
  const container = useRef<HTMLDivElement>(null);
  const readout = useRef<HTMLOutputElement>(null);
  // The latest day's reading, shown whenever no day is hovered, so the values never need a pointer.
  const latestReading = useRef('');
  useLayoutEffect(() => {
    const last = days.at(-1);
    latestReading.current = last ? formatDayReading(last, today) : '';
    // Written to the DOM, not rendered, because hovering rewrites the same node directly.
    if (readout.current) readout.current.textContent = latestReading.current;
  }, [days, today]);
  const data = useMemo(() => alignedPrognosisData(days), [days]);

  const mount = useMemo(
    () =>
      ({
        createPrognosisChart,
      }: PrognosisChartModule): MountedRenderer<PrognosisChartHandle> | null => {
        if (!container.current) return null;
        const handle = createPrognosisChart({
          container: container.current,
          data,
          theme: latestTheme.current,
          // Written straight to the DOM: a React render per pointer move would be wasted work.
          onHover: (dayIndex) => {
            const day = dayIndex == null ? undefined : days[dayIndex];
            if (readout.current) {
              readout.current.textContent = day
                ? formatDayReading(day, today)
                : latestReading.current;
            }
          },
        });
        return {
          handle,
          unmount: () => {
            handle.destroy();
            if (readout.current) readout.current.textContent = latestReading.current;
          },
        };
      },
    [data, days, today],
  );
  const chart = useLazyRenderer(loadPrognosisChart, mount);
  const liveChart = chart.kind === 'ready' ? chart.handle : null;
  useEffect(() => {
    liveChart?.setTheme(theme);
  }, [liveChart, theme]);

  const palette = PROGNOSIS_PALETTE[theme];
  return (
    <figure aria-label="Race Prognosis trend" className="m-0 mt-4 space-y-2">
      <figcaption className="text-sm font-semibold text-slate-700 dark:text-slate-200">
        Predicted pace over time
      </figcaption>
      <output
        aria-label="Latest day, or the day under the cursor"
        // Rewritten on every pointer move, so it is read on demand rather than announced.
        aria-live="off"
        ref={readout}
        // Reserves the lines a reading wraps to on a phone, so hovering never shifts the chart.
        className="block min-h-10 text-sm tabular-nums text-slate-600 dark:text-slate-300"
      />
      <ul
        aria-label="Legend"
        className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-slate-600 dark:text-slate-300"
      >
        {RACE_DISTANCES.map(({ distance, label }) => (
          <li key={distance} className="flex items-center gap-2">
            <span
              aria-hidden="true"
              className="inline-block w-4 border-t-2"
              style={{ borderColor: palette[distance] }}
            />
            {label}
          </li>
        ))}
      </ul>
      <div ref={container} style={{ height: PROGNOSIS_PLOT_HEIGHT_PX }} className="relative w-full">
        {chart.kind === 'failed' && (
          <p className="absolute inset-0 flex items-center justify-center text-sm text-slate-500 dark:text-slate-400">
            This chart could not be displayed.
          </p>
        )}
      </div>
    </figure>
  );
}
