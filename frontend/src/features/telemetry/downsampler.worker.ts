/**
 * Dedicated Vite module Web Worker that LTTB-downsamples the chart series of ultra-long Activities off
 * the main thread.
 */
import {
  type DownsampleRequest,
  type DownsampleResponse,
  handleDownsampleRequest,
} from './downsamplerProtocol';

interface DownsamplerWorkerScope {
  onmessage: ((event: MessageEvent<DownsampleRequest>) => void) | null;
  postMessage(response: DownsampleResponse): void;
}

const scope = self as unknown as DownsamplerWorkerScope;

scope.onmessage = (event) => {
  scope.postMessage(handleDownsampleRequest(event.data));
};
