import * as Dialog from '@radix-ui/react-dialog';
import type { ActivityHistoryEntryDto } from '../../api/generated/types.gen';
import { activityChangeType, describeActivityChange } from '../../lib/activityChange';
import { formatDateTime } from '../../lib/format';
import type { ActivityHistoryChangeType } from '../../types/domain';
import { SECONDARY_BUTTON_CLASS } from './styles';

const CHANGE_TYPE_LABELS: Readonly<Record<ActivityHistoryChangeType, string>> = {
  CREATED: 'Created',
  EDITED: 'Edited',
  UPGRADED: 'Upgraded',
  BATCH_EDITED: 'Batch edited',
};

/** A button opening an accessible modal of the **Activity History Entries**, oldest first. */
export function ActivityHistoryDialog({
  history,
}: {
  readonly history: readonly ActivityHistoryEntryDto[];
}) {
  return (
    <Dialog.Root>
      <Dialog.Trigger className={SECONDARY_BUTTON_CLASS}>Activity History</Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-slate-950/50" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 flex max-h-[80vh] w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 flex-col rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-6 shadow-lg">
          <Dialog.Title className="text-lg font-bold">Activity History</Dialog.Title>
          <Dialog.Description className="mt-1 text-sm text-slate-600 dark:text-slate-400">
            When this Activity was created, edited, or upgraded by a newer Parser Version.
          </Dialog.Description>
          {history.length === 0 ? (
            <p className="mt-4 text-sm text-slate-500 dark:text-slate-400">
              No history recorded yet.
            </p>
          ) : (
            <ol className="mt-4 space-y-3 overflow-y-auto">
              {history.map((entry) => (
                <li
                  key={entry.id}
                  className="rounded-md border border-slate-200 dark:border-slate-800 p-3 text-sm"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="rounded-full bg-slate-100 dark:bg-slate-800 px-2.5 py-0.5 text-xs font-semibold">
                      {CHANGE_TYPE_LABELS[activityChangeType(entry.change)]}
                    </span>
                    <time
                      dateTime={entry.createdAt}
                      className="tabular-nums text-slate-500 dark:text-slate-400"
                    >
                      {formatDateTime(entry.createdAt)}
                    </time>
                  </div>
                  <p className="mt-2 break-words">{describeActivityChange(entry.change)}</p>
                </li>
              ))}
            </ol>
          )}
          <div className="mt-6 flex justify-end">
            <Dialog.Close className={`${SECONDARY_BUTTON_CLASS} text-sm font-medium`}>
              Close
            </Dialog.Close>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
