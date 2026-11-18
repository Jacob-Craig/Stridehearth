import { sharedImport } from '../telemetry/lazyRenderers';

/** The progression chart's uPlot adapter, loaded on demand so uPlot stays out of the main bundle. */
export const loadProgressionChart = sharedImport(() => import('./progressionChart'));
