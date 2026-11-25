/**
 * The colours of the **Performance Management Chart**, per colour scheme, shared by the uPlot adapter and the
 * panel's key so a line and its swatch cannot drift apart. Every band's meaning is also given in text (the
 * key and the readout), never by colour alone.
 */
import type { ColorScheme } from '../../lib/colorScheme';
import type { FormZoneId } from './formZones';

export interface PmcPalette {
  readonly fitness: string;
  readonly fatigue: string;
  readonly form: string;
  readonly acwr: string;
  /** Opaque base colours of the **Form Zones**, drawn at `bandAlpha`. */
  readonly zones: Readonly<Record<FormZoneId, string>>;
  /** Base colour of the **ACWR Corridor**, drawn at `bandAlpha`. */
  readonly corridor: string;
  readonly bandAlpha: number;
}

const ZONES: PmcPalette['zones'] = {
  'high-risk': '#dc2626',
  optimal: '#16a34a',
  grey: '#64748b',
  fresh: '#2563eb',
  transition: '#d97706',
};

export const PMC_PALETTE: Record<ColorScheme, PmcPalette> = {
  light: {
    fitness: '#2563eb',
    fatigue: '#ea580c',
    form: '#334155',
    acwr: '#7c3aed',
    zones: ZONES,
    corridor: '#16a34a',
    bandAlpha: 0.12,
  },
  dark: {
    fitness: '#60a5fa',
    fatigue: '#fb923c',
    form: '#e2e8f0',
    acwr: '#a78bfa',
    zones: ZONES,
    corridor: '#22c55e',
    bandAlpha: 0.2,
  },
};

/** Heights of the three stacked uPlot canvases, shared by the adapter and the panel's reserved layout. */
export const PMC_PANE_HEIGHT_PX = { fitness: 200, form: 140, acwr: 120 } as const;
