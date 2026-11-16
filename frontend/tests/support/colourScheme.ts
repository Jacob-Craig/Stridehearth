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
