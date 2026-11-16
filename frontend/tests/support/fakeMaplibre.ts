/**
 * A stand-in for MapLibre GL JS (jsdom has no WebGL) recording what the map adapters ask of it.
 * Install with:
 *
 *   vi.mock('maplibre-gl', () => import('./support/fakeMaplibre').then((m) => m.fakeMaplibreModule));
 */
import type { Map as MapLibreMap } from 'maplibre-gl';
import { vi } from 'vitest';

type Handler = (event?: { lngLat: { lng: number; lat: number } }) => void;

/** A layer of the loaded basemap style, as `getStyle()` lists it. */
export type FakeStyleLayer = { id: string; type: string; 'source-layer'?: string };

/** A GeoJSON, vector or raster tile source, as added, with what has been set on it since. */
class FakeSource {
  tiles: unknown;
  constructor(
    public data: unknown,
    readonly spec: Record<string, unknown> = {},
  ) {
    this.tiles = spec.tiles;
  }
  setData(data: unknown) {
    this.data = data;
  }
  setTiles(tiles: unknown) {
    this.tiles = tiles;
    return this;
  }
}

export class FakeMap {
  static instances: FakeMap[] = [];
  readonly sources = new Map<string, FakeSource>();
  readonly layers = new Map<
    string,
    {
      type?: string;
      source?: string;
      'source-layer'?: string;
      layout?: Record<string, unknown>;
      paint?: Record<string, unknown>;
    }
  >();
  readonly handlers: Array<{ event: string; layer?: string; handler: Handler }> = [];
  readonly cameraMoves: Array<Record<string, unknown>> = [];
  readonly styles: string[] = [];
  /** The layers the loaded style brings, and paint properties set on them since. */
  private styleLayers: FakeStyleLayer[] = [];
  private readonly stylePaint = new Map<string, Record<string, unknown>>();
  readonly remove = vi.fn();
  readonly canvas = { style: { cursor: '' } };

  constructor(
    readonly options: { style: string; container: HTMLElement } & Record<string, unknown>,
  ) {
    this.styles.push(options.style);
    FakeMap.instances.push(this);
  }

  on(event: string, layerOrHandler: string | Handler, handler?: Handler) {
    if (typeof layerOrHandler === 'string') {
      this.handlers.push({ event, layer: layerOrHandler, handler: handler! });
    } else this.handlers.push({ event, handler: layerOrHandler });
    return this;
  }
  /** Loads a style with [layers], as the basemap's style would, firing `style.load`. */
  loadStyle(layers: FakeStyleLayer[] = []) {
    this.styleLayers = layers;
    this.emit('style.load');
  }
  getStyle() {
    return { layers: [...this.styleLayers] };
  }
  setPaintProperty(id: string, property: string, value: unknown) {
    this.stylePaint.set(id, { ...this.stylePaint.get(id), [property]: value });
  }
  /** The paint properties set on a style layer since the style loaded. */
  paintOf(id: string): Record<string, unknown> {
    return this.stylePaint.get(id) ?? {};
  }
  emit(event: string, layer?: string, payload?: { lngLat: { lng: number; lat: number } }) {
    for (const h of this.handlers) if (h.event === event && h.layer === layer) h.handler(payload);
  }
  /** The OpenFreeMap styles bring their own vector tile source, with building footprints. */
  getSource(id: string) {
    return id === 'openmaptiles' ? new FakeSource(null) : this.sources.get(id);
  }
  addSource(id: string, spec: { data?: unknown } & Record<string, unknown>) {
    this.sources.set(id, new FakeSource(spec.data, spec));
  }
  getLayer(id: string) {
    return this.layers.get(id);
  }
  addLayer(layer: {
    id: string;
    type?: string;
    source?: string;
    'source-layer'?: string;
    layout?: Record<string, unknown>;
    paint?: Record<string, unknown>;
  }) {
    const { id, layout, paint, ...rest } = layer;
    this.layers.set(id, {
      ...rest,
      ...(layout ? { layout: { ...layout } } : {}),
      ...(paint ? { paint: { ...paint } } : {}),
    });
  }
  setLayoutProperty(id: string, property: string, value: unknown) {
    const layer = this.layers.get(id)!;
    layer.layout = { ...layer.layout, [property]: value };
  }
  /** Swapping styles drops every custom source and layer, as `setStyle(..., { diff: false })` does. */
  setStyle(style: string) {
    this.styles.push(style);
    this.sources.clear();
    this.layers.clear();
  }
  fitBounds(_bounds: unknown, options: Record<string, unknown>) {
    this.cameraMoves.push(options);
  }
  easeTo(options: Record<string, unknown>) {
    this.cameraMoves.push(options);
  }
  readonly controls: unknown[] = [];
  addControl(control: unknown) {
    this.controls.push(control);
  }
  getCanvas() {
    return this.canvas;
  }
  private camera = { lng: 0, lat: 0, zoom: 0 };
  getCenter() {
    return { lng: this.camera.lng, lat: this.camera.lat };
  }
  getZoom() {
    return this.camera.zoom;
  }
  /** Moves the camera as the athlete would by panning and zooming, firing `moveend` when it rests. */
  moveCamera({ center: [lng, lat], zoom }: { center: [number, number]; zoom: number }) {
    this.camera = { lng, lat, zoom };
    this.emit('moveend');
  }
}

export class FakeMarker {
  static instances: FakeMarker[] = [];
  lngLat: unknown = null;
  map: FakeMap | null = null;
  constructor(readonly options: { color?: string; element?: HTMLElement } = {}) {
    FakeMarker.instances.push(this);
  }
  setLngLat(lngLat: unknown) {
    this.lngLat = lngLat;
    return this;
  }
  addTo(map: FakeMap) {
    this.map = map;
    return this;
  }
  remove() {
    this.map = null;
    return this;
  }
}

export class FakeLngLatBounds {
  readonly points: unknown[] = [];
  constructor(sw: unknown, ne: unknown) {
    this.points.push(sw, ne);
  }
  extend(point: unknown) {
    this.points.push(point);
    return this;
  }
}

export class FakeNavigationControl {
  constructor(readonly options: Record<string, unknown> = {}) {}
}

export const fakeMaplibreModule = {
  Map: FakeMap,
  Marker: FakeMarker,
  LngLatBounds: FakeLngLatBounds,
  NavigationControl: FakeNavigationControl,
  setWorkerUrl: vi.fn(),
};

/** Hands a fake map to code that takes MapLibre's real `Map`, which it stands in for. */
export const asMapLibre = (map: FakeMap): MapLibreMap => map as unknown as MapLibreMap;

export function resetFakeMaplibre(): void {
  FakeMap.instances.length = 0;
  FakeMarker.instances.length = 0;
}
