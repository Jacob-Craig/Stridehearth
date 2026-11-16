import type { DownsampleRequest, DownsampleResponse } from './downsamplerProtocol';
import type { XySeries } from './lttb';

export interface DownsamplerClient {
  downsample(
    t: readonly number[],
    series: readonly (readonly (number | null)[])[],
    targetPoints: number,
  ): Promise<XySeries[]>;
  dispose(): void;
}

const spawnDownsamplerWorker = (): Worker =>
  new Worker(new URL('./downsampler.worker.ts', import.meta.url), { type: 'module' });

/**
 * Promise facade over one lazily-spawned `downsampler.worker.ts`, correlating concurrent requests by
 * id. Requests reject if the worker crashes or the client is disposed.
 */
export function createDownsamplerClient(
  spawnWorker: () => Worker = spawnDownsamplerWorker,
): DownsamplerClient {
  let worker: Worker | null = null;
  let nextId = 0;
  const pending = new Map<
    number,
    { resolve: (series: XySeries[]) => void; reject: (error: Error) => void }
  >();

  const ensureWorker = (): Worker => {
    if (worker) return worker;
    const spawned = spawnWorker();
    spawned.onmessage = (event: MessageEvent<DownsampleResponse>) => {
      const request = pending.get(event.data.id);
      pending.delete(event.data.id);
      request?.resolve(event.data.series);
    };
    spawned.onerror = () => {
      for (const request of pending.values()) request.reject(new Error('Downsampler crashed'));
      pending.clear();
    };
    worker = spawned;
    return spawned;
  };

  return {
    downsample(t, series, targetPoints) {
      const id = nextId++;
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        const request: DownsampleRequest = { id, t, series, targetPoints };
        ensureWorker().postMessage(request);
      });
    },
    dispose() {
      worker?.terminate();
      worker = null;
      for (const request of pending.values()) request.reject(new Error('Downsampler disposed'));
      pending.clear();
    },
  };
}
