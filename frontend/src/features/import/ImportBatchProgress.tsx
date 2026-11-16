import { Link } from '@tanstack/react-router';
import { match } from 'ts-pattern';
import type { ImportBatchSummaryDto } from '../../api/generated/types.gen';
import { useImportBatch } from '../../api/importBatches';
import { ErrorAlert } from '../../components/ErrorAlert';
import { parseApiError } from '../../utils/error';
import { ImportBatchItemOutcomes } from './ImportBatchItemOutcomes';
import { ImportBatchStatusBadge } from './ImportBatchStatusBadge';

function statusDescription(batch: ImportBatchSummaryDto): string {
  return match(batch.status)
    .with('PENDING', () => 'Waiting for an import worker to pick up your files…')
    .with('PROCESSING', () => 'Importing your activity files…')
    .with('COMPLETED', () => 'Every file has been processed.')
    .with(
      'CANCELLED',
      () => 'This import was cancelled. Files imported before cancelling were kept.',
    )
    .with('INTERRUPTED', () => 'This import was interrupted by a server restart.')
    .with('FAILED', () => batch.errorMessage ?? 'This import failed.')
    .exhaustive();
}

interface ImportBatchProgressProps {
  readonly userId: number | undefined;
  readonly batchId: number;
}

/** Live progress dashboard for one **Import Batch**, polled every second until it is terminal. */
export function ImportBatchProgress({ userId, batchId }: ImportBatchProgressProps) {
  const { batch, cancellation, terminal } = useImportBatch({ userId, batchId });

  if (batch.isPending) {
    return <p className="text-slate-600 dark:text-slate-400">Loading Import Batch…</p>;
  }
  if (batch.data === undefined) {
    return (
      <ErrorAlert title="This Import Batch could not be loaded">
        {parseApiError(batch.error).generalError}
      </ErrorAlert>
    );
  }

  const summary = batch.data;
  const counts: ReadonlyArray<readonly [string, number]> = [
    ['Imported', summary.successfulFiles],
    ['Parser Version upgrades', summary.upgradedFiles],
    ['Duplicates', summary.duplicateFiles],
    ['Failed files', summary.failedFiles],
  ];

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 dark:border-slate-800 pb-4">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight">Import Batch #{summary.id}</h1>
            <ImportBatchStatusBadge status={summary.status} />
          </div>
          <p className="mt-1 text-slate-600 dark:text-slate-400">{statusDescription(summary)}</p>
        </div>
        {!terminal && (
          <button
            type="button"
            onClick={() => cancellation.mutate()}
            disabled={cancellation.isPending}
            className="rounded-md border border-red-600 px-3 py-1.5 text-sm font-medium text-red-600 hover:bg-red-50 dark:hover:bg-red-950 disabled:opacity-50"
          >
            {cancellation.isPending ? 'Cancelling…' : 'Cancel import'}
          </button>
        )}
      </header>

      {batch.isError && (
        <p role="alert" className="text-sm text-amber-700 dark:text-amber-300">
          Lost contact with the server ({parseApiError(batch.error).generalError}). Reconnecting…
        </p>
      )}

      {cancellation.isError && (
        <div role="alert" className="text-sm text-red-700 dark:text-red-300">
          {parseApiError(cancellation.error).generalError}
        </div>
      )}

      <section aria-labelledby="progress-heading" className="space-y-2">
        <h2 id="progress-heading" className="sr-only">
          Progress
        </h2>
        <progress
          aria-label="Import progress"
          max={Math.max(summary.totalFiles, 1)}
          value={summary.processedFiles}
          className="h-3 w-full overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800 [&::-webkit-progress-bar]:bg-slate-200 dark:[&::-webkit-progress-bar]:bg-slate-800 [&::-webkit-progress-value]:bg-blue-600 [&::-moz-progress-bar]:bg-blue-600"
        />
        <p className="text-sm tabular-nums">
          {summary.processedFiles} / {summary.totalFiles} files processed
        </p>
      </section>

      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        {counts.map(([label, value]) => (
          <div
            key={label}
            className="rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4"
          >
            <dt className="text-sm text-slate-600 dark:text-slate-400">{label}</dt>
            <dd className="text-2xl font-bold tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>

      <ImportBatchItemOutcomes batch={summary} />

      {terminal && (
        <nav aria-label="Import actions" className="flex gap-3 text-sm font-medium">
          <Link to="/activities/import" search={{}} className="text-blue-600 hover:underline">
            Start another import
          </Link>
          <Link to="/activities/import/history" className="text-blue-600 hover:underline">
            View import history
          </Link>
        </nav>
      )}
    </div>
  );
}
