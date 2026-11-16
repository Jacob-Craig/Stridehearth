/** Schedules work on the next animation frame; injectable so tests can step frames by hand. */
export interface FrameClock {
  requestFrame(callback: () => void): number;
  cancelFrame(id: number): void;
}

export const animationFrameClock: FrameClock = {
  requestFrame: (callback) => requestAnimationFrame(callback),
  cancelFrame: (id) => cancelAnimationFrame(id),
};

export interface FrameCoalescer<T> {
  /** Queues `value` for the next frame, replacing any value already queued for it. */
  push(value: T): void;
  /** Drops the queued value and its frame. */
  cancel(): void;
}

/**
 * Runs `apply` at most once per animation frame, with the latest value pushed during that frame, so
 * high-frequency pointer events cost one update per frame however fast they arrive.
 */
export function coalescePerFrame<T>(
  apply: (latest: T) => void,
  clock: FrameClock = animationFrameClock,
): FrameCoalescer<T> {
  let frame: number | null = null;
  let queued: { readonly value: T } | null = null;

  const flush = () => {
    frame = null;
    if (!queued) return;
    const { value } = queued;
    queued = null;
    apply(value);
  };

  return {
    push(value) {
      queued = { value };
      frame ??= clock.requestFrame(flush);
    },
    cancel() {
      if (frame != null) clock.cancelFrame(frame);
      frame = null;
      queued = null;
    },
  };
}
