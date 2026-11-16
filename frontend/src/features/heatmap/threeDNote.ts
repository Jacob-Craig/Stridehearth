/**
 * Whether this browser has already shown the one-time note that the **Heatmap** in 3D loads more
 * map data. Kept in local browser storage, which may be missing, blocked or full (private windows,
 * strict privacy settings), so every read and write tolerates failure: the note may then show again on
 * a later visit, but nothing breaks.
 */
const KEY = 'stridehearth.heatmap.3dNoteShown';

export const THREE_D_NOTE = '3D loads more map data and may be slower on large Heatmaps';

/**
 * Set when the note was shown but storage could not remember it, so it is not shown again until the
 * page is next loaded.
 */
let shownWithoutStorage = false;

/**
 * `false` when the note has never been shown here, or storage cannot be read and the note has not
 * been shown since the page loaded.
 */
export function wasThreeDNoteShown(): boolean {
  try {
    return shownWithoutStorage || localStorage.getItem(KEY) === 'true';
  } catch {
    return shownWithoutStorage;
  }
}

/** Remembers that the note was shown; if storage fails, only until the page is next loaded. */
export function rememberThreeDNoteShown(): void {
  try {
    localStorage.setItem(KEY, 'true');
  } catch {
    // Storage is blocked or full: the note may show again on a later visit, but not this one.
    shownWithoutStorage = true;
  }
}
