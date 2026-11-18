import { vi } from 'vitest';

/**
 * Stubs `window.matchMedia` so the app sees a fixed colour scheme. Undo with `vi.unstubAllGlobals()`.
 */
export function stubColourScheme(theme: 'light' | 'dark'): void {
  vi.stubGlobal(
    'matchMedia',
    (query: string) =>
      ({
        matches: query === '(prefers-color-scheme: dark)' && theme === 'dark',
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }) as unknown as MediaQueryList,
  );
}

/**
 * Stubs `window.matchMedia` with a colour scheme the test can change, notifying the app's listeners as
 * the operating system would. Undo with `vi.unstubAllGlobals()`.
 */
export function changeableColourScheme(initial: 'light' | 'dark'): {
  change(theme: 'light' | 'dark'): void;
} {
  let dark = initial === 'dark';
  const listeners = new Set<() => void>();
  vi.stubGlobal(
    'matchMedia',
    (query: string) =>
      ({
        get matches() {
          return query === '(prefers-color-scheme: dark)' && dark;
        },
        addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
        removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
      }) as unknown as MediaQueryList,
  );
  return {
    change(theme) {
      dark = theme === 'dark';
      for (const listener of listeners) listener();
    },
  };
}
