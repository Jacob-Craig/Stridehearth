/**
 * Chart and map adapters are loaded on demand, so their libraries stay out of the main bundle until a view
 * that draws one is opened. One load is shared by every chart of a kind.
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

/** The **Performance Management Chart** adapter, with uPlot. */
export const loadPmcChart = sharedImport(() => import('../pmc/pmcChart'));

/** The **Pace-Duration Curve** adapter, with uPlot. */
export const loadPaceDurationChart = sharedImport(
  () => import('../pace-duration/paceDurationChart'),
);

/** The **Race Prognosis** trend adapter, with uPlot. */
export const loadPrognosisChart = sharedImport(() => import('../race-prognosis/prognosisChart'));

/** The **Race Split Plan** elevation profile adapter, with uPlot. */
export const loadElevationProfileChart = sharedImport(
  () => import('../race-split-plan/elevationProfileChart'),
);
