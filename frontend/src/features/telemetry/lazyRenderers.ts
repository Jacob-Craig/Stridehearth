/**
 * The uPlot and MapLibre adapters are loaded on demand, so their libraries stay out of the main bundle
 * until an Activity with **Activity Details** is opened. One load is shared by every chart.
 */
import { sharedImport } from '../charts/lazyCharts';

export { sharedImport };

export const loadUplotChart = sharedImport(() => import('./uplotChart'));

export const loadRouteMap = sharedImport(() => import('./maplibreRouteMap'));
