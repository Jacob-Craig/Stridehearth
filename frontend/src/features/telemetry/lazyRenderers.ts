/**
 * The uPlot and MapLibre adapters are loaded on demand, so their libraries stay out of the main bundle
 * until an Activity with **Activity Details** is opened. One load is shared by every chart.
 */

/** Shares one in-flight dynamic import between callers, forgetting a failed one so it can be retried. */
export function sharedImport<T>(load: () => Promise<T>): () => Promise<T> {
  let loading: Promise<T> | null = null;
  return () => {
    loading ??= load().catch((error: unknown) => {
      loading = null;
      throw error;
    });
    return loading;
  };
}

export const loadUplotChart = sharedImport(() => import('./uplotChart'));

export const loadRouteMap = sharedImport(() => import('./maplibreRouteMap'));
