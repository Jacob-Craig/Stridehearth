import { useEffect, useState } from 'react';

/** A renderer created from a lazily-loaded adapter module, and how to tear it down. */
export interface MountedRenderer<Handle> {
  readonly handle: Handle;
  unmount(): void;
}

/** The lifecycle of a lazily-loaded canvas or WebGL renderer. */
export type LazyRenderer<Handle> =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly handle: Handle }
  /** The adapter failed to load or the renderer could not be created, e.g. without WebGL. */
  | { readonly kind: 'failed' };

const LOADING = { kind: 'loading' } as const;

/**
 * Loads an adapter module on demand and mounts one renderer from it, unmounting it when `mount`
 * changes or the component unmounts. Pass a memoized `mount`, or `null` while its inputs are not yet
 * available; `mount` returns `null` when its container has gone.
 */
export function useLazyRenderer<Module, Handle>(
  load: () => Promise<Module>,
  mount: ((module: Module) => MountedRenderer<Handle> | null) | null,
): LazyRenderer<Handle> {
  const [renderer, setRenderer] = useState<LazyRenderer<Handle>>(LOADING);

  useEffect(() => {
    if (!mount) return;
    let disposed = false;
    let mounted: MountedRenderer<Handle> | null = null;
    load()
      .then((module) => {
        if (disposed) return;
        mounted = mount(module);
        if (mounted) setRenderer({ kind: 'ready', handle: mounted.handle });
      })
      .catch(() => {
        if (!disposed) setRenderer({ kind: 'failed' });
      });
    return () => {
      disposed = true;
      mounted?.unmount();
      setRenderer(LOADING);
    };
  }, [load, mount]);

  return renderer;
}
