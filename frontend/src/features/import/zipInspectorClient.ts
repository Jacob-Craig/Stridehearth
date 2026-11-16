import type { ZipInspection } from './zipInspector';
import type { ZipInspectionRequest, ZipInspectionResponse } from './zipInspectorProtocol';

export interface ZipInspectorClient {
  inspect(archive: Blob): Promise<ZipInspection>;
  dispose(): void;
}

const spawnZipInspectorWorker = (): Worker =>
  new Worker(new URL('./zipInspector.worker.ts', import.meta.url), { type: 'module' });

/**
 * Promise facade over one lazily-spawned `zipInspector.worker.ts`, correlating concurrent
 * inspections by request id.
 */
export function createZipInspectorClient(
  spawnWorker: () => Worker = spawnZipInspectorWorker,
): ZipInspectorClient {
  let worker: Worker | null = null;
  let nextId = 0;
  const pending = new Map<number, (inspection: ZipInspection) => void>();

  const settleAll = (inspection: ZipInspection) => {
    for (const resolve of pending.values()) resolve(inspection);
    pending.clear();
  };

  const ensureWorker = (): Worker => {
    if (worker) return worker;
    const spawned = spawnWorker();
    spawned.onmessage = (event: MessageEvent<ZipInspectionResponse>) => {
      const resolve = pending.get(event.data.id);
      pending.delete(event.data.id);
      resolve?.(event.data.inspection);
    };
    spawned.onerror = () => settleAll({ kind: 'corrupt', reason: 'Archive inspector crashed' });
    worker = spawned;
    return spawned;
  };

  return {
    inspect(archive) {
      const id = nextId++;
      return new Promise((resolve) => {
        pending.set(id, resolve);
        const request: ZipInspectionRequest = { id, archive };
        ensureWorker().postMessage(request);
      });
    },
    dispose() {
      worker?.terminate();
      worker = null;
      pending.clear();
    },
  };
}
