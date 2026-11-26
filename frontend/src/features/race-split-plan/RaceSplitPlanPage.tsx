import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { type FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import type { ActivityDetailDto, RaceSplitPlanResponseDto } from '../../api/generated/types.gen';
import { racePrognosisQueryOptions } from '../../api/racePrognosis';
import { type RaceSplitPlanRequest, raceSplitPlanQueryOptions } from '../../api/raceSplitPlan';
import { ErrorAlert } from '../../components/ErrorAlert';
import { useColorScheme } from '../../lib/colorScheme';
import { parseApiError } from '../../utils/error';
import { SECONDARY_BUTTON_CLASS } from '../activity-detail/styles';
import { NumericCell, type TableColumn, TableSection } from '../activity-detail/TableSection';
import { loadElevationProfileChart } from '../charts/lazyCharts';
import { RACE_DISTANCES } from '../race-prognosis/prognosisSeries';
import { type MountedRenderer, useLazyRenderer } from '../telemetry/useLazyRenderer';
import type { ElevationProfileHandle } from './elevationProfileChart';
import {
  BIAS_STEP_PCT,
  type ElevationProfileData,
  elevationProfileData,
  formatGoalTime,
  formatGrade,
  formatSplitTime,
  formatUnitDistance,
  initialGoalSeconds,
  MAX_BIAS_PCT,
  PROFILE_PLOT_HEIGHT_PX,
  parseGoalTime,
  predictedGoal,
  type SplitUnit,
  splitsTsv,
  UNIT_LABELS,
} from './splitPlan';

type ElevationProfileModule = typeof import('./elevationProfileChart');

/** The most climb the Race Prognosis derates for, in m, as the API enforces it. */
const MAX_PROGNOSIS_CLIMB_M = 5000;

interface RaceSplitPlanPageProps {
  readonly userId: number | undefined;
  readonly activity: ActivityDetailDto;
}

/**
 * A **Race Split Plan** over an Activity's route: a target time per km or mile that holds Grade-Adjusted
 * effort even and sums to a **Goal Time**, with an optional **Split Bias**. It starts from the Activity's own
 * time; "Use predicted time" fills in the athlete's **Race Prognosis**, derated for the course's climb and
 * scaled to its length. The page prints without the navigation and controls.
 */
export function RaceSplitPlanPage({ userId, activity }: RaceSplitPlanPageProps) {
  const [request, setRequest] = useState<RaceSplitPlanRequest>(() => ({
    goalSeconds: initialGoalSeconds(activity.timeTakenSeconds),
    unit: 'KM',
    biasPct: 0,
  }));
  const plan = useQuery(raceSplitPlanQueryOptions(userId, activity.id, request));
  const course = plan.data;
  const climbM =
    course?.elevationGainM != null
      ? Math.min(MAX_PROGNOSIS_CLIMB_M, Math.round(course.elevationGainM))
      : undefined;
  const prognosis = useQuery({
    ...racePrognosisQueryOptions(userId, climbM ? { elevationGainM: climbM } : {}),
    enabled: course !== undefined,
  });
  const suggestion =
    course && prognosis.data?.current
      ? predictedGoal(course.courseDistanceM, prognosis.data.current.predictions)
      : null;

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 dark:border-slate-800 pb-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Race Split Plan</h1>
          <p className="mt-1 text-slate-600 dark:text-slate-400">
            <Link
              to="/activities/$activityId"
              params={{ activityId: String(activity.id) }}
              className="font-semibold text-blue-600 dark:text-blue-400 hover:underline print:text-inherit print:no-underline"
            >
              {activity.title}
            </Link>
            {course && <CourseSummary plan={course} />}
          </p>
        </div>
        {course && <PlanActions plan={course} />}
      </header>

      <PlanControls
        request={request}
        onChange={setRequest}
        suggestion={suggestion}
        suggestionLabel={
          suggestion
            ? (RACE_DISTANCES.find((d) => d.distance === suggestion.distance)?.label ??
              suggestion.distance)
            : null
        }
      />

      {plan.status === 'pending' ? (
        <p className="text-slate-500 dark:text-slate-400">Loading…</p>
      ) : plan.status === 'error' ? (
        <ErrorAlert>
          This Race Split Plan could not be made. {planErrorReason(plan.error)}
        </ErrorAlert>
      ) : (
        <>
          <p className="text-sm tabular-nums text-slate-600 dark:text-slate-300">
            Goal Time <strong>{formatGoalTime(plan.data.goalSeconds)}</strong> · Split Bias{' '}
            {formatBias(plan.data.biasPct)}
          </p>
          {plan.data.profile.length > 1 && <ElevationProfile plan={plan.data} />}
          <SplitsTable plan={plan.data} />
        </>
      )}
    </div>
  );
}

/** The server's own reasons (such as an Activity with no route) rather than a bare "Validation failed". */
function planErrorReason(error: unknown): string {
  const { generalError, fieldErrors } = parseApiError(error);
  const reasons = Object.values(fieldErrors);
  return reasons.length > 0 ? reasons.join(' ') : generalError;
}

/** How long the Split Bias slider rests before the plan is asked for, so a drag sends one request. */
const BIAS_SETTLE_MS = 250;

function formatBias(biasPct: number): string {
  if (biasPct === 0) return 'even';
  return `${biasPct > 0 ? '+' : ''}${biasPct}% (${biasPct > 0 ? 'positive' : 'negative'} split)`;
}

function CourseSummary({ plan }: { plan: RaceSplitPlanResponseDto }) {
  return (
    <span className="tabular-nums">
      {' · '}
      {(plan.courseDistanceM / 1000).toFixed(2)} km
      {plan.elevationGainM != null && ` · ${Math.round(plan.elevationGainM)} m climb`}
    </span>
  );
}

function PlanActions({ plan }: { plan: RaceSplitPlanResponseDto }) {
  const [copied, setCopied] = useState<'idle' | 'copied' | 'failed'>('idle');
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(splitsTsv(plan));
      setCopied('copied');
    } catch {
      setCopied('failed');
    }
  };
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm font-medium print:hidden">
      <output aria-live="polite" className="text-slate-600 dark:text-slate-300">
        {copied === 'copied' ? 'Splits copied' : copied === 'failed' ? 'Could not copy' : ''}
      </output>
      <button type="button" onClick={copy} className={SECONDARY_BUTTON_CLASS}>
        Copy splits
      </button>
      <button type="button" onClick={() => window.print()} className={SECONDARY_BUTTON_CLASS}>
        Print
      </button>
    </div>
  );
}

const INPUT_CLASS =
  'mt-1 block w-32 rounded-md border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-950 px-2 py-1 text-sm tabular-nums aria-invalid:border-red-500';

function PlanControls({
  request,
  onChange,
  suggestion,
  suggestionLabel,
}: {
  request: RaceSplitPlanRequest;
  onChange: (request: RaceSplitPlanRequest) => void;
  suggestion: { readonly seconds: number } | null;
  suggestionLabel: string | null;
}) {
  const [goalText, setGoalText] = useState(() => formatGoalTime(request.goalSeconds));
  // The slider's own value: its label follows it at once, and the plan is asked for once it settles.
  const [bias, setBias] = useState(request.biasPct);
  const latest = useRef({ request, onChange });
  useEffect(() => {
    latest.current = { request, onChange };
  });
  useEffect(() => {
    if (bias === latest.current.request.biasPct) return;
    const timer = setTimeout(
      () => latest.current.onChange({ ...latest.current.request, biasPct: bias }),
      BIAS_SETTLE_MS,
    );
    return () => clearTimeout(timer);
  }, [bias]);
  const [goalError, setGoalError] = useState<string | null>(null);

  const applyGoal = (event: FormEvent) => {
    event.preventDefault();
    const parsed = parseGoalTime(goalText);
    if (parsed.ok) {
      setGoalError(null);
      onChange({ ...request, goalSeconds: parsed.seconds });
    } else {
      setGoalError(parsed.error);
    }
  };
  const applyPredicted = (seconds: number) => {
    setGoalText(formatGoalTime(seconds));
    setGoalError(null);
    onChange({ ...request, goalSeconds: seconds });
  };

  return (
    <section
      aria-label="Plan settings"
      className="rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 shadow-sm print:hidden"
    >
      <div className="flex flex-wrap items-end gap-x-8 gap-y-4 text-sm">
        <form onSubmit={applyGoal} noValidate className="flex flex-wrap items-end gap-2">
          <div>
            <label htmlFor="goal-time" className="text-slate-600 dark:text-slate-300">
              Goal Time (h:mm:ss)
            </label>
            <input
              id="goal-time"
              type="text"
              inputMode="numeric"
              value={goalText}
              onChange={(event) => setGoalText(event.target.value)}
              aria-invalid={goalError !== null}
              aria-describedby={goalError ? 'goal-time-error' : undefined}
              className={INPUT_CLASS}
            />
          </div>
          <button
            type="submit"
            className="rounded-md bg-blue-600 px-3 py-1 font-medium text-white hover:bg-blue-700"
          >
            Apply
          </button>
          {suggestion && (
            <button
              type="button"
              onClick={() => applyPredicted(suggestion.seconds)}
              className="rounded-md px-3 py-1 font-medium text-blue-700 ring-1 ring-blue-300 hover:bg-blue-50 dark:text-blue-300 dark:ring-blue-800 dark:hover:bg-blue-950"
            >
              Use predicted time
              <span className="ml-1 font-normal tabular-nums text-slate-500 dark:text-slate-400">
                ({formatGoalTime(suggestion.seconds)} from {suggestionLabel})
              </span>
            </button>
          )}
          {goalError && (
            <p id="goal-time-error" className="basis-full text-xs text-red-600 dark:text-red-400">
              {goalError}
            </p>
          )}
        </form>

        <fieldset>
          <legend className="text-slate-600 dark:text-slate-300">Splits per</legend>
          <div className="mt-1 inline-flex overflow-hidden rounded-md ring-1 ring-slate-300 dark:ring-slate-700">
            {(Object.keys(UNIT_LABELS) as SplitUnit[]).map((unit) => (
              <button
                key={unit}
                type="button"
                aria-pressed={request.unit === unit}
                onClick={() => onChange({ ...request, unit })}
                className="px-3 py-1 font-medium aria-pressed:bg-blue-600 aria-pressed:text-white hover:bg-slate-100 dark:hover:bg-slate-800 aria-pressed:hover:bg-blue-700"
              >
                {unit === 'KM' ? 'Kilometre' : 'Mile'}
              </button>
            ))}
          </div>
        </fieldset>

        <div>
          <label htmlFor="split-bias" className="text-slate-600 dark:text-slate-300">
            Split Bias <span className="tabular-nums">{formatBias(bias)}</span>
          </label>
          <input
            id="split-bias"
            type="range"
            min={-MAX_BIAS_PCT}
            max={MAX_BIAS_PCT}
            step={BIAS_STEP_PCT}
            value={bias}
            onChange={(event) => setBias(Number(event.target.value))}
            className="mt-2 block w-48"
          />
        </div>
      </div>
    </section>
  );
}

function ElevationProfile({ plan }: { plan: RaceSplitPlanResponseDto }) {
  const theme = useColorScheme();
  const latestTheme = useRef(theme);
  useEffect(() => {
    latestTheme.current = theme;
  });
  const container = useRef<HTMLDivElement>(null);
  // A new Goal Time or Split Bias is a new plan over the same course: keep the data, and so the chart.
  const data = useStableValue(elevationProfileData(plan), sameProfileData);
  const mount = useMemo(
    () =>
      ({
        createElevationProfileChart,
      }: ElevationProfileModule): MountedRenderer<ElevationProfileHandle> | null => {
        if (!container.current) return null;
        const handle = createElevationProfileChart({
          container: container.current,
          data,
          theme: latestTheme.current,
        });
        return { handle, unmount: () => handle.destroy() };
      },
    [data],
  );
  const chart = useLazyRenderer(loadElevationProfileChart, mount);
  const liveChart = chart.kind === 'ready' ? chart.handle : null;
  useEffect(() => {
    liveChart?.setTheme(theme);
  }, [liveChart, theme]);

  return (
    <figure
      aria-label="Elevation profile"
      className="rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 shadow-sm break-inside-avoid"
    >
      <figcaption className="mb-2 text-sm font-semibold text-slate-700 dark:text-slate-200">
        Elevation profile
      </figcaption>
      <div ref={container} style={{ height: PROFILE_PLOT_HEIGHT_PX }} className="relative w-full">
        {chart.kind === 'failed' && (
          <p className="absolute inset-0 flex items-center justify-center text-sm text-slate-500 dark:text-slate-400">
            This chart could not be displayed.
          </p>
        )}
      </div>
    </figure>
  );
}

function sameNumbers(a: readonly number[], b: readonly number[]): boolean {
  return a.length === b.length && a.every((value, i) => value === b[i]);
}

function sameProfileData(a: ElevationProfileData, b: ElevationProfileData): boolean {
  return (
    a.unitLabel === b.unitLabel &&
    sameNumbers(a.x, b.x) &&
    sameNumbers(a.altitude, b.altitude) &&
    sameNumbers(a.boundaries, b.boundaries)
  );
}

/** [value], or the previous one while it is [equal] to it, so effects keyed on it do not re-run. */
function useStableValue<T>(value: T, equal: (a: T, b: T) => boolean): T {
  const stable = useRef(value);
  if (stable.current !== value && !equal(stable.current, value)) stable.current = value;
  return stable.current;
}

function formatGainLoss(gainM: number, lossM: number): string {
  const gain = Math.round(gainM);
  const loss = Math.round(lossM);
  return `${gain > 0 ? `+${gain}` : '0'} / ${loss > 0 ? `−${loss}` : '0'} m`;
}

function SplitsTable({ plan }: { plan: RaceSplitPlanResponseDto }) {
  const unit = UNIT_LABELS[plan.unit].short;
  const columns: readonly TableColumn[] = [
    { label: 'Split' },
    { label: `To (${unit})`, numeric: true },
    { label: 'Target', numeric: true },
    { label: `Pace (/${unit})`, numeric: true },
    { label: 'Cumulative', numeric: true },
    { label: 'Gain / Loss', numeric: true },
    { label: 'Grade', numeric: true },
  ];
  return (
    <TableSection
      headingId="splits-heading"
      title="Splits"
      description="Even Grade-Adjusted effort: slower up hills, faster (to a limit) down them."
      columns={columns}
    >
      {plan.splits.map((split) => (
        <tr key={split.index} className="break-inside-avoid">
          <td className="px-4 py-2 font-semibold tabular-nums">{split.index}</td>
          <NumericCell>{formatUnitDistance(split.endM, plan.unit)}</NumericCell>
          <NumericCell>{formatSplitTime(split.targetSeconds)}</NumericCell>
          <NumericCell>{formatSplitTime(split.paceSecondsPerUnit)}</NumericCell>
          <NumericCell>{formatSplitTime(split.cumulativeSeconds)}</NumericCell>
          <NumericCell>
            {split.elevationGainM == null || split.elevationLossM == null
              ? '—'
              : formatGainLoss(split.elevationGainM, split.elevationLossM)}
          </NumericCell>
          <NumericCell>
            {split.avgGradePct == null ? '—' : formatGrade(split.avgGradePct)}
          </NumericCell>
        </tr>
      ))}
    </TableSection>
  );
}
