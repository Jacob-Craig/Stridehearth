import { Link } from '@tanstack/react-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { match } from 'ts-pattern';
import type { BenchmarkProgressionDto } from '../../api/generated/types.gen';
import { useColorScheme } from '../../lib/colorScheme';
import { formatDate, formatDuration } from '../../lib/format';
import { type MountedRenderer, useLazyRenderer } from '../telemetry/useLazyRenderer';
import { loadProgressionChart } from './lazyProgressionChart';
import type { ProgressionChartHandle } from './progressionChart';
import {
  type BenchmarkDistance,
  PROGRESSION_PLOT_HEIGHT_PX,
  defaultBenchmark,
  progressionSteps,
} from './progressionSeries';

type ProgressionChartModule = typeof import('./progressionChart');

interface PersonalRecordProgressionProps {
  progressions: readonly BenchmarkProgressionDto[];
}

/**
 * The **Personal Record Progression** of one Benchmark Distance at a time: a stepped chart of the
 * record time against the date each record was set, its last step held until today.
 */
export function PersonalRecordProgression({ progressions }: PersonalRecordProgressionProps) {
  const withRecords = progressions.filter((p) => p.records.length > 0);
  const [chosen, setChosen] = useState<BenchmarkDistance | null>(null);
  const selected =
    withRecords.find((p) => p.benchmarkDistance === chosen) ??
    withRecords.find((p) => p.benchmarkDistance === defaultBenchmark(progressions));

  return (
    <section
      aria-labelledby="pr-progression-heading"
      className="mt-4 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-5 shadow-sm"
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3
          id="pr-progression-heading"
          className="text-sm font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400"
        >
          Personal Record Progression
        </h3>
        {selected && (
          <label className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
            Benchmark Distance
            <select
              value={selected.benchmarkDistance}
              onChange={(event) => setChosen(event.target.value as BenchmarkDistance)}
              className="rounded-md border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-2 py-1"
            >
              {withRecords.map((p) => (
                <option key={p.benchmarkDistance} value={p.benchmarkDistance}>
                  {p.benchmarkName}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>
      {selected ? (
        <ProgressionFigure progression={selected} />
      ) : (
        <p className="py-2 text-slate-500 dark:text-slate-400">No records yet</p>
      )}
    </section>
  );
}

function ProgressionFigure({ progression }: { progression: BenchmarkProgressionDto }) {
  const theme = useColorScheme();
  const container = useRef<HTMLDivElement>(null);
  // The current theme, read when the chart is created; later changes restyle the live chart.
  const latestTheme = useRef(theme);
  useEffect(() => {
    latestTheme.current = theme;
  });
  // "Today" is fixed when the figure first renders, so re-renders do not rebuild the chart.
  const [nowMs] = useState(() => Date.now());
  const { records } = progression;
  const points = useMemo(() => progressionSteps(records, nowMs), [records, nowMs]);

  const mount = useMemo(
    () =>
      ({
        createProgressionChart,
      }: ProgressionChartModule): MountedRenderer<ProgressionChartHandle> | null => {
        if (!container.current) return null;
        const handle = createProgressionChart({
          container: container.current,
          points,
          recordCount: records.length,
          theme: latestTheme.current,
        });
        return { handle, unmount: () => handle.destroy() };
      },
    [points, records],
  );
  const chart = useLazyRenderer(loadProgressionChart, mount);
  const liveChart = chart.kind === 'ready' ? chart.handle : null;
  useEffect(() => {
    liveChart?.setTheme(theme);
  }, [liveChart, theme]);

  const title = `${progression.benchmarkName} Personal Record Progression`;
  return (
    <figure aria-label={title} className="m-0">
      <div
        ref={container}
        style={{ height: PROGRESSION_PLOT_HEIGHT_PX }}
        className="relative w-full"
      >
        {match(chart)
          .with({ kind: 'failed' }, () => (
            <p className="absolute inset-0 flex items-center justify-center text-sm text-slate-500 dark:text-slate-400">
              This chart could not be displayed.
            </p>
          ))
          .with({ kind: 'loading' }, { kind: 'ready' }, () => null)
          .exhaustive()}
      </div>
      <table className="sr-only">
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Set on</th>
            <th scope="col">Time</th>
            <th scope="col">Activity</th>
          </tr>
        </thead>
        <tbody>
          {records.map((record) => (
            <tr key={record.activityId}>
              <td>
                <time dateTime={record.activityStartedAt}>
                  {formatDate(record.activityStartedAt)}
                </time>
              </td>
              <td>{formatDuration(record.elapsedTimeSeconds)}</td>
              <td>
                <Link
                  to="/activities/$activityId"
                  params={{ activityId: String(record.activityId) }}
                >
                  {record.activityTitle}
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
