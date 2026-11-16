/** Typed router history state carried across a navigation (never persisted in the URL). */
declare module '@tanstack/history' {
  interface HistoryState {
    /** A one-off notification banner for the destination route, e.g. after a file upload. */
    notice?: string;
  }
}

export {};
