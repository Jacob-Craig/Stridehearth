import { useMutation } from '@tanstack/react-query';
import { type DragEvent, useEffect, useRef, useState } from 'react';
import { match } from 'ts-pattern';
import type { ImportBatchReceiptDto } from '../../api/generated/types.gen';
import { submitImportBatch } from '../../api/importBatches';
import { ErrorAlert } from '../../components/ErrorAlert';
import { parseApiError } from '../../utils/error';
import { countOf } from './countOf';
import { type FitTally, NO_FIT_FILES, addFitTallies, hasFitExtension } from './fitTally';
import type { ZipInspection } from './zipInspector';
import { type ZipInspectorClient, createZipInspectorClient } from './zipInspectorClient';

/** What one picked file will contribute to the **Import Batch**, known before anything is uploaded. */
type FilePreview =
  | { readonly kind: 'fit' }
  | { readonly kind: 'inspecting' }
  | { readonly kind: 'archive'; readonly tally: FitTally }
  | { readonly kind: 'corrupt'; readonly reason: string }
  | { readonly kind: 'unsupported' };

interface SelectedFile {
  readonly key: string;
  readonly file: File;
  readonly preview: FilePreview;
}

const isZip = (file: File) => file.name.toLowerCase().endsWith('.zip');

function initialPreview(file: File): FilePreview {
  if (hasFitExtension(file.name)) return { kind: 'fit' };
  if (isZip(file)) return { kind: 'inspecting' };
  return { kind: 'unsupported' };
}

function previewFromInspection(inspection: ZipInspection): FilePreview {
  return match(inspection)
    .returnType<FilePreview>()
    .with({ kind: 'inspected' }, ({ tally }) => ({ kind: 'archive', tally }))
    .with({ kind: 'corrupt' }, ({ reason }) => ({ kind: 'corrupt', reason }))
    .exhaustive();
}

/** The `.fit` files this file contributes and the bytes the server must stage for them. */
function tallyOf({ file, preview }: SelectedFile): FitTally {
  return match(preview)
    .with({ kind: 'fit' }, () => ({ fitFiles: 1, uncompressedBytes: file.size }))
    .with({ kind: 'archive' }, ({ tally }) => tally)
    .with({ kind: 'inspecting' }, { kind: 'corrupt' }, { kind: 'unsupported' }, () => NO_FIT_FILES)
    .exhaustive();
}

const fitFiles = (count: number) => countOf(count, '.fit file');

function describePreview(preview: FilePreview): string {
  return match(preview)
    .with({ kind: 'fit' }, () => fitFiles(1))
    .with({ kind: 'inspecting' }, () => 'Inspecting archive…')
    .with({ kind: 'archive', tally: { fitFiles: 0 } }, () => 'No .fit files found')
    .with({ kind: 'archive' }, ({ tally }) => fitFiles(tally.fitFiles))
    .with({ kind: 'corrupt' }, ({ reason }) => `Unreadable archive — ${reason}`)
    .with({ kind: 'unsupported' }, () => 'Skipped — not a .fit or .zip file')
    .exhaustive();
}

const byteFormat = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 1 });

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${byteFormat.format(bytes / 1024)} KB`;
  return `${byteFormat.format(bytes / 1024 ** 2)} MB`;
}

/** Picks files and previews their `.fit` counts, inspecting `.zip` archives in a Web Worker. */
function useFileSelection() {
  const [selection, setSelection] = useState<readonly SelectedFile[]>([]);
  const inspector = useRef<ZipInspectorClient | null>(null);
  const generation = useRef(0);

  useEffect(
    () => () => {
      inspector.current?.dispose();
      inspector.current = null;
    },
    [],
  );

  const select = (files: readonly File[]) => {
    const current = ++generation.current;
    const selected = files.map((file, index) => ({
      key: `${current}-${index}-${file.name}`,
      file,
      preview: initialPreview(file),
    }));
    setSelection(selected);

    for (const entry of selected) {
      if (entry.preview.kind !== 'inspecting') continue;
      inspector.current ??= createZipInspectorClient();
      void inspector.current.inspect(entry.file).then((inspection) => {
        if (generation.current !== current) return;
        setSelection((previous) =>
          previous.map((candidate) =>
            candidate.key === entry.key
              ? { ...candidate, preview: previewFromInspection(inspection) }
              : candidate,
          ),
        );
      });
    }
  };

  const clear = () => {
    generation.current++;
    setSelection([]);
  };

  return { selection, select, clear };
}

interface ImportBatchUploaderProps {
  readonly onSubmitted: (receipt: ImportBatchReceiptDto) => void;
}

export function ImportBatchUploader({ onSubmitted }: ImportBatchUploaderProps) {
  const { selection, select, clear } = useFileSelection();
  const [dragging, setDragging] = useState(false);
  const submission = useMutation({ mutationFn: submitImportBatch, onSuccess: onSubmitted });

  const tallied = selection.map((entry) => ({ entry, tally: tallyOf(entry) }));
  const total = tallied.reduce((sum, { tally }) => addFitTallies(sum, tally), NO_FIT_FILES);
  const inspecting = selection.some((entry) => entry.preview.kind === 'inspecting');
  const uploadable = tallied.filter(({ tally }) => tally.fitFiles > 0).map(({ entry }) => entry);
  const canSubmit = total.fitFiles > 0 && !inspecting && !submission.isPending;

  const onDragOver = (event: DragEvent<HTMLElement>) => {
    event.preventDefault();
    setDragging(true);
  };
  const onDrop = (event: DragEvent<HTMLElement>) => {
    event.preventDefault();
    setDragging(false);
    select(Array.from(event.dataTransfer.files));
  };

  return (
    <div className="space-y-6">
      <header className="border-b border-slate-200 dark:border-slate-800 pb-4">
        <h1 className="text-2xl font-bold tracking-tight">Import Activities</h1>
        <p className="mt-1 text-slate-600 dark:text-slate-400">
          Upload many <code>.fit</code> files or <code>.zip</code> exports at once. Files are
          imported in the background as an Import Batch.
        </p>
      </header>

      <section
        aria-label="File drop zone"
        onDragOver={onDragOver}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={`rounded-lg border-2 border-dashed p-8 text-center transition ${
          dragging
            ? 'border-blue-500 bg-blue-50 dark:bg-blue-950'
            : 'border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900'
        }`}
      >
        <p className="font-medium">Drag and drop .fit or .zip files here</p>
        <p className="my-2 text-sm text-slate-500">or</p>
        <label className="inline-block cursor-pointer rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 focus-within:ring-2 focus-within:ring-blue-400 focus-within:ring-offset-2">
          Choose .fit or .zip files
          <input
            type="file"
            multiple
            accept=".fit,.zip"
            className="sr-only"
            onChange={(event) => select(Array.from(event.target.files ?? []))}
          />
        </label>
      </section>

      {selection.length > 0 && (
        <section aria-labelledby="selected-files-heading" className="space-y-3">
          <div className="flex items-center justify-between">
            <h2 id="selected-files-heading" className="text-lg font-bold">
              Selected files
            </h2>
            <button
              type="button"
              onClick={clear}
              className="text-sm text-slate-600 dark:text-slate-400 hover:underline"
            >
              Clear selection
            </button>
          </div>
          <ul
            aria-label="Selected files"
            className="divide-y divide-slate-200 dark:divide-slate-800 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900"
          >
            {selection.map((entry) => (
              <li key={entry.key} className="flex items-center justify-between gap-4 px-3 py-2">
                <span className="truncate">{entry.file.name}</span>
                <span className="flex shrink-0 items-center gap-3 text-sm">
                  <span className="tabular-nums text-slate-500">
                    {formatBytes(entry.file.size)}
                  </span>
                  <span
                    className={
                      entry.preview.kind === 'corrupt'
                        ? 'text-red-700 dark:text-red-300'
                        : 'text-slate-700 dark:text-slate-300'
                    }
                  >
                    {describePreview(entry.preview)}
                  </span>
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="flex flex-wrap items-center justify-between gap-4">
        <output aria-live="polite" className="text-sm">
          {inspecting ? (
            'Inspecting archives…'
          ) : (
            <>
              <strong className="tabular-nums">{fitFiles(total.fitFiles)}</strong> ready to import
              {total.fitFiles > 0 && (
                <span className="tabular-nums text-slate-500">
                  {' '}
                  (
                  {total.uncompressedBytes === null
                    ? 'uncompressed size unknown'
                    : `${formatBytes(total.uncompressedBytes)} uncompressed`}
                  )
                </span>
              )}
            </>
          )}
        </output>
        <button
          type="button"
          disabled={!canSubmit}
          onClick={() => submission.mutate(uploadable.map((entry) => entry.file))}
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submission.isPending ? 'Uploading…' : 'Start import'}
        </button>
      </div>

      {submission.isError && (
        <ErrorAlert>{parseApiError(submission.error).generalError}</ErrorAlert>
      )}
    </div>
  );
}
