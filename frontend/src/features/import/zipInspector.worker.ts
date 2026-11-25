/**
 * Dedicated Vite module Web Worker that inspects `.zip` Central Directories off the main thread, so
 * dropping a multi-thousand-file export never blocks rendering.
 */
import {
  handleZipInspectionRequest,
  type ZipInspectionRequest,
  type ZipInspectionResponse,
} from './zipInspectorProtocol';

interface ZipInspectorWorkerScope {
  onmessage: ((event: MessageEvent<ZipInspectionRequest>) => void) | null;
  postMessage(response: ZipInspectionResponse): void;
}

const scope = self as unknown as ZipInspectorWorkerScope;

scope.onmessage = (event) => {
  void handleZipInspectionRequest(event.data).then((response) => scope.postMessage(response));
};
