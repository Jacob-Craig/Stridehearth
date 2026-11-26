/**
 * Day-based chart axes. A day of an athlete-local series (`2026-03-01`) is plotted at midnight UTC of that
 * calendar date and labelled through `calendarDate`, so its label is the same date whatever the viewer's
 * own time zone is.
 */

export const SECONDS_PER_DAY = 86_400;

/** The x value of a local date: midnight UTC of that calendar date, in epoch seconds. */
export function dayStartSeconds(date: string): number {
  return Date.parse(`${date}T00:00:00Z`) / 1000;
}

/**
 * uPlot `tzDate`: a `Date` whose local fields read the UTC calendar date and time of `seconds`, so the axis
 * labels a plotted day as its own date.
 */
export function calendarDate(seconds: number): Date {
  const utc = new Date(seconds * 1000);
  return new Date(
    utc.getUTCFullYear(),
    utc.getUTCMonth(),
    utc.getUTCDate(),
    utc.getUTCHours(),
    utc.getUTCMinutes(),
    utc.getUTCSeconds(),
  );
}

/**
 * Least room uPlot leaves between date ticks: wide enough for `Sept 2026` and a gap, so a narrow
 * (phone-width) chart drops ticks rather than running its labels together.
 */
export const DATE_TICK_SPACE_PX = 80;

/** Ticks closer together than this are days or weeks apart, so they are labelled by day. */
const DAY_TICK_SPACING_SECONDS = 28 * SECONDS_PER_DAY;

/**
 * Labels for a chart's time ticks (epoch seconds) in the app's date style, formatted in `timeZone` (the
 * viewer's when omitted): `2 Nov` when ticks are under a month apart, otherwise `Mar 2026`. A day-based
 * axis (`dayStartSeconds`) passes `'UTC'`, since its days sit at midnight UTC.
 */
export function dateTickLabels(ticks: readonly number[], timeZone?: string): string[] {
  const first = ticks[0];
  const second = ticks[1];
  const byDay =
    first !== undefined && second !== undefined && second - first < DAY_TICK_SPACING_SECONDS;
  const format = new Intl.DateTimeFormat(
    'en-GB',
    byDay
      ? { day: 'numeric', month: 'short', timeZone }
      : { month: 'short', year: 'numeric', timeZone },
  );
  return ticks.map((tick) => format.format(new Date(tick * 1000)));
}
