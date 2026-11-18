/**
 * The **Form Zones** shaded behind Form (TSB) on the **Performance Management Chart**, and the **ACWR
 * Corridor** shaded behind the Acute:Chronic Workload Ratio. Pure constants, shared by the chart and its
 * readout so the shading and the zone name can never disagree.
 */

export type FormZoneId = 'high-risk' | 'optimal' | 'grey' | 'fresh' | 'transition';

export interface FormZone {
  readonly id: FormZoneId;
  readonly label: string;
  /** Inclusive lower bound of Form. */
  readonly min: number;
  /** Exclusive upper bound of Form. */
  readonly max: number;
}

/** Half-open bands `[min, max)` from the lowest Form to the highest, with no gaps or overlaps. */
export const FORM_ZONES: readonly FormZone[] = [
  { id: 'high-risk', label: 'High Risk', min: Number.NEGATIVE_INFINITY, max: -30 },
  { id: 'optimal', label: 'Optimal', min: -30, max: -10 },
  { id: 'grey', label: 'Grey', min: -10, max: 5 },
  { id: 'fresh', label: 'Fresh', min: 5, max: 25 },
  { id: 'transition', label: 'Transition', min: 25, max: Number.POSITIVE_INFINITY },
];

/** The **Form Zone** a Form (TSB) value falls in. */
export function formZoneOf(tsb: number): FormZone {
  return FORM_ZONES.find((zone) => tsb >= zone.min && tsb < zone.max) ?? FORM_ZONES[0]!;
}

/** The ACWR range, both ends inclusive, in which load is rising no faster than the athlete is adapted to. */
export const ACWR_CORRIDOR = { min: 0.8, max: 1.3 } as const;
