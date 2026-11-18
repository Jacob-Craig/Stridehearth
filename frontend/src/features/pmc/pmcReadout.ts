import type { PmcDayDto } from '../../api/generated/types.gen';
import { MISSING, formatDate } from '../../lib/format';
import { formZoneOf } from './formZones';

const oneDecimal = (value: number) => value.toFixed(1);
const twoDecimals = (value: number | null) => (value == null ? MISSING : value.toFixed(2));
const whole = (value: number | null) => (value == null ? MISSING : Math.round(value).toFixed(0));

/**
 * Every value of one day of the **Performance Management Chart**, as read out under the cursor: loads and
 * Form to one decimal, ACWR and Monotony to two, Strain whole, a withheld value as a dash. Form carries its
 * **Form Zone** by name, so the zone never depends on colour alone.
 */
export function formatPmcReadout(day: PmcDayDto): string {
  return [
    formatDate(day.date),
    `Load ${oneDecimal(day.trainingLoad)}`,
    `Fitness (CTL) ${oneDecimal(day.ctl)}`,
    `Fatigue (ATL) ${oneDecimal(day.atl)}`,
    `Form (TSB) ${oneDecimal(day.tsb)} ${formZoneOf(day.tsb).label}`,
    `ACWR ${twoDecimals(day.acwr)}`,
    `Monotony ${twoDecimals(day.monotony)}`,
    `Strain ${whole(day.strain)}`,
  ].join(' · ');
}
