import * as AlertDialog from '@radix-ui/react-alert-dialog';
import type { ActivitySummaryDto } from '../api/generated/types.gen';
import { ErrorAlert } from './ErrorAlert';

interface DeleteActivityDialogProps {
  /** The **Activity** awaiting confirmation; the dialog is open while this is non-null. */
  activity: ActivitySummaryDto | null;
  isDeleting: boolean;
  error: string | null;
  onConfirm: (activity: ActivitySummaryDto) => void;
  onCancel: () => void;
}

export function DeleteActivityDialog({
  activity,
  isDeleting,
  error,
  onConfirm,
  onCancel,
}: DeleteActivityDialogProps) {
  return (
    <AlertDialog.Root
      open={activity !== null}
      onOpenChange={(open) => {
        if (!open) onCancel();
      }}
    >
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="fixed inset-0 z-50 bg-slate-950/50" />
        <AlertDialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-6 shadow-lg">
          <AlertDialog.Title className="text-lg font-bold">Delete activity?</AlertDialog.Title>
          <AlertDialog.Description className="mt-2 text-sm text-slate-600 dark:text-slate-400">
            <strong className="font-semibold text-slate-900 dark:text-slate-100">
              {activity?.title}
            </strong>{' '}
            will be permanently deleted, and any Personal Records it holds will be recalculated.
          </AlertDialog.Description>
          {error && (
            <div className="mt-3">
              <ErrorAlert>{error}</ErrorAlert>
            </div>
          )}
          <div className="mt-6 flex justify-end gap-2 text-sm font-medium">
            <AlertDialog.Cancel
              disabled={isDeleting}
              className="rounded-md border border-slate-300 dark:border-slate-700 px-3 py-1.5 hover:bg-slate-100 dark:hover:bg-slate-800"
            >
              Cancel
            </AlertDialog.Cancel>
            <button
              type="button"
              disabled={isDeleting}
              onClick={() => activity && onConfirm(activity)}
              className="rounded-md bg-red-600 px-3 py-1.5 text-white hover:bg-red-700 disabled:opacity-50"
            >
              {isDeleting ? 'Deleting…' : 'Delete'}
            </button>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  );
}
