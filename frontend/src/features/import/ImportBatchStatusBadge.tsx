import { match } from 'ts-pattern';
import type { ImportBatchStatus } from '../../api/importBatches';

interface StatusPresentation {
  readonly label: string;
  readonly className: string;
}

/** Exhaustive mapping of every **Import Batch** status to its badge, mirroring the Kotlin enum. */
export function importBatchStatusPresentation(status: ImportBatchStatus): StatusPresentation {
  return match(status)
    .returnType<StatusPresentation>()
    .with('PENDING', () => ({
      label: 'Pending',
      className: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200',
    }))
    .with('PROCESSING', () => ({
      label: 'Processing',
      className: 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-200',
    }))
    .with('COMPLETED', () => ({
      label: 'Completed',
      className: 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-200',
    }))
    .with('CANCELLED', () => ({
      label: 'Cancelled',
      className: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
    }))
    .with('INTERRUPTED', () => ({
      label: 'Interrupted',
      className: 'bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200',
    }))
    .with('FAILED', () => ({
      label: 'Failed',
      className: 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-200',
    }))
    .exhaustive();
}

export function ImportBatchStatusBadge({ status }: { status: ImportBatchStatus }) {
  const { label, className } = importBatchStatusPresentation(status);
  return (
    <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${className}`}>{label}</span>
  );
}
