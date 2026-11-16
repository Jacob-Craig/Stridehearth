/** Whether a file or archive entry name carries the `.fit` extension, ignoring case. */
export function hasFitExtension(name: string): boolean {
  return name.toLowerCase().endsWith('.fit');
}

/** The `.fit` files a picked file or archive contributes to an **Import Batch**, known before upload. */
export interface FitTally {
  readonly fitFiles: number;
  /** Their combined uncompressed size, or `null` when any `.fit` file's size could not be read. */
  readonly uncompressedBytes: number | null;
}

export const NO_FIT_FILES: FitTally = { fitFiles: 0, uncompressedBytes: 0 };

export function addFitTallies(left: FitTally, right: FitTally): FitTally {
  return {
    fitFiles: left.fitFiles + right.fitFiles,
    uncompressedBytes:
      left.uncompressedBytes === null || right.uncompressedBytes === null
        ? null
        : left.uncompressedBytes + right.uncompressedBytes,
  };
}
