import { type FormEvent, useState } from 'react';
import {
  browserTimeZone,
  useAcceptProposal,
  useDeleteThreshold,
  useDismissProposal,
  useRecordThreshold,
  useSetTimeZone,
} from '../../api/athlete';
import type {
  AthleteThresholdsResponseDto,
  HeartRateZoneDto,
  PaceZoneDto,
  ThresholdEntryDto,
  ThresholdKind,
  ThresholdProposalDto,
} from '../../api/generated/types.gen';
import { ErrorAlert } from '../../components/ErrorAlert';
import { formatDate } from '../../lib/format';
import { parseApiError } from '../../utils/error';
import { formatPace, parsePace } from './pace';

const KINDS: readonly ThresholdKind[] = [
  'LTHR',
  'MAX_HR',
  'RESTING_HR',
  'THRESHOLD_PACE',
  'CRITICAL_POWER',
];

const KIND_LABELS: Readonly<Record<ThresholdKind, string>> = {
  MAX_HR: 'Max HR',
  RESTING_HR: 'Resting HR',
  LTHR: 'LTHR',
  THRESHOLD_PACE: 'Threshold pace',
  CRITICAL_POWER: 'Critical power',
};

const VALUE_LABELS: Readonly<Record<ThresholdKind, string>> = {
  MAX_HR: 'Value (bpm)',
  RESTING_HR: 'Value (bpm)',
  LTHR: 'Value (bpm)',
  THRESHOLD_PACE: 'Pace (min:sec per km)',
  CRITICAL_POWER: 'Value (W)',
};

const RECALCULATING =
  'Saved. Metrics that depend on your Thresholds are recalculated in the background.';

/** A Threshold value in its unit: whole bpm or watts, threshold pace as `m:ss /km`. */
function formatThreshold(kind: ThresholdKind, value: number): string {
  if (kind === 'THRESHOLD_PACE') return `${formatPace(value)} /km`;
  return `${Math.round(value)} ${kind === 'CRITICAL_POWER' ? 'W' : 'bpm'}`;
}

/** Today in the browser's calendar, as `YYYY-MM-DD`. */
function today(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** Every IANA zone the browser knows, always including the athlete's own. */
function timeZoneOptions(current: string): string[] {
  const known =
    typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [];
  return Array.from(new Set([current, 'UTC', ...known])).sort();
}

/** A heart-rate zone's range in whole bpm: each zone ends one beat below the next one's lower bound. */
function heartRateRange(zone: HeartRateZoneDto): string {
  if (zone.lowerBpm === null) return `Below ${zone.upperBpm} bpm`;
  if (zone.upperBpm === null) return `${zone.lowerBpm} bpm and above`;
  const upper = zone.zone === 5 ? zone.upperBpm : zone.upperBpm - 1;
  return `${zone.lowerBpm}–${upper} bpm`;
}

/** A pace zone's range, from its slower to its faster pace. */
function paceRange(zone: PaceZoneDto): string {
  if (zone.lowerSpeedMps === null) return `Slower than ${formatPace(zone.upperSpeedMps ?? 0)} /km`;
  if (zone.upperSpeedMps === null) return `Faster than ${formatPace(zone.lowerSpeedMps)} /km`;
  return `${formatPace(zone.lowerSpeedMps)}–${formatPace(zone.upperSpeedMps)} /km`;
}

const cardClass =
  'rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 space-y-3';
const inputClass =
  'rounded-md border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-3 py-1.5 text-sm';
const primaryButtonClass =
  'rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50';
const tableClass = 'w-full text-left text-sm';
const theadClass = 'bg-slate-50 dark:bg-slate-800/50 text-slate-600 dark:text-slate-400';

interface SettingsPageProps {
  readonly userId: number | undefined;
  readonly timeZone: string | null;
  readonly thresholds: AthleteThresholdsResponseDto;
}

/** The athlete's settings: time zone, **Thresholds** with their history, **Zones** and suggested updates. */
export function SettingsPage({ userId, timeZone, thresholds }: SettingsPageProps) {
  return (
    <div className="space-y-6">
      <header className="border-b border-slate-200 dark:border-slate-800 pb-4">
        <h1 className="text-2xl font-bold tracking-tight">Settings</h1>
        <p className="mt-1 text-slate-600 dark:text-slate-400">
          Your Thresholds set your training Zones and the metrics derived from your Activities. Each
          entry applies from its date until the next one; the earliest also covers everything before
          it.
        </p>
      </header>

      {thresholds.proposals.length > 0 && (
        <SuggestedUpdates userId={userId} proposals={thresholds.proposals} />
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <CurrentThresholds userId={userId} thresholds={thresholds} />
        <TimeZoneSetting userId={userId} timeZone={timeZone} />
      </div>

      <section aria-labelledby="zones-heading" className="space-y-3">
        <h2 id="zones-heading" className="text-lg font-semibold">
          Zones
        </h2>
        <div className="grid gap-6 lg:grid-cols-2">
          <HeartRateZones zones={thresholds.heartRateZones} />
          <PaceZones zones={thresholds.paceZones} />
        </div>
      </section>

      <History userId={userId} entries={thresholds.entries} />
    </div>
  );
}

function SuggestedUpdates({
  userId,
  proposals,
}: {
  readonly userId: number | undefined;
  readonly proposals: readonly ThresholdProposalDto[];
}) {
  const accept = useAcceptProposal(userId);
  const dismiss = useDismissProposal(userId);
  const failure = accept.error ?? dismiss.error;
  return (
    <section
      aria-labelledby="suggested-updates-heading"
      className="rounded-lg border border-blue-200 dark:border-blue-900 bg-blue-50 dark:bg-blue-950/40 p-4 space-y-3"
    >
      <h2 id="suggested-updates-heading" className="font-semibold">
        Suggested updates
      </h2>
      <p className="text-sm text-slate-700 dark:text-slate-300">
        Estimated from your Activities. Nothing changes unless you accept it.
      </p>
      {failure && <ErrorAlert>{parseApiError(failure).generalError}</ErrorAlert>}
      <ul className="divide-y divide-blue-200 dark:divide-blue-900">
        {proposals.map((proposal) => (
          <li key={proposal.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
            <span className="text-sm">
              <span className="font-medium">{KIND_LABELS[proposal.kind]}</span>{' '}
              {formatThreshold(proposal.kind, proposal.value)} from{' '}
              {formatDate(proposal.effectiveDate)}
              <span className="text-slate-500 dark:text-slate-400"> · {proposal.source}</span>
            </span>
            <span className="flex gap-2">
              <button
                type="button"
                onClick={() => accept.mutate(proposal.id)}
                disabled={accept.isPending}
                className={primaryButtonClass}
              >
                Accept
              </button>
              <button
                type="button"
                onClick={() => dismiss.mutate(proposal.id)}
                disabled={dismiss.isPending}
                className="rounded-md border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 px-3 py-1.5 text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 disabled:opacity-50"
              >
                Dismiss
              </button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function CurrentThresholds({
  userId,
  thresholds,
}: {
  readonly userId: number | undefined;
  readonly thresholds: AthleteThresholdsResponseDto;
}) {
  const { current } = thresholds;
  const values: ReadonlyArray<[ThresholdKind, number | null]> = [
    ['LTHR', current.lthr],
    ['MAX_HR', current.maxHr],
    ['RESTING_HR', current.restingHr],
    ['THRESHOLD_PACE', current.thresholdSpeedMps],
    ['CRITICAL_POWER', current.criticalPowerWatts],
  ];
  return (
    <section aria-labelledby="thresholds-heading" className={cardClass}>
      <h2 id="thresholds-heading" className="text-lg font-semibold">
        Thresholds
      </h2>
      {thresholds.entries.length === 0 && (
        <p className="text-sm text-slate-600 dark:text-slate-400">
          No Thresholds recorded yet. Add your LTHR or threshold pace to see your Zones.
        </p>
      )}
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-3">
        {values.map(([kind, value]) => (
          <div key={kind}>
            <dt className="text-slate-500 dark:text-slate-400">{KIND_LABELS[kind]}</dt>
            <dd className="font-medium tabular-nums">
              {value === null ? '—' : formatThreshold(kind, value)}
            </dd>
          </div>
        ))}
      </dl>
      <AddEntryForm userId={userId} />
    </section>
  );
}

function AddEntryForm({ userId }: { readonly userId: number | undefined }) {
  const record = useRecordThreshold(userId);
  const [kind, setKind] = useState<ThresholdKind>('LTHR');
  const [effectiveDate, setEffectiveDate] = useState(today);
  const [value, setValue] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    setSaved(false);
    const parsed = kind === 'THRESHOLD_PACE' ? parsePace(value) : Number(value);
    if (parsed === null || value.trim() === '' || !Number.isFinite(parsed)) {
      setLocalError(
        kind === 'THRESHOLD_PACE' ? 'Enter the pace as min:sec, such as 4:30' : 'Enter a number',
      );
      return;
    }
    setLocalError(null);
    record.mutate(
      { kind, effectiveDate, value: parsed },
      {
        onSuccess: () => {
          setValue('');
          setSaved(true);
        },
      },
    );
  };

  const apiErrors = record.error ? parseApiError(record.error) : null;
  const fieldError = (field: string) => apiErrors?.fieldErrors[field];
  const valueError = localError ?? fieldError('value') ?? fieldError('kind');
  const dateError = fieldError('effectiveDate');

  return (
    <form
      onSubmit={handleSubmit}
      className="space-y-3 border-t border-slate-200 dark:border-slate-800 pt-3"
    >
      <h3 className="text-sm font-semibold">Add an entry</h3>
      <div className="grid gap-3 sm:grid-cols-3">
        <label className="flex flex-col gap-1 text-sm">
          <span>Threshold</span>
          <select
            value={kind}
            onChange={(e) => {
              setKind(e.target.value as ThresholdKind);
              setValue('');
              setLocalError(null);
            }}
            className={inputClass}
          >
            {KINDS.map((k) => (
              <option key={k} value={k}>
                {KIND_LABELS[k]}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span>Effective from</span>
          <input
            type="date"
            required
            value={effectiveDate}
            onChange={(e) => setEffectiveDate(e.target.value)}
            className={inputClass}
          />
          {dateError && <span className="text-red-600 dark:text-red-400">{dateError}</span>}
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span>{VALUE_LABELS[kind]}</span>
          <input
            type="text"
            inputMode={kind === 'THRESHOLD_PACE' ? 'text' : 'decimal'}
            placeholder={kind === 'THRESHOLD_PACE' ? '4:30' : undefined}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            className={inputClass}
          />
          {valueError && <span className="text-red-600 dark:text-red-400">{valueError}</span>}
        </label>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={record.isPending} className={primaryButtonClass}>
          {record.isPending ? 'Saving…' : 'Add entry'}
        </button>
        {saved && (
          <output className="text-sm text-slate-600 dark:text-slate-400">{RECALCULATING}</output>
        )}
        {apiErrors && Object.keys(apiErrors.fieldErrors).length === 0 && (
          <ErrorAlert>{apiErrors.generalError}</ErrorAlert>
        )}
      </div>
    </form>
  );
}

function TimeZoneSetting({
  userId,
  timeZone,
}: {
  readonly userId: number | undefined;
  readonly timeZone: string | null;
}) {
  const setTimeZone = useSetTimeZone(userId);
  const current = timeZone ?? browserTimeZone() ?? 'UTC';
  const [selected, setSelected] = useState(current);

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    setTimeZone.mutate(selected);
  };

  return (
    <section aria-labelledby="time-zone-heading" className={cardClass}>
      <h2 id="time-zone-heading" className="text-lg font-semibold">
        Your time zone
      </h2>
      <p className="text-sm text-slate-600 dark:text-slate-400">
        Your calendar days, such as the day an Activity counts towards, are counted in this zone.
      </p>
      <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span>Time zone</span>
          <select
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            className={inputClass}
          >
            {timeZoneOptions(current).map((zone) => (
              <option key={zone} value={zone}>
                {zone}
              </option>
            ))}
          </select>
        </label>
        <button
          type="submit"
          disabled={setTimeZone.isPending || selected === timeZone}
          className={primaryButtonClass}
        >
          Save time zone
        </button>
      </form>
      {setTimeZone.isSuccess && (
        <output className="block text-sm text-slate-600 dark:text-slate-400">
          {RECALCULATING}
        </output>
      )}
      {setTimeZone.error && (
        <ErrorAlert>
          {parseApiError(setTimeZone.error).fieldErrors.timeZone ??
            parseApiError(setTimeZone.error).generalError}
        </ErrorAlert>
      )}
    </section>
  );
}

function HeartRateZones({ zones }: { readonly zones: readonly HeartRateZoneDto[] | null }) {
  return (
    <div className={cardClass}>
      <h3 className="font-semibold">Heart rate</h3>
      {zones === null ? (
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Record an LTHR to see your heart-rate zones.
        </p>
      ) : (
        <ZoneTable
          caption="Heart-rate zones"
          rows={zones.map((zone) => ({
            zone: zone.zone,
            range: heartRateRange(zone),
            seilerZone: zone.seilerZone,
          }))}
        />
      )}
    </div>
  );
}

function PaceZones({ zones }: { readonly zones: readonly PaceZoneDto[] | null }) {
  return (
    <div className={cardClass}>
      <h3 className="font-semibold">Pace</h3>
      {zones === null ? (
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Record a threshold pace to see your pace zones.
        </p>
      ) : (
        <ZoneTable
          caption="Pace zones"
          rows={zones.map((zone) => ({
            zone: zone.zone,
            range: paceRange(zone),
            seilerZone: zone.seilerZone,
          }))}
        />
      )}
    </div>
  );
}

function ZoneTable({
  caption,
  rows,
}: {
  readonly caption: string;
  readonly rows: ReadonlyArray<{ zone: number; range: string; seilerZone: number }>;
}) {
  return (
    <table className={tableClass}>
      <caption className="sr-only">{caption}</caption>
      <thead className={theadClass}>
        <tr>
          <th scope="col" className="px-3 py-2 font-medium">
            Zone
          </th>
          <th scope="col" className="px-3 py-2 font-medium">
            Range
          </th>
          <th scope="col" className="px-3 py-2 font-medium">
            Seiler zone
          </th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
        {rows.map((row) => (
          <tr key={row.zone}>
            <th scope="row" className="px-3 py-2 font-medium">
              Z{row.zone}
            </th>
            <td className="px-3 py-2 tabular-nums">{row.range}</td>
            <td className="px-3 py-2 tabular-nums">{row.seilerZone}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function History({
  userId,
  entries,
}: {
  readonly userId: number | undefined;
  readonly entries: readonly ThresholdEntryDto[];
}) {
  const remove = useDeleteThreshold(userId);
  return (
    <section aria-labelledby="history-heading" className="space-y-3">
      <h2 id="history-heading" className="text-lg font-semibold">
        History
      </h2>
      {remove.error && <ErrorAlert>{parseApiError(remove.error).generalError}</ErrorAlert>}
      {entries.length === 0 ? (
        <div className="rounded-lg border border-dashed border-slate-300 dark:border-slate-700 p-8 text-center">
          <p className="font-medium">No entries yet.</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900">
          <table className={tableClass}>
            <caption className="sr-only">Threshold history</caption>
            <thead className={theadClass}>
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">
                  Threshold
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  From
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Value
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
              {entries.map((entry) => (
                <tr key={entry.id}>
                  <td className="px-3 py-2">{KIND_LABELS[entry.kind]}</td>
                  <td className="px-3 py-2 tabular-nums">{formatDate(entry.effectiveDate)}</td>
                  <td className="px-3 py-2 tabular-nums">
                    {formatThreshold(entry.kind, entry.value)}
                  </td>
                  <td className="px-3 py-2 text-right">
                    <button
                      type="button"
                      onClick={() => remove.mutate(entry.id)}
                      disabled={remove.isPending}
                      aria-label={`Delete the ${KIND_LABELS[entry.kind]} entry from ${formatDate(entry.effectiveDate)}`}
                      className="rounded-md px-2 py-1 text-sm font-medium text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/50 disabled:opacity-50"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
