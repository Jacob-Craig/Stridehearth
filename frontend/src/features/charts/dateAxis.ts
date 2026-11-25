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
