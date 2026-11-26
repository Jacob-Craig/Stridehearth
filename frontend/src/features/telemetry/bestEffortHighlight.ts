import type { ColorScheme } from '../../lib/colorScheme';

/** The amber of a highlighted **Best Effort**: its route path, and its shaded band on every chart. */
export const BEST_EFFORT_HIGHLIGHT_COLOR: Readonly<Record<ColorScheme, string>> = {
  light: '#f59e0b',
  dark: '#fbbf24',
};

/** The teal of a **Detected Interval** that is work: its shaded band on every chart. */
export const WORK_INTERVAL_COLOR: Readonly<Record<ColorScheme, string>> = {
  light: '#0d9488',
  dark: '#2dd4bf',
};
