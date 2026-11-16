/**
 * The last area of the **Heatmap** the athlete viewed, kept in local browser storage so later visits
 * reopen there. Storage may be missing, blocked or full (private windows, strict privacy settings), and
 * what it holds may be stale or edited by hand, so every read and write tolerates failure: a read then
 * finds no view, and the Heatmap opens on its starting view instead.
 */
import type { HeatmapView } from './heatmapLayers';

// Per athlete, so one athlete's whereabouts never open another's Heatmap on a shared browser.
// Without an id there is nowhere safe to keep it.
const storageKeyFor = (userId: number | undefined): string | null =>
  userId === undefined ? null : `stridehearth.heatmap.view.${userId}`;

const isLongitude = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= 180;
const isLatitude = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= 90;

/** MapLibre's deepest zoom; anything beyond it was not saved by the map. */
const MAX_ZOOM = 24;
const isZoom = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= MAX_ZOOM;

function parseView(stored: string): HeatmapView | null {
  const value: unknown = JSON.parse(stored);
  if (typeof value !== 'object' || value === null) return null;
  const { center, zoom } = value as { center?: unknown; zoom?: unknown };
  if (!Array.isArray(center) || center.length !== 2) return null;
  const [lng, lat] = center as unknown[];
  if (!isLongitude(lng) || !isLatitude(lat)) return null;
  if (!isZoom(zoom)) return null;
  return { center: [lng, lat], zoom };
}

/** The athlete's last viewed area, or `null` if none was saved or it cannot be read. */
export function readRememberedView(userId: number | undefined): HeatmapView | null {
  const storageKey = storageKeyFor(userId);
  if (storageKey === null) return null;
  try {
    const stored = window.localStorage.getItem(storageKey);
    return stored === null ? null : parseView(stored);
  } catch {
    return null;
  }
}

/** Saves the athlete's view for later visits, silently doing nothing where storage fails. */
export function rememberView(userId: number | undefined, view: HeatmapView): void {
  const storageKey = storageKeyFor(userId);
  if (storageKey === null) return;
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(view));
  } catch {
    // Storage is blocked or full; the next visit opens on the starting view.
  }
}
