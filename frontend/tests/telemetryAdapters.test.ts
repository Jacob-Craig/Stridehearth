import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  BEST_EFFORT_HIGHLIGHT_SOURCE,
  createRouteMap,
} from '../src/features/telemetry/maplibreRouteMap';
import type { LngLat } from '../src/features/telemetry/sampleLookup';
import { createTelemetryScrubber } from '../src/features/telemetry/scrubberBus';
import {
  type TelemetryChartOptions,
  createTelemetryChart,
} from '../src/features/telemetry/uplotChart';
import * as fakeMaplibre from './support/fakeMaplibre';

/** A manual animation-frame clock standing in for `requestAnimationFrame`. */
function frames() {
  const queued = new Map<number, () => void>();
  let nextId = 1;
  return {
    requestFrame: (callback: () => void) => {
      const id = nextId++;
      queued.set(id, callback);
      return id;
    },
    cancelFrame: (id: number) => {
      queued.delete(id);
    },
    get pending() {
      return queued.size;
    },
    flush() {
      const callbacks = [...queued.values()];
      queued.clear();
      for (const callback of callbacks) callback();
    },
  };
}

/**
 * A stand-in for uPlot's canvas renderer (jsdom has no canvas): x positions are `10px` per elapsed
 * second, and moving the cursor snaps `cursor.idx` to the nearest point as uPlot does.
 */
const fakeUplot = vi.hoisted(() => {
  const instances: FakeUplot[] = [];

  type Stroke = (u: FakeUplot, index: number) => string;
  interface FakeOptions {
    hooks: { setCursor: Array<(u: FakeUplot) => void>; draw: Array<(u: FakeUplot) => void> };
    axes: Array<{ stroke: Stroke; grid: { stroke: Stroke } }>;
    series: Array<{
      stroke?: Stroke;
      spanGaps?: boolean;
      gaps?: (
        u: FakeUplot,
        seriesIdx: number,
        idx0: number,
        idx1: number,
        nullGaps: number[][],
      ) => number[][];
    }>;
    height: number;
  }

  class FakeUplot {
    readonly cursor: { idx: number | null; left: number } = { idx: null, left: -10 };
    readonly bbox = { left: 0, top: 0, width: 1000, height: 140 };
    readonly fills: Array<[number, number, number, number]> = [];
    readonly ctx = {
      save: vi.fn(),
      restore: vi.fn(),
      fillRect: (...rect: [number, number, number, number]) => this.fills.push(rect),
      fillStyle: '',
      globalAlpha: 1,
    };
    readonly setCursorCalls: Array<[{ left: number; top: number }, boolean | undefined]> = [];
    readonly destroy = vi.fn();
    readonly setSize = vi.fn();
    readonly redraw = vi.fn(() => {
      this.fills.length = 0;
      for (const hook of this.opts.hooks.draw) hook(this);
    });

    constructor(
      readonly opts: FakeOptions,
      readonly data: [number[], (number | null)[]],
      readonly target: HTMLElement,
    ) {
      instances.push(this);
    }

    valToPos(value: number) {
      return value * 10;
    }

    posToVal(left: number) {
      return left / 10;
    }

    /** The colours the series, axes and grid would currently be drawn in. */
    strokes() {
      return {
        series: this.opts.series[1]!.stroke!(this, 1),
        axis: this.opts.axes[0]!.stroke(this, 0),
        grid: this.opts.axes[1]!.grid.stroke(this, 1),
      };
    }

    setCursor(position: { left: number; top: number }, fireHook?: boolean) {
      this.setCursorCalls.push([position, fireHook]);
      this.moveCursor(position.left);
      if (fireHook !== false) for (const hook of this.opts.hooks.setCursor) hook(this);
    }

    /** What uPlot does on a real pointer move: snap to the nearest point and fire the hooks. */
    hover(left: number | null) {
      this.moveCursor(left ?? -10);
      for (const hook of this.opts.hooks.setCursor) hook(this);
    }

    private moveCursor(left: number) {
      this.cursor.left = left;
      if (left < 0) {
        this.cursor.idx = null;
        return;
      }
      const xs = this.data[0];
      let nearest = 0;
      xs.forEach((x, i) => {
        if (Math.abs(x * 10 - left) < Math.abs(xs[nearest]! * 10 - left)) nearest = i;
      });
      this.cursor.idx = nearest;
    }
  }

  return { FakeUplot, instances };
});

vi.mock('uplot', () => ({ default: fakeUplot.FakeUplot }));

vi.mock('maplibre-gl', () => import('./support/fakeMaplibre').then((m) => m.fakeMaplibreModule));

const heartRateSamples = { x: [0, 1, 2, 3], y: [140, null, 151.25, 160] };

function chart(overrides: Partial<Omit<TelemetryChartOptions, 'container' | 'readout'>> = {}) {
  const container = document.createElement('div');
  const readout = document.createElement('output');
  const onScrub = vi.fn();
  const handle = createTelemetryChart({
    container,
    readout,
    series: { label: 'Heart Rate', unit: 'bpm', color: { light: '#dc2626', dark: '#f87171' } },
    data: heartRateSamples,
    samples: heartRateSamples,
    theme: 'light',
    onScrub,
    ...overrides,
  });
  const u = fakeUplot.instances.at(-1)!;
  return { handle, u, readout, onScrub };
}

beforeEach(() => {
  fakeUplot.instances.length = 0;
  fakeMaplibre.resetFakeMaplibre();
});

describe('uPlot chart adapter', () => {
  it('publishes the elapsed time under the pointer and shows the reading of the nearest sample', () => {
    const { u, readout, onScrub } = chart();

    u.hover(21);

    expect(onScrub).toHaveBeenCalledWith(2.1);
    expect(readout.textContent).toBe('00:02 · 151.3 bpm');
  });

  it('publishes the true pointer time, not the nearest plotted point, on a downsampled chart', () => {
    // 31 one-second samples, one route vertex per sample, plotted as only every tenth second.
    const t = Array.from({ length: 31 }, (_, i) => i);
    const timeline = { coordinates: t.map((i): LngLat => [i, 50]), t, routeIndex: t };
    const clock = frames();
    const bus = createTelemetryScrubber({ timeline, clock });
    const marker = { moveTo: vi.fn(), hide: vi.fn() };
    bus.registerMarker(marker);
    const { u, readout } = chart({
      data: { x: [0, 10, 20, 30], y: [0, 20, 40, 60] },
      samples: { x: t, y: t.map((i) => i * 2) },
      onScrub: (seconds) => bus.scrub(seconds),
    });

    u.hover(134);
    clock.flush();

    expect(u.cursor.idx).toBe(1);
    expect(marker.moveTo).toHaveBeenCalledWith([13, 50]);
    expect(readout.textContent).toBe('00:13 · 26 bpm');
  });

  it('reads out the full-resolution sample at a time scrubbed from a sibling chart', () => {
    const t = Array.from({ length: 31 }, (_, i) => i);
    const { handle, u, readout } = chart({
      data: { x: [0, 10, 20, 30], y: [0, 20, 40, 60] },
      samples: { x: t, y: t.map((i) => i * 2) },
    });

    handle.setCursorTime(17.2);

    expect(u.setCursorCalls).toEqual([[{ left: 172, top: -1 }, false]]);
    expect(readout.textContent).toBe('00:17 · 34 bpm');
  });

  it('breaks the line only at sensor dropouts, bridging sparse readings a few seconds apart', () => {
    // Heart rate every 5 seconds, with a 60-second dropout between 100s and 160s.
    const x = Array.from({ length: 201 }, (_, i) => i);
    const y = x.map((i) => (i % 5 === 0 && (i <= 100 || i >= 160) ? 140 : null));
    const { u } = chart({ data: { x, y }, samples: { x, y } });
    const series = u.opts.series[1]!;
    // Every null between readings is a gap to uPlot; only the dropout is kept, in canvas pixels.
    const nullGaps = [
      [0, 50],
      [1000, 1600],
    ];

    expect(series.spanGaps).toBe(false);
    expect(series.gaps!(u, 1, 0, x.length - 1, nullGaps)).toEqual([[1000, 1600]]);
  });

  it('reads out the nearest recorded sample between sparse readings, and none inside a dropout', () => {
    const x = Array.from({ length: 201 }, (_, i) => i);
    const y = x.map((i) => (i % 5 === 0 && (i <= 100 || i >= 160) ? 140 + i / 5 : null));
    const { handle, readout } = chart({ data: { x, y }, samples: { x, y } });

    handle.setCursorTime(12);
    expect(readout.textContent).toBe('00:12 · 142 bpm');

    handle.setCursorTime(130);
    expect(readout.textContent).toBe('02:10 · —');
  });

  it('publishes that the pointer left', () => {
    const { u, readout, onScrub } = chart();

    u.hover(null);

    expect(onScrub).toHaveBeenCalledWith(null);
    expect(readout.textContent).toBe('—');
  });

  it('moves its crosshair to a scrubbed time without echoing it back to the bus', () => {
    const { handle, u, readout, onScrub } = chart();

    handle.setCursorTime(3);

    expect(u.setCursorCalls).toEqual([[{ left: 30, top: -1 }, false]]);
    expect(onScrub).not.toHaveBeenCalled();
    expect(readout.textContent).toBe('00:03 · 160 bpm');
  });

  it('hides its crosshair when scrubbing stops', () => {
    const { handle, u, readout } = chart();
    handle.setCursorTime(3);

    handle.setCursorTime(null);

    expect(u.setCursorCalls.at(-1)).toEqual([{ left: -10, top: -10 }, false]);
    expect(readout.textContent).toBe('—');
  });

  it('shades a Best Effort window by elapsed time and clears it again', () => {
    const { handle, u } = chart();

    handle.setHighlight({ startTimeSeconds: 1, endTimeSeconds: 2.5 });
    expect(u.fills).toEqual([[10, 0, 15, 140]]);

    handle.setHighlight(null);
    expect(u.fills).toEqual([]);
  });

  it('uses theme-synced strokes for the series, axes and grid', () => {
    const light = chart().u;
    const dark = chart({ theme: 'dark' }).u;

    expect(light.strokes().series).toBe('#dc2626');
    expect(dark.strokes().series).toBe('#f87171');
    expect(light.strokes().axis).not.toBe(dark.strokes().axis);
    expect(light.strokes().grid).not.toBe(dark.strokes().grid);
    expect(light.opts.height).toBe(140);
  });

  it('restyles in place on a colour-scheme change, keeping the chart and its reading', () => {
    const { handle, u, readout } = chart();
    const dark = chart({ theme: 'dark' }).u.strokes();
    handle.setCursorTime(3);
    u.redraw.mockClear();

    handle.setTheme('dark');

    expect(u.strokes()).toEqual(dark);
    expect(u.redraw).toHaveBeenCalledWith(false);
    expect(u.destroy).not.toHaveBeenCalled();
    expect(readout.textContent).toBe('00:03 · 160 bpm');
  });
});

describe('MapLibre route map adapter', () => {
  const coordinates: LngLat[] = [
    [-0.16, 51.51],
    [-0.17, 51.52],
    [-0.18, 51.53],
  ];

  function routeMap(overrides: Partial<Parameters<typeof createRouteMap>[0]> = {}) {
    const onRouteVertexHover = vi.fn();
    const clock = frames();
    const handle = createRouteMap({
      container: document.createElement('div'),
      coordinates,
      theme: 'light',
      perspective: '2d',
      onRouteVertexHover,
      clock,
      ...overrides,
    });
    const map = fakeMaplibre.FakeMap.instances.at(-1)!;
    map.emit('style.load');
    return { handle, map, onRouteVertexHover, clock };
  }

  type HighlightData =
    | { type: 'Feature'; geometry: { type: 'LineString'; coordinates: unknown } }
    | { type: 'FeatureCollection'; features: unknown[] };

  /** The highlighted path, or `null` when the source holds no feature at all. */
  const highlightCoordinates = (map: InstanceType<typeof fakeMaplibre.FakeMap>) => {
    const data = map.sources.get(BEST_EFFORT_HIGHLIGHT_SOURCE)!.data as HighlightData;
    if (data.type === 'FeatureCollection') {
      expect(data.features).toEqual([]);
      return null;
    }
    expect(data.geometry.type).toBe('LineString');
    return data.geometry.coordinates;
  };

  it('draws the route with start and end markers on the OpenFreeMap style of the colour scheme', () => {
    const { map } = routeMap();
    routeMap({ theme: 'dark' });

    expect(map.styles).toEqual(['https://tiles.openfreemap.org/styles/positron']);
    expect(fakeMaplibre.FakeMap.instances[1]!.styles).toEqual([
      'https://tiles.openfreemap.org/styles/dark',
    ]);
    expect(map.sources.get('route')!.data).toMatchObject({
      geometry: { type: 'LineString', coordinates },
    });
    const pins = fakeMaplibre.FakeMarker.instances.filter((m) => m.map === map);
    expect(pins.map((m) => m.lngLat)).toEqual([coordinates[0], coordinates[2]]);
  });

  it('fits the camera to the route', () => {
    const { map } = routeMap();

    expect(map.options).toMatchObject({
      bounds: { points: [coordinates[0], coordinates[0], ...coordinates] },
      fitBoundsOptions: { pitch: 0 },
    });
  });

  it('sets and clears the best-effort-highlight-source path', () => {
    const { handle, map } = routeMap();
    expect(highlightCoordinates(map)).toBeNull();

    handle.setHighlight([
      [-0.17, 51.52],
      [-0.18, 51.53],
    ]);
    expect(highlightCoordinates(map)).toEqual([
      [-0.17, 51.52],
      [-0.18, 51.53],
    ]);

    handle.setHighlight([]);
    expect(highlightCoordinates(map)).toBeNull();
  });

  it('never sends a one-position LineString, which is not valid GeoJSON', () => {
    const { handle, map } = routeMap();

    handle.setHighlight([[-0.17, 51.52]]);

    expect(highlightCoordinates(map)).toBeNull();
  });

  it('switches style with the colour scheme, keeping the route and its highlight', () => {
    const { handle, map } = routeMap();
    const path: LngLat[] = [
      [-0.17, 51.52],
      [-0.18, 51.53],
    ];
    handle.setHighlight(path);

    handle.setTheme('dark');
    map.emit('style.load');

    expect(map.styles.at(-1)).toBe('https://tiles.openfreemap.org/styles/dark');
    expect(map.sources.has('route')).toBe(true);
    expect(highlightCoordinates(map)).toEqual(path);
  });

  it('pitches the camera and extrudes buildings in 3D, and flattens both in 2D', () => {
    const { handle, map } = routeMap();
    expect(map.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'none' });

    handle.setPerspective('3d');
    expect(map.cameraMoves.at(-1)).toMatchObject({ pitch: 55 });
    expect(map.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'visible' });

    handle.setPerspective('2d');
    expect(map.cameraMoves.at(-1)).toMatchObject({ pitch: 0, bearing: 0 });
    expect(map.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'none' });
  });

  it('moves the scrubber marker along the route and hides it', () => {
    const { handle, map } = routeMap();

    handle.marker.moveTo([-0.17, 51.52]);
    const scrubber = fakeMaplibre.FakeMarker.instances.find((m) => m.options.element)!;
    expect(scrubber.lngLat).toEqual([-0.17, 51.52]);
    expect(scrubber.map).toBe(map);

    handle.marker.hide();
    expect(scrubber.map).toBeNull();
  });

  it('reports the nearest route vertex under the pointer, and when it leaves the route', () => {
    const { map, onRouteVertexHover, clock } = routeMap();

    map.emit('mousemove', 'route-hit', { lngLat: { lng: -0.1699, lat: 51.5201 } });
    clock.flush();
    expect(onRouteVertexHover).toHaveBeenLastCalledWith(1);

    map.emit('mouseleave', 'route-hit');
    clock.flush();
    expect(onRouteVertexHover).toHaveBeenLastCalledWith(null);
  });

  it('searches for the nearest route vertex at most once per animation frame', () => {
    const { map, onRouteVertexHover, clock } = routeMap();

    map.emit('mousemove', 'route-hit', { lngLat: { lng: -0.16, lat: 51.51 } });
    map.emit('mousemove', 'route-hit', { lngLat: { lng: -0.17, lat: 51.52 } });
    map.emit('mousemove', 'route-hit', { lngLat: { lng: -0.18, lat: 51.53 } });
    expect(onRouteVertexHover).not.toHaveBeenCalled();
    expect(clock.pending).toBe(1);

    clock.flush();
    expect(onRouteVertexHover).toHaveBeenCalledTimes(1);
    expect(onRouteVertexHover).toHaveBeenCalledWith(2);
  });

  it('drops a pending hover search when the pointer leaves the route, or the map is destroyed', () => {
    const { handle, map, onRouteVertexHover, clock } = routeMap();

    map.emit('mousemove', 'route-hit', { lngLat: { lng: -0.18, lat: 51.53 } });
    map.emit('mouseleave', 'route-hit');
    clock.flush();
    expect(onRouteVertexHover.mock.calls).toEqual([[null]]);

    map.emit('mousemove', 'route-hit', { lngLat: { lng: -0.18, lat: 51.53 } });
    handle.destroy();
    expect(clock.pending).toBe(0);
  });
});
