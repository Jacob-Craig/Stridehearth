import * as Dialog from '@radix-ui/react-dialog';
import { useVirtualizer } from '@tanstack/react-virtual';
import { type FormEvent, useRef, useState } from 'react';
import { match } from 'ts-pattern';
import type { ActivitySummaryDto } from '../../api/generated/types.gen';
import { ErrorAlert } from '../../components/ErrorAlert';
import { TITLE_MAX_LENGTH } from '../../lib/activityForm';
import { ACTIVITY_SPORTS, type ActivitySport, formatCount, formatSport } from '../../lib/format';
import {
  type BatchDraft,
  type BatchModalEvent,
  type BatchModalState,
  type BatchMode,
  EMPTY_DRAFT,
} from './batchModalState';
import { fieldClass, secondaryButton } from './manageStyles';

/** Height of one affected-Activity row in the confirmation list, in pixels. */
const AFFECTED_ROW_HEIGHT_PX = 32;

interface BatchActionDialogProps {
  readonly state: BatchModalState;
  readonly dispatch: (event: BatchModalEvent) => void;
  /** The selected **Activities** the batch applies to. */
  readonly activities: readonly ActivitySummaryDto[];
  readonly draft: BatchDraft;
  readonly onDraftChange: (draft: BatchDraft) => void;
  /** Why the last submission failed, shown on the confirmation step. */
  readonly error: string | null;
  readonly onSubmit: (mode: BatchMode, draft: BatchDraft) => void;
}

/** Everything that differs between batch modify and batch delete, in one place. */
const MODE_COPY: Readonly<
  Record<
    BatchMode,
    {
      readonly primaryButton: string;
      readonly confirmTitle: string;
      readonly confirmLabel: (count: string) => string;
      readonly submittingTitle: (count: string) => string;
    }
  >
> = {
  modify: {
    primaryButton:
      'rounded-md bg-blue-600 px-3 py-1.5 text-white hover:bg-blue-700 disabled:opacity-50',
    confirmTitle: 'Confirm batch update',
    confirmLabel: (count) => `Yes, update ${count}`,
    submittingTitle: (count) => `Updating ${count}…`,
  },
  delete: {
    primaryButton:
      'rounded-md bg-red-600 px-3 py-1.5 text-white hover:bg-red-700 disabled:opacity-50',
    confirmTitle: 'Confirm batch delete',
    confirmLabel: (count) => `Yes, permanently delete ${count}`,
    submittingTitle: (count) => `Deleting ${count}…`,
  },
};

/** The two-step batch modify / batch delete confirmation dialog. */
export function BatchActionDialog(props: BatchActionDialogProps) {
  const { state, dispatch } = props;
  return (
    <Dialog.Root
      open={state.step !== 'closed'}
      onOpenChange={(open) => {
        if (!open) dispatch({ type: 'dismiss' });
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-slate-950/50" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 flex max-h-[85vh] w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 flex-col rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-6 shadow-lg">
          {match(state)
            .with({ step: 'closed' }, () => null)
            .with({ step: 'configure', mode: 'modify' }, () => <ConfigureModify {...props} />)
            .with({ step: 'configure', mode: 'delete' }, () => <ConfigureDelete {...props} />)
            .with({ step: 'confirm' }, (confirm) => (
              <Confirm {...props} mode={confirm.mode} draft={confirm} />
            ))
            .with({ step: 'submitting' }, ({ mode }) => (
              <Submitting mode={mode} count={props.activities.length} />
            ))
            .exhaustive()}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function ConfigureModify({ activities, draft, onDraftChange, dispatch }: BatchActionDialogProps) {
  const [showError, setShowError] = useState(false);
  const review = (event: FormEvent) => {
    event.preventDefault();
    const draftTitle = draft.draftTitle.trim();
    if (!draftTitle && draft.draftSport === null) {
      setShowError(true);
      return;
    }
    dispatch({ type: 'review', draft: { ...draft, draftTitle } });
  };
  return (
    <form onSubmit={review} className="space-y-4" noValidate>
      <Dialog.Title className="text-lg font-bold">
        Batch modify {formatCount(activities.length, 'activity', 'activities')}
      </Dialog.Title>
      <Dialog.Description className="text-sm text-slate-600 dark:text-slate-400">
        Step 1 of 2: choose a new title and/or sport. Leave a field blank to keep each Activity's
        current value.
      </Dialog.Description>
      <label className="block text-sm font-medium">
        New title
        <input
          type="text"
          maxLength={TITLE_MAX_LENGTH}
          value={draft.draftTitle}
          onChange={(e) => onDraftChange({ ...draft, draftTitle: e.target.value })}
          className={fieldClass}
        />
      </label>
      <label className="block text-sm font-medium">
        New sport
        <select
          value={draft.draftSport ?? ''}
          onChange={(e) =>
            onDraftChange({
              ...draft,
              draftSport: e.target.value === '' ? null : (e.target.value as ActivitySport),
            })
          }
          className={fieldClass}
        >
          <option value="">Keep current sport</option>
          {ACTIVITY_SPORTS.map((sport) => (
            <option key={sport} value={sport}>
              {formatSport(sport)}
            </option>
          ))}
        </select>
      </label>
      {showError && <ErrorAlert>Please specify a new title or sport to apply.</ErrorAlert>}
      <div className="flex justify-end gap-2 text-sm font-medium">
        <Dialog.Close className={secondaryButton}>Cancel</Dialog.Close>
        <button type="submit" className={MODE_COPY.modify.primaryButton}>
          Review changes
        </button>
      </div>
    </form>
  );
}

function ConfigureDelete({ activities, dispatch }: BatchActionDialogProps) {
  return (
    <div className="space-y-4">
      <Dialog.Title className="text-lg font-bold">
        Batch delete {formatCount(activities.length, 'activity', 'activities')}
      </Dialog.Title>
      <Dialog.Description className="text-sm text-slate-600 dark:text-slate-400">
        Step 1 of 2: the selected Activities, with their Laps, Activity Details and history, will be
        permanently deleted. Any Personal Records they hold will be recalculated.
      </Dialog.Description>
      <div className="flex justify-end gap-2 text-sm font-medium">
        <Dialog.Close className={secondaryButton}>Cancel</Dialog.Close>
        <button
          type="button"
          className={MODE_COPY.delete.primaryButton}
          onClick={() => dispatch({ type: 'review', draft: EMPTY_DRAFT })}
        >
          Continue
        </button>
      </div>
    </div>
  );
}

function Confirm({
  mode,
  draft,
  activities,
  error,
  dispatch,
  onSubmit,
}: BatchActionDialogProps & { mode: BatchMode }) {
  const count = formatCount(activities.length, 'activity', 'activities');
  return (
    <div className="flex min-h-0 flex-col gap-4">
      <Dialog.Title className="text-lg font-bold">{MODE_COPY[mode].confirmTitle}</Dialog.Title>
      <Dialog.Description asChild>
        <div className="text-sm text-slate-600 dark:text-slate-400">
          {mode === 'modify' ? (
            <>
              <p>Step 2 of 2: these changes will be applied to {count}:</p>
              <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 text-slate-900 dark:text-slate-100">
                <dt className="font-medium">Title</dt>
                <dd>{draft.draftTitle || 'Unchanged'}</dd>
                <dt className="font-medium">Sport</dt>
                <dd>{draft.draftSport ? formatSport(draft.draftSport) : 'Unchanged'}</dd>
              </dl>
            </>
          ) : (
            <p>
              Step 2 of 2: these {count} will be permanently deleted. This cannot be undone, and
              Personal Records will be recalculated.
            </p>
          )}
        </div>
      </Dialog.Description>
      <AffectedActivitiesList activities={activities} />
      {error && <ErrorAlert>{error}</ErrorAlert>}
      <div className="flex justify-end gap-2 text-sm font-medium">
        <button
          type="button"
          className={secondaryButton}
          onClick={() => dispatch({ type: 'back' })}
        >
          Back
        </button>
        <button
          type="button"
          className={MODE_COPY[mode].primaryButton}
          onClick={() => onSubmit(mode, draft)}
        >
          {MODE_COPY[mode].confirmLabel(count)}
        </button>
      </div>
    </div>
  );
}

function Submitting({ mode, count }: { mode: BatchMode; count: number }) {
  return (
    <div className="space-y-2">
      <Dialog.Title className="text-lg font-bold">
        {MODE_COPY[mode].submittingTitle(formatCount(count, 'activity', 'activities'))}
      </Dialog.Title>
      <Dialog.Description className="text-sm text-slate-600 dark:text-slate-400">
        Please wait while the batch is applied.
      </Dialog.Description>
    </div>
  );
}

/**
 * Every affected **Activity**, in a scrollable list that mounts only the rows in view so a `size=all`
 * selection of thousands stays cheap. Each row states its position so assistive tech knows the full count.
 */
function AffectedActivitiesList({
  activities,
}: {
  activities: readonly ActivitySummaryDto[];
}) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: activities.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => AFFECTED_ROW_HEIGHT_PX,
    overscan: 10,
    getItemKey: (index) => activities[index]?.id ?? index,
  });
  return (
    <div
      ref={scrollRef}
      className="min-h-0 flex-1 overflow-y-auto rounded-md border border-slate-200 dark:border-slate-800 text-sm"
    >
      <ul
        aria-label="Affected activities"
        className="relative"
        style={{ height: virtualizer.getTotalSize() }}
      >
        {virtualizer.getVirtualItems().map((row) => (
          <li
            key={row.key}
            aria-setsize={activities.length}
            aria-posinset={row.index + 1}
            className="absolute inset-x-0 top-0 truncate border-b border-slate-200 dark:border-slate-800 px-3 leading-8"
            style={{ height: AFFECTED_ROW_HEIGHT_PX, transform: `translateY(${row.start}px)` }}
          >
            {activities[row.index]?.title}
          </li>
        ))}
      </ul>
    </div>
  );
}
