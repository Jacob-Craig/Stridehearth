import { type ZipInspection, blobSource, inspectZip } from './zipInspector';

/** Message posted to `zipInspector.worker.ts`. `File`/`Blob` are structured-cloned without copying bytes. */
export interface ZipInspectionRequest {
  readonly id: number;
  readonly archive: Blob;
}

export interface ZipInspectionResponse {
  readonly id: number;
  readonly inspection: ZipInspection;
}

/** The worker's whole job, kept outside the worker scope so it can run in-thread under test. */
export async function handleZipInspectionRequest(
  request: ZipInspectionRequest,
): Promise<ZipInspectionResponse> {
  try {
    return { id: request.id, inspection: await inspectZip(blobSource(request.archive)) };
  } catch (error) {
    const reason = error instanceof Error ? error.message : 'Archive could not be read';
    return { id: request.id, inspection: { kind: 'corrupt', reason } };
  }
}
