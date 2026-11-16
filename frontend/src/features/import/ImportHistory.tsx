import { Link } from '@tanstack/react-router';
import type { ImportBatchSummaryDto } from '../../api/generated/types.gen';
import { formatDate, formatDuration } from '../../lib/format';
import { ImportBatchItemOutcomes } from './ImportBatchItemOutcomes';
import { ImportBatchStatusBadge } from './ImportBatchStatusBadge';

const MISSING = '—';

/** Wall-clock seconds an **Import Batch** took, or `null` while it is still running. */
export function importBatchElapsedSeconds(batch: ImportBatchSummaryDto): number | null {
  if (batch.completedAt === null) return null;
  const elapsedMs = Date.parse(batch.completedAt) - Date.parse(batch.createdAt);
  return Number.isFinite(elapsedMs) ? Math.max(0, elapsedMs / 1000) : null;
}

interface ImportHistoryProps {
  readonly batches: readonly ImportBatchSummaryDto[];
}

/** Audit table of the athlete's past **Import Batches**, newest first. */
export function ImportHistory({ batches }: ImportHistoryProps) {
  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 dark:border-slate-800 pb-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Import History</h1>
          <p className="mt-1 text-slate-600 dark:text-slate-400">
            Every Import Batch you have submitted, with per-file errors and Parser Version upgrades.
          </p>
        </div>
        <Link
          to="/activities/import"
          search={{}}
          className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
        >
          New import
        </Link>
      </header>

      {batches.length === 0 ? (
        <div className="rounded-lg border border-dashed border-slate-300 dark:border-slate-700 p-8 text-center">
          <p className="font-medium">No imports yet.</p>
          <Link
            to="/activities/import"
            search={{}}
            className="mt-2 inline-block text-blue-600 hover:underline"
          >
            Start an import
          </Link>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 [content-visibility:auto] [contain-intrinsic-size:auto_480px]">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Import history</caption>
            <thead className="bg-slate-50 dark:bg-slate-800/50 text-slate-600 dark:text-slate-400">
              <tr>
                <th scope="col" className="px-3 py-2 font-medium">
                  Started
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Status
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Processed
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Imported
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Upgrades
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Duplicates
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Failed
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Duration
                </th>
                <th scope="col" className="px-3 py-2 font-medium">
                  Details
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
              {batches.map((batch) => (
                <ImportHistoryRow key={batch.id} batch={batch} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function ImportHistoryRow({ batch }: { batch: ImportBatchSummaryDto }) {
  const elapsed = importBatchElapsedSeconds(batch);
  const hasDetails =
    batch.errorMessage !== null || batch.itemErrors.length > 0 || batch.itemUpgrades.length > 0;

  return (
    <tr className="align-top">
      <td className="px-3 py-2">
        <time dateTime={batch.createdAt}>{formatDate(batch.createdAt)}</time>
      </td>
      <td className="px-3 py-2">
        <ImportBatchStatusBadge status={batch.status} />
      </td>
      <td className="px-3 py-2 tabular-nums">
        {batch.processedFiles} / {batch.totalFiles}
      </td>
      <td className="px-3 py-2 tabular-nums">{batch.successfulFiles}</td>
      <td className="px-3 py-2 tabular-nums">{batch.upgradedFiles}</td>
      <td className="px-3 py-2 tabular-nums">{batch.duplicateFiles}</td>
      <td className="px-3 py-2 tabular-nums">{batch.failedFiles}</td>
      <td className="px-3 py-2 tabular-nums">
        {elapsed === null ? MISSING : formatDuration(elapsed)}
      </td>
      <td className="px-3 py-2 space-y-1">
        {batch.errorMessage !== null && (
          <p className="text-sm text-red-700 dark:text-red-300">{batch.errorMessage}</p>
        )}
        <ImportBatchItemOutcomes batch={batch} />
        {!hasDetails && <span className="text-slate-500">No issues</span>}
      </td>
    </tr>
  );
}
