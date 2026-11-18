import { describe, expect, it } from 'vitest';
import type { PmcDayDto } from '../src/api/generated/types.gen';
import { ACWR_CORRIDOR, FORM_ZONES, formZoneOf } from '../src/features/pmc/formZones';
import { DEFAULT_PMC_RANGE, PMC_RANGES, visibleWindow } from '../src/features/pmc/pmcRange';
import { formatPmcReadout } from '../src/features/pmc/pmcReadout';
import { pmcDay } from './support/fakePmcChart';

describe('Form zones', () => {
  it.each([
    [-45, 'High Risk'],
    [-30.01, 'High Risk'],
    [-30, 'Optimal'],
    [-10.01, 'Optimal'],
    [-10, 'Grey'],
    [4.99, 'Grey'],
    [5, 'Fresh'],
    [24.99, 'Fresh'],
    [25, 'Transition'],
    [60, 'Transition'],
  ])('puts a Form of %s in the %s zone', (tsb, zone) => {
    expect(formZoneOf(tsb).label).toBe(zone);
  });

  it('lists the five zones from the lowest Form to the highest, with no gaps or overlaps', () => {
    expect(FORM_ZONES.map((zone) => [zone.label, zone.min, zone.max])).toEqual([
      ['High Risk', Number.NEGATIVE_INFINITY, -30],
      ['Optimal', -30, -10],
      ['Grey', -10, 5],
      ['Fresh', 5, 25],
      ['Transition', 25, Number.POSITIVE_INFINITY],
    ]);
  });

  it('keeps the ACWR corridor at 0.80 to 1.30', () => {
    expect(ACWR_CORRIDOR).toEqual({ min: 0.8, max: 1.3 });
  });
});

function days(count: number): PmcDayDto[] {
  return Array.from({ length: count }, (_, i) => pmcDay({ date: `day-${i}` }));
}

describe('visible window of a PMC range', () => {
  it.each([
    ['90d', 400, { fromIndex: 310, toIndex: 399 }],
    ['1y', 400, { fromIndex: 35, toIndex: 399 }],
    ['all', 400, { fromIndex: 0, toIndex: 399 }],
    ['90d', 90, { fromIndex: 0, toIndex: 89 }],
    ['90d', 30, { fromIndex: 0, toIndex: 29 }],
    ['1y', 200, { fromIndex: 0, toIndex: 199 }],
    ['all', 1, { fromIndex: 0, toIndex: 0 }],
  ] as const)(
    'shows the last days of %s over %i days, clamped to the data',
    (range, count, window) => {
      expect(visibleWindow(days(count), range)).toEqual(window);
    },
  );

  it('has no window without days', () => {
    expect(visibleWindow([], 'all')).toBeNull();
  });

  it('offers 90 days, 1 year and All, defaulting to 90 days', () => {
    expect(PMC_RANGES.map((range) => [range.id, range.label])).toEqual([
      ['90d', '90 days'],
      ['1y', '1 year'],
      ['all', 'All'],
    ]);
    expect(DEFAULT_PMC_RANGE).toBe('90d');
  });
});

describe('PMC readout', () => {
  it("reads out every value of the day, with Form's zone", () => {
    const day = pmcDay({
      date: '2026-11-12',
      trainingLoad: 84.96,
      ctl: 42.34,
      atl: 55.12,
      tsb: -12.78,
      acwr: 1.1234,
      monotony: 1.456,
      strain: 812.4,
    });
    expect(formatPmcReadout(day)).toBe(
      '12 Nov 2026 · Load 85.0 · Fitness (CTL) 42.3 · Fatigue (ATL) 55.1 · Form (TSB) -12.8 Optimal · ACWR 1.12 · Monotony 1.46 · Strain 812',
    );
  });

  it('shows a dash for a withheld ACWR, Monotony and Strain', () => {
    const day = pmcDay({ date: '2026-01-03', acwr: null, monotony: null, strain: null });
    expect(formatPmcReadout(day)).toBe(
      '3 Jan 2026 · Load 0.0 · Fitness (CTL) 0.0 · Fatigue (ATL) 0.0 · Form (TSB) 0.0 Grey · ACWR — · Monotony — · Strain —',
    );
  });
});
