import { useEffect, useReducer, useState } from 'react';
import { match } from 'ts-pattern';
import {
  useBackfillBestEfforts,
  useBatchDeleteActivities,
  useBatchUpdateActivities,
} from '../../api/activityManagement';
import type {
  ActivitySummaryDto,
  BatchMutationResultDto,
  PagedResponseDtoActivitySummaryDto,
} from '../../api/generated/types.gen';
import { Pagination } from '../../components/ActivityList';
import { ErrorAlert } from '../../components/ErrorAlert';
import { formatCount } from '../../lib/format';
import { parseApiError } from '../../utils/error';
import { BatchActionDialog } from './BatchActionDialog';
import { ManageActivityTable } from './ManageActivityTable';
import { ManageFilterForm } from './ManageFilterForm';
import {
  type BatchDraft,
  type BatchMode,
  CLOSED,
  EMPTY_DRAFT,
  batchModalReducer,
} from './batchModalState';
import { backfillNotice, batchResultNotice } from './batchNotices';
import type { ManageFilters } from './manageSearch';
import { secondaryButton } from './manageStyles';

const NO_SELECTION: ReadonlySet<number> = new Set();

interface ManageActivitiesPageProps {
  readonly userId: number | undefined;
  readonly filters: ManageFilters;
  readonly activityPage: PagedResponseDtoActivitySummaryDto;
  readonly onFiltersChange: (filters: ManageFilters) => void;
}

/**
 * The bulk **Activity** management workspace: URL-synced filters, a selectable (and, for `size=all`,
 * virtualized) table, two-step batch modify / delete, and the **Best Efforts** backfill.
 */
export function ManageActivitiesPage({
  userId,
  filters,
  activityPage,
  onFiltersChange,
}: ManageActivitiesPageProps) {
  const items = activityPage.items;
  const [selection, setSelection] = useState(NO_SELECTION);
  const [modal, dispatch] = useReducer(batchModalReducer, CLOSED);
  const [draft, setDraft] = useState(EMPTY_DRAFT);
  const [notice, setNotice] = useState<string | null>(null);

  // Keep the selection a subset of the listed Activities whenever the list is refetched.
  const [selectionSource, setSelectionSource] = useState(items);
  if (selectionSource !== items) {
    setSelectionSource(items);
    const listed = new Set(items.map((a) => a.id));
    setSelection((current) => new Set([...current].filter((id) => listed.has(id))));
  }

  const batchUpdate = useBatchUpdateActivities(userId);
  const batchDelete = useBatchDeleteActivities(userId);
  const batchError = batchUpdate.error ?? batchDelete.error;
  const submitBatch = (mode: BatchMode, submitted: BatchDraft) => {
    const activityIds = [...selection];
    const callbacks = {
      onSuccess: (result: BatchMutationResultDto) => {
        dispatch({ type: 'succeeded' });
        setSelection(NO_SELECTION);
        setNotice(batchResultNotice(mode, result));
      },
      onError: () => dispatch({ type: 'failed', draft: submitted }),
    };
    match(mode)
      .with('modify', () =>
        batchUpdate.mutate(
          { activityIds, title: submitted.draftTitle, sport: submitted.draftSport },
          callbacks,
        ),
      )
      .with('delete', () => batchDelete.mutate(activityIds, callbacks))
      .exhaustive();
  };

  const backfill = useBackfillBestEfforts(userId);

  // A batch can empty a later page (deleted, or no longer matching the filters): step back to one with results.
  const pageEmptied = items.length === 0 && activityPage.hasPrevious;
  useEffect(() => {
    if (pageEmptied) onFiltersChange({ ...filters, page: Math.max(0, filters.page - 1) });
  }, [pageEmptied, filters, onFiltersChange]);

  const toggle = (activityId: number) =>
    setSelection((current) => {
      const next = new Set(current);
      if (!next.delete(activityId)) next.add(activityId);
      return next;
    });
  const toggleAll = () =>
    setSelection((current) =>
      current.size === items.length ? NO_SELECTION : new Set(items.map((a) => a.id)),
    );

  const openBatch = (mode: BatchMode) => {
    batchUpdate.reset();
    batchDelete.reset();
    setDraft(EMPTY_DRAFT);
    dispatch({ type: 'open', mode });
  };

  const selectedActivities: readonly ActivitySummaryDto[] =
    modal.step === 'closed' ? [] : items.filter((a) => selection.has(a.id));

  return (
    <div className="space-y-6">
      <header className="border-b border-slate-200 dark:border-slate-800 pb-4">
        <h1 className="text-2xl font-bold tracking-tight">Manage Activities</h1>
        <p className="mt-1 text-slate-600 dark:text-slate-400">
          Filter, select and update or delete many Activities at once.
        </p>
      </header>

      <ManageFilterForm
        key={JSON.stringify(filters)}
        filters={filters}
        onApply={(next) => {
          setNotice(null);
          onFiltersChange(next);
        }}
      />

      {notice && (
        <output className="block rounded-lg border border-blue-200 dark:border-blue-900 bg-blue-50 dark:bg-blue-950 p-3 text-sm text-blue-800 dark:text-blue-200">
          {notice}
        </output>
      )}
      {backfill.error && <ErrorAlert>{parseApiError(backfill.error).generalError}</ErrorAlert>}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3 text-sm text-slate-600 dark:text-slate-400">
          <span className="tabular-nums">
            {formatCount(activityPage.totalElements, 'activity', 'activities')}
          </span>
          <output aria-live="polite" className="tabular-nums font-medium">
            {formatCount(selection.size, 'selected', 'selected')}
          </output>
        </div>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            className={secondaryButton}
            disabled={selection.size === 0}
            onClick={() => openBatch('modify')}
          >
            Batch Modify
          </button>
          <button
            type="button"
            className={`${secondaryButton} text-red-600 dark:text-red-400`}
            disabled={selection.size === 0}
            onClick={() => openBatch('delete')}
          >
            Batch Delete
          </button>
          <button
            type="button"
            className={secondaryButton}
            disabled={backfill.isPending}
            onClick={() =>
              backfill.mutate(undefined, {
                onSuccess: (result) => setNotice(backfillNotice(result)),
              })
            }
          >
            {backfill.isPending ? 'Backfilling Best Efforts…' : 'Backfill Best Efforts'}
          </button>
        </div>
      </div>

      <ManageActivityTable
        activities={items}
        selection={selection}
        virtualized={filters.size === 'all'}
        onToggle={toggle}
        onToggleAll={toggleAll}
      />

      {filters.size !== 'all' && activityPage.totalElements > 0 && (
        <Pagination
          activityPage={activityPage}
          onPageChange={(page) => onFiltersChange({ ...filters, page })}
        />
      )}

      <BatchActionDialog
        state={modal}
        dispatch={dispatch}
        activities={selectedActivities}
        draft={draft}
        onDraftChange={setDraft}
        error={batchError ? parseApiError(batchError).generalError : null}
        onSubmit={(mode, submitted) => {
          dispatch({ type: 'submit' });
          submitBatch(mode, submitted);
        }}
      />
    </div>
  );
}
