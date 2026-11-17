/** Pace as typed by an athlete: minutes and seconds per km, such as `4:30`. */
const PACE = /^(\d{1,2}):([0-5]\d)$/;

/**
 * The speed in m/s of a pace typed as `min:sec` per km (`4:00` is 1000 / 240 = 4.1667 m/s), or null when the text
 * is not a positive pace. Threshold pace travels and is stored as a speed; only the SPA speaks in paces.
 */
export function parsePace(text: string): number | null {
  const match = PACE.exec(text.trim());
  if (!match) return null;
  const seconds = Number(match[1]) * 60 + Number(match[2]);
  return seconds > 0 ? 1000 / seconds : null;
}

/** A speed in m/s as `min:sec` per km, to the nearest second (1000 / 240 m/s is `4:00`). */
export function formatPace(speedMps: number): string {
  const seconds = Math.round(1000 / speedMps);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
