import { useSyncExternalStore } from 'react';

export type ColorScheme = 'light' | 'dark';

const DARK_QUERY = '(prefers-color-scheme: dark)';

const darkQuery = (): MediaQueryList | null =>
  typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null;

function subscribe(onChange: () => void): () => void {
  const query = darkQuery();
  query?.addEventListener('change', onChange);
  return () => query?.removeEventListener('change', onChange);
}

const currentScheme = (): ColorScheme => (darkQuery()?.matches ? 'dark' : 'light');

/** The operating system's colour scheme, which the app (`color-scheme: light dark`) follows. */
export function useColorScheme(): ColorScheme {
  return useSyncExternalStore(subscribe, currentScheme, () => 'light');
}
