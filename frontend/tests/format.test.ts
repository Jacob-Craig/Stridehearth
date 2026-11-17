import { describe, expect, it } from 'vitest';
import {
  averageSpeedKmh,
  decouplingStatus,
  formatDate,
  formatDateTime,
  formatDecouplingPercent,
  formatDecouplingUnavailableReason,
  formatDistanceKm,
  formatDistanceMeters,
  formatDistanceRangeKm,
  formatDuration,
  formatDurationRange,
  formatEfficiencyFactor,
  formatElevationGain,
  formatNgpPerKm,
  formatPacePerKm,
} from '../src/lib/format';

describe('formatDuration', () => {
  it('formats sub-hour elapsed seconds as MM:SS', () => {
    expect(formatDuration(1200)).toBe('20:00');
    expect(formatDuration(96)).toBe('01:36');
    expect(formatDuration(0)).toBe('00:00');
  });

  it('formats elapsed seconds of an hour or more as HH:MM:SS', () => {
    expect(formatDuration(6000)).toBe('01:40:00');
    expect(formatDuration(3600)).toBe('01:00:00');
  });

  it('rounds fractional seconds to the nearest whole second', () => {
    expect(formatDuration(95.6)).toBe('01:36');
    expect(formatDuration(3599.5)).toBe('01:00:00');
  });

  it('renders a dash for negative or non-finite input', () => {
    expect(formatDuration(-1)).toBe('—');
    expect(formatDuration(Number.NaN)).toBe('—');
  });
});

describe('averageSpeedKmh', () => {
  it('derives km/h from distance in meters and elapsed seconds', () => {
    expect(averageSpeedKmh(5000, 1200)).toBe(15);
    expect(averageSpeedKmh(10000, 3600)).toBe(10);
  });

  it('returns 0 when no time has elapsed', () => {
    expect(averageSpeedKmh(400, 0)).toBe(0);
  });
});

describe('formatPacePerKm', () => {
  it('formats a speed in km/h as MM:SS per kilometer', () => {
    expect(formatPacePerKm(15)).toBe('04:00 /km');
    expect(formatPacePerKm(12)).toBe('05:00 /km');
  });

  it('rounds to the nearest second per kilometer', () => {
    // 1 mile in 6:00 -> 223.69 s/km
    expect(formatPacePerKm(16.09344)).toBe('03:44 /km');
  });

  it('formats slow paces over an hour per km', () => {
    expect(formatPacePerKm(0.5)).toBe('120:00 /km');
  });

  it('renders a dash for zero, negative, or non-finite speed', () => {
    expect(formatPacePerKm(0)).toBe('—');
    expect(formatPacePerKm(-3)).toBe('—');
    expect(formatPacePerKm(Number.POSITIVE_INFINITY)).toBe('—');
  });
});

describe('formatDistanceKm', () => {
  it('formats kilometers to two decimal places with grouping', () => {
    expect(formatDistanceKm(5)).toBe('5.00 km');
    expect(formatDistanceKm(21.0975)).toBe('21.10 km');
    expect(formatDistanceKm(1234.5)).toBe('1,234.50 km');
  });
});

describe('formatDistanceMeters', () => {
  it('formats meters without trailing zeros and with grouping', () => {
    expect(formatDistanceMeters(400)).toBe('400 m');
    expect(formatDistanceMeters(1609.344)).toBe('1,609.344 m');
    expect(formatDistanceMeters(21097.5)).toBe('21,097.5 m');
  });
});

describe('formatDate', () => {
  it('formats an ISO-8601 local date without shifting the day', () => {
    expect(formatDate('2026-03-01')).toBe('1 Mar 2026');
  });

  it('formats an ISO-8601 instant in the requested time zone', () => {
    expect(formatDate('2026-04-10T09:00:00Z', 'UTC')).toBe('10 Apr 2026');
    expect(formatDate('2026-04-10T23:30:00Z', 'Asia/Tokyo')).toBe('11 Apr 2026');
  });

  it('renders a dash for an unparseable value', () => {
    expect(formatDate('not-a-date')).toBe('—');
  });
});

describe('formatElevationGain', () => {
  it('formats meters climbed with at most one decimal place', () => {
    expect(formatElevationGain(12.5)).toBe('+12.5 m');
    expect(formatElevationGain(40)).toBe('+40 m');
    expect(formatElevationGain(3.24)).toBe('+3.2 m');
  });

  it('shows a dash when the device recorded no elevation', () => {
    expect(formatElevationGain(null)).toBe('—');
  });
});

describe('formatDateTime', () => {
  it('formats an instant as a date and 24-hour time in the given zone', () => {
    expect(formatDateTime('2026-03-01T09:05:00Z', 'UTC')).toBe('1 Mar 2026, 09:05');
    expect(formatDateTime('2026-03-01T23:30:00Z', 'Asia/Tokyo')).toBe('2 Mar 2026, 08:30');
  });

  it('shows a dash for an unparseable value', () => {
    expect(formatDateTime('not a date')).toBe('—');
  });
});

describe('formatDistanceRangeKm', () => {
  it('formats a span of cumulative meters as kilometers to two decimal places', () => {
    expect(formatDistanceRangeKm(120, 1120)).toBe('0.12 – 1.12 km');
    expect(formatDistanceRangeKm(0, 21097.5)).toBe('0.00 – 21.10 km');
  });
});

describe('formatDurationRange', () => {
  it('formats a span of elapsed seconds', () => {
    expect(formatDurationRange(10, 250)).toBe('00:10 – 04:10');
    expect(formatDurationRange(3540, 3725)).toBe('59:00 – 01:02:05');
  });
});

describe('formatNgpPerKm', () => {
  it('formats Normalized Graded Pace in m/s as a pace per km', () => {
    // 10/3 m/s = 12 km/h = 5:00 /km; 4 m/s = 14.4 km/h = 250 s /km.
    expect(formatNgpPerKm(10 / 3)).toBe('05:00 /km');
    expect(formatNgpPerKm(4)).toBe('04:10 /km');
  });

  it('shows a dash when absent', () => {
    expect(formatNgpPerKm(null)).toBe('—');
  });
});

describe('formatEfficiencyFactor', () => {
  it('formats to two decimal places', () => {
    expect(formatEfficiencyFactor(1.2)).toBe('1.20');
    expect(formatEfficiencyFactor(1.23456)).toBe('1.23');
  });

  it('shows a dash when absent', () => {
    expect(formatEfficiencyFactor(null)).toBe('—');
  });
});

describe('formatDecouplingPercent', () => {
  it('formats to one decimal place with a percent sign', () => {
    expect(formatDecouplingPercent(4.94)).toBe('4.9%');
    expect(formatDecouplingPercent(9.0909)).toBe('9.1%');
    expect(formatDecouplingPercent(-2)).toBe('-2.0%');
  });
});

describe('decouplingStatus', () => {
  it('is coupled below 5 percent, including negative drift', () => {
    expect(decouplingStatus(4.9)).toBe('coupled');
    expect(decouplingStatus(4.999)).toBe('coupled');
    expect(decouplingStatus(-3)).toBe('coupled');
  });

  it('is drifting at 5 percent or more', () => {
    expect(decouplingStatus(5)).toBe('drifting');
    expect(decouplingStatus(12.5)).toBe('drifting');
  });
});

describe('formatDecouplingUnavailableReason', () => {
  it('explains each reason', () => {
    expect(formatDecouplingUnavailableReason('NO_HEART_RATE')).toBe('No heart rate');
    expect(formatDecouplingUnavailableReason('TOO_SHORT')).toBe('Needs 30 min of running');
    expect(formatDecouplingUnavailableReason('NOT_STEADY')).toBe('Pace too variable');
    expect(formatDecouplingUnavailableReason('NO_PACE_DATA')).toBe('No pace data');
  });
});
