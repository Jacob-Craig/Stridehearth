/**
 * The one rendering of an **Activity History Entry**'s change (and of an **Import Batch Item**'s upgrades)
 * as English. The API sends typed changes only (ADR-0004); every word of history wording lives here.
 */

import { match } from 'ts-pattern';
import type {
  ActivityChangeDto,
  DetailsUpgradeDto,
  FieldChangeDto,
  LapsUpgradeDto,
} from '../api/generated/types.gen';
import type { ActivityHistoryChangeType } from '../types/domain';
import {
  type ActivitySource,
  MISSING,
  formatCount,
  formatDateTime,
  formatDistanceKm,
  formatDuration,
  formatOptional,
  formatPacePerKm,
  formatSport,
} from './format';

/**
 * How an import names where an **Activity** came from: `Imported from FIT file`. Deliberately not
 * `formatSource`'s labels (`FIT Upload`), which name the source, not the file it came in.
 */
const IMPORT_SOURCE_LABELS: Readonly<Record<ActivitySource, string>> = {
  MANUAL: 'manual entry',
  FIT_UPLOAD: 'FIT file',
  GARMIN: 'Garmin',
  STRAVA: 'Strava',
};

const lapsText = (count: number): string => formatCount(count, 'lap', 'laps');

const parserText = (version: number): string => `Parser v${version}`;

/** `from → to`, with an absent value shown as `—`. */
function fromTo<T>(from: T | null, to: T | null, format: (value: T) => string): string {
  const show = (value: T | null) => (value === null ? MISSING : format(value));
  return `${show(from)} → ${show(to)}`;
}

function describeField(field: FieldChangeDto, timeZone?: string): string {
  return match(field)
    .with({ kind: 'TITLE' }, (f) => `title: ${f.from} → ${f.to}`)
    .with({ kind: 'SPORT' }, (f) => `sport: ${fromTo(f.from, f.to, formatSport)}`)
    .with(
      { kind: 'STARTED_AT' },
      (f) => `start time: ${fromTo(f.from, f.to, (iso) => formatDateTime(iso, timeZone))}`,
    )
    .with({ kind: 'DISTANCE' }, (f) => `distance: ${fromTo(f.from, f.to, formatDistanceKm)}`)
    .with({ kind: 'TIME_TAKEN' }, (f) => `time taken: ${fromTo(f.from, f.to, formatDuration)}`)
    .with({ kind: 'AVERAGE_PACE' }, (f) => `average pace: ${fromTo(f.from, f.to, formatPacePerKm)}`)
    .with(
      { kind: 'AVERAGE_HEART_RATE' },
      (f) => `average heart rate: ${fromTo(f.from, f.to, (bpm) => formatOptional(bpm, 'bpm'))}`,
    )
    .with(
      { kind: 'CALORIES' },
      (f) => `calories: ${fromTo(f.from, f.to, (kcal) => formatOptional(kcal, 'kcal'))}`,
    )
    .exhaustive();
}

function describeDetails(details: DetailsUpgradeDto): string {
  return match(details)
    .with(
      { kind: 'BACKFILLED' },
      (d) => `Backfilled Activity Details (${parserText(d.parserVersion)})`,
    )
    .with(
      { kind: 'PARSER_VERSION_UPGRADED' },
      (d) => `Upgraded Activity Details (${parserText(d.from)} → v${d.to})`,
    )
    .with(
      { kind: 'DISTANCE_BACKFILLED' },
      (d) => `Backfilled Activity Details distance (${parserText(d.parserVersion)})`,
    )
    .exhaustive();
}

function describeUpgrade(details: DetailsUpgradeDto | null, laps: LapsUpgradeDto | null): string {
  if (details === null) {
    // Laps are only replaced together with Activity Details, so a Laps-only upgrade is a backfill. An upgrade
    // always has a part, so the bare `Upgraded` only satisfies the wire type.
    return laps === null ? 'Upgraded' : `Backfilled ${lapsText(laps.count)}`;
  }
  const detailsText = describeDetails(details);
  if (laps === null) return detailsText;
  const verb = details.kind === 'BACKFILLED' ? 'backfilled' : 'refreshed';
  return `${detailsText}; ${verb} ${lapsText(laps.count)}`;
}

function describeCreated(
  source: ActivitySource,
  parserVersion: number | null,
  lapCount: number,
): string {
  if (source === 'MANUAL') return 'Logged manually';
  const detail = [
    parserVersion === null ? null : parserText(parserVersion),
    lapCount > 0 ? lapsText(lapCount) : null,
  ].filter((part) => part !== null);
  const from = `Imported from ${IMPORT_SOURCE_LABELS[source]}`;
  return detail.length === 0 ? from : `${from} (${detail.join(', ')})`;
}

/**
 * What one change did, in English, e.g. `Imported from FIT file (Parser v1, 3 laps)`,
 * `title: A → B, distance: 5.00 km → 10.00 km` or `Upgraded Activity Details (Parser v1 → v2); refreshed 3 laps`.
 * Instants are shown in `timeZone` (the viewer's when omitted).
 */
export function describeActivityChange(change: ActivityChangeDto, timeZone?: string): string {
  return match(change)
    .with({ kind: 'CREATED' }, (c) => describeCreated(c.source, c.parserVersion, c.lapCount))
    .with({ kind: 'EDITED' }, { kind: 'BATCH_EDITED' }, (c) =>
      c.fields.map((field) => describeField(field, timeZone)).join(', '),
    )
    .with({ kind: 'UPGRADED' }, (c) => describeUpgrade(c.details, c.laps))
    .with({ kind: 'LEGACY' }, (c) => c.description)
    .exhaustive();
}

/**
 * What an **Import Batch Item**'s file upgraded: each change described, joined with `; `, or `Upgraded`
 * when the item recorded none.
 */
export function describeItemUpgrades(changes: readonly ActivityChangeDto[]): string {
  return changes.length === 0
    ? 'Upgraded'
    : changes.map((change) => describeActivityChange(change)).join('; ');
}

/** The kind of change, for its badge: a legacy entry keeps the type it was recorded under. */
export function activityChangeType(change: ActivityChangeDto): ActivityHistoryChangeType {
  return change.kind === 'LEGACY' ? change.changeType : change.kind;
}
