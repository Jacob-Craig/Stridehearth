import { useState } from 'react';
import { useUploadActivityFile } from '../api/activities';
import type { SingleFileUploadResponseDto } from '../api/generated/types.gen';
import { parseApiError } from '../utils/error';
import { ErrorAlert } from './ErrorAlert';

interface FitUploadFormProps {
  userId: number | undefined;
  onUploaded: (result: SingleFileUploadResponseDto) => Promise<void> | void;
}

/** Imports a single `.fit` file; an unreadable file (`INVALID_ACTIVITY_FILE`) is reported inline. */
export function FitUploadForm({ userId, onUploaded }: FitUploadFormProps) {
  const [file, setFile] = useState<File | null>(null);
  const upload = useUploadActivityFile(userId, onUploaded);

  return (
    <form
      aria-labelledby="fit-upload-heading"
      onSubmit={(e) => {
        e.preventDefault();
        if (file) upload.mutate(file);
      }}
      className="space-y-3"
    >
      <h2 id="fit-upload-heading" className="text-lg font-bold">
        Upload a .fit file
      </h2>
      <p className="text-sm text-slate-600 dark:text-slate-400">
        Import a recorded activity, including its Activity Details and Laps. For many files at once,
        use Bulk Import.
      </p>
      {upload.error && <ErrorAlert>{parseApiError(upload.error).generalError}</ErrorAlert>}
      <div>
        <label
          htmlFor="fit-file"
          className="mb-1 block text-sm font-medium text-slate-700 dark:text-slate-300"
        >
          Activity .fit file
        </label>
        <input
          id="fit-file"
          name="file"
          type="file"
          accept=".fit"
          onChange={(e) => {
            upload.reset();
            setFile(e.target.files?.[0] ?? null);
          }}
          className="block w-full text-sm file:mr-3 file:rounded-md file:border-0 file:bg-slate-100 dark:file:bg-slate-800 file:px-3 file:py-1.5 file:font-medium"
        />
      </div>
      <button
        type="submit"
        disabled={!file || upload.isPending}
        className="rounded-lg border border-blue-600 px-4 py-2 font-medium text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950 disabled:opacity-50"
      >
        {upload.isPending ? 'Uploading…' : 'Upload'}
      </button>
    </form>
  );
}
