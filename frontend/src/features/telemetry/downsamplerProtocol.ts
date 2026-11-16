import { type XySeries, lttb } from './lttb';

/** Message posted to `downsampler.worker.ts`: series parallel to the elapsed-time stream `t`. */
export interface DownsampleRequest {
  readonly id: number;
  readonly t: readonly number[];
  readonly series: readonly (readonly (number | null)[])[];
  readonly targetPoints: number;
}

export interface DownsampleResponse {
  readonly id: number;
  /** One LTTB-downsampled `(elapsed seconds, value)` series per requested series, in order. */
  readonly series: XySeries[];
}

/** The worker's whole job, kept outside the worker scope so it can run in-thread under test. */
export function handleDownsampleRequest(request: DownsampleRequest): DownsampleResponse {
  return {
    id: request.id,
    series: request.series.map((values) => lttb(request.t, values, request.targetPoints)),
  };
}
