import { Link } from '@tanstack/react-router';
import { useVirtualizer } from '@tanstack/react-virtual';
import { useEffect, useRef } from 'react';
import type { ActivitySummaryDto } from '../../api/generated/types.gen';
import {
  formatDate,
  formatDistanceKm,
  formatDuration,
  formatSource,
  formatSport,
} from '../../lib/format';

/** Estimated height of one table row in pixels; rows are single-line so this is near-exact. */
const ROW_HEIGHT_PX = 45;
const COLUMN_COUNT = 7;

interface ManageActivityTableProps {
  readonly activities: readonly ActivitySummaryDto[];
  /** Ids of the selected Activities; always a subset of [activities]. */
  readonly selection: ReadonlySet<number>;
  /** When true, only the rows in view are mounted (used for `size=all`). */
  readonly virtualized: boolean;
  readonly onToggle: (activityId: number) => void;
  readonly onToggleAll: () => void;
}

/** The selectable **Activity** table of the management workspace. */
export function ManageActivityTable(props: ManageActivityTableProps) {
  if (props.activities.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 p-8 text-center text-slate-500 dark:text-slate-400">
        No activities match these filters.
      </div>
    );
  }
  return props.virtualized ? <VirtualizedTable {...props} /> : <PlainTable {...props} />;
}

function PlainTable({ activities, selection, onToggle, onToggleAll }: ManageActivityTableProps) {
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shadow-sm [content-visibility:auto] [contain-intrinsic-size:auto_480px]">
      <table className="w-full text-sm">
        <TableHead activities={activities} selection={selection} onToggleAll={onToggleAll} />
        <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
          {activities.map((activity) => (
            <ActivityRow
              key={activity.id}
              activity={activity}
              selected={selection.has(activity.id)}
              onToggle={onToggle}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Mounts only the rows in (and just around) the viewport, so thousands of **Activities** keep a constant
 * DOM size. Spacer rows above and below preserve the scroll height and native table semantics.
 */
function VirtualizedTable({
  activities,
  selection,
  onToggle,
  onToggleAll,
}: ManageActivityTableProps) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: activities.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT_PX,
    overscan: 10,
    getItemKey: (index) => activities[index]?.id ?? index,
  });
  const rows = virtualizer.getVirtualItems();
  const paddingTop = rows[0]?.start ?? 0;
  const paddingBottom = virtualizer.getTotalSize() - (rows.at(-1)?.end ?? 0);

  return (
    <div
      ref={scrollRef}
      className="h-[70vh] min-h-80 overflow-auto rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shadow-sm [overflow-anchor:none]"
    >
      <table className="w-full text-sm">
        <TableHead activities={activities} selection={selection} onToggleAll={onToggleAll} sticky />
        <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
          {paddingTop > 0 && <SpacerRow height={paddingTop} />}
          {rows.map((row) => {
            const activity = activities[row.index];
            return activity ? (
              <ActivityRow
                key={row.key}
                activity={activity}
                selected={selection.has(activity.id)}
                onToggle={onToggle}
              />
            ) : null;
          })}
          {paddingBottom > 0 && <SpacerRow height={paddingBottom} />}
        </tbody>
      </table>
    </div>
  );
}

function SpacerRow({ height }: { height: number }) {
  return (
    <tr>
      <td colSpan={COLUMN_COUNT} style={{ height, padding: 0 }} />
    </tr>
  );
}

function TableHead({
  activities,
  selection,
  onToggleAll,
  sticky = false,
}: Pick<ManageActivityTableProps, 'activities' | 'selection' | 'onToggleAll'> & {
  sticky?: boolean;
}) {
  // The selection only ever holds listed Activities, so comparing sizes is exact and O(1).
  const allSelected = activities.length > 0 && selection.size === activities.length;
  const someSelected = !allSelected && selection.size > 0;
  const checkboxRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (checkboxRef.current) checkboxRef.current.indeterminate = someSelected;
  }, [someSelected]);

  return (
    <thead
      className={`bg-slate-50 dark:bg-slate-800 text-left text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400 ${sticky ? 'sticky top-0 z-10' : ''}`}
    >
      <tr>
        <th scope="col" className="w-10 px-4 py-3">
          <input
            ref={checkboxRef}
            type="checkbox"
            aria-label="Select all activities"
            checked={allSelected}
            onChange={onToggleAll}
          />
        </th>
        <th scope="col" className="px-4 py-3">
          Title
        </th>
        <th scope="col" className="px-4 py-3">
          Sport
        </th>
        <th scope="col" className="px-4 py-3">
          Source
        </th>
        <th scope="col" className="px-4 py-3">
          Date
        </th>
        <th scope="col" className="px-4 py-3 text-right">
          Distance
        </th>
        <th scope="col" className="px-4 py-3 text-right">
          Duration
        </th>
      </tr>
    </thead>
  );
}

function ActivityRow({
  activity,
  selected,
  onToggle,
}: {
  activity: ActivitySummaryDto;
  selected: boolean;
  onToggle: (activityId: number) => void;
}) {
  return (
    <tr
      style={{ height: ROW_HEIGHT_PX }}
      className={
        selected ? 'bg-blue-50 dark:bg-blue-950/40' : 'hover:bg-slate-50 dark:hover:bg-slate-800/40'
      }
    >
      <td className="px-4 py-2">
        <input
          type="checkbox"
          aria-label={`Select ${activity.title}`}
          checked={selected}
          onChange={() => onToggle(activity.id)}
        />
      </td>
      <td className="px-4 py-2 font-medium">
        <Link
          to="/activities/$activityId"
          params={{ activityId: String(activity.id) }}
          className="hover:underline"
        >
          {activity.title}
        </Link>
      </td>
      <td className="px-4 py-2">{formatSport(activity.sport)}</td>
      <td className="px-4 py-2">{formatSource(activity.source)}</td>
      <td className="px-4 py-2">
        <time dateTime={activity.startedAt} className="tabular-nums">
          {formatDate(activity.startedAt)}
        </time>
      </td>
      <td className="px-4 py-2 text-right tabular-nums">{formatDistanceKm(activity.distance)}</td>
      <td className="px-4 py-2 text-right tabular-nums">
        {formatDuration(activity.timeTakenSeconds)}
      </td>
    </tr>
  );
}
