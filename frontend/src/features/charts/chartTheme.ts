import type { ColorScheme } from '../../lib/colorScheme';

/** Axis labels and grid lines of every uPlot chart, per colour scheme. */
export const AXIS_PALETTE: Record<ColorScheme, { readonly axis: string; readonly grid: string }> = {
  light: { axis: '#475569', grid: '#e2e8f0' },
  dark: { axis: '#94a3b8', grid: '#1e293b' },
};
