/**
 * Thin adapter over `MapLibre GL JS`: the WebGL route map of an **Activity** on the shared OpenFreeMap
 * basemap. Only this module touches the MapLibre API for the route map.
 */
import {
  type GeoJSONSource,
  LngLatBounds,
  Map as MapLibreMap,
  Marker,
  NavigationControl,
} from 'maplibre-gl';
import type { ColorScheme } from '../../lib/colorScheme';
import { type MapPerspective, isTilted, perspectiveCameraOptions } from '../basemap/mapPerspective';
import {
  BASEMAP_STYLES,
  type BuildingsStyle,
  addBuildingsLayer,
  tiltTo,
} from '../basemap/maplibreBasemap';
import { BEST_EFFORT_HIGHLIGHT_COLOR } from './bestEffortHighlight';
import { type FrameClock, animationFrameClock, coalescePerFrame } from './frameCoalescer';
import type { LngLat } from './sampleLookup';
import type { ScrubberMarker } from './scrubberBus';

export const BEST_EFFORT_HIGHLIGHT_SOURCE = 'best-effort-highlight-source';

const PALETTE = {
  light: { route: '#2563eb', casing: '#ffffff' },
  dark: { route: '#60a5fa', casing: '#0f172a' },
} as const;
const BUILDINGS: Readonly<Record<ColorScheme, BuildingsStyle>> = {
  light: { color: '#d4d4d8', opacity: 0.8 },
  dark: { color: '#3f3f46', opacity: 0.8 },
};
const FIT_PADDING = 40;
const ROUTE_HIT_LAYER = 'route-hit';

export interface RouteMapOptions {
  readonly container: HTMLElement;
  /** The route's positions, at least two. */
  readonly coordinates: readonly LngLat[];
  readonly theme: ColorScheme;
  readonly perspective: MapPerspective;
  /**
   * Called with the route vertex nearest the pointer, or `null` when it leaves the route; at most
   * once per animation frame.
   */
  readonly onRouteVertexHover: (vertex: number | null) => void;
  /** Schedules the route-hover search; animation frames unless a test steps them by hand. */
  readonly clock?: FrameClock;
}

export interface RouteMapHandle {
  /** The scrubber marker, moved imperatively by the scrubber bus. */
  readonly marker: ScrubberMarker;
  /** Highlights a **Best Effort** route path; a path of fewer than two positions clears it. */
  setHighlight(path: readonly LngLat[]): void;
  setTheme(theme: ColorScheme): void;
  setPerspective(perspective: MapPerspective): void;
  destroy(): void;
}

/** MapLibre takes positions as mutable tuples. */
const toMapPosition = ([lng, lat]: LngLat): [number, number] => [lng, lat];

const lineFeature = (path: readonly LngLat[]): GeoJSON.Feature<GeoJSON.LineString> => ({
  type: 'Feature',
  properties: {},
  geometry: { type: 'LineString', coordinates: path.map(toMapPosition) },
});

const NO_FEATURES: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] };

/** A LineString needs two positions, so a shorter highlight path is drawn as no feature at all. */
const highlightData = (path: readonly LngLat[]): GeoJSON.GeoJSON =>
  path.length >= 2 ? lineFeature(path) : NO_FEATURES;

/** Index of the position nearest `pointer` (equirectangular approximation, fine at route scale). */
function nearestVertex(
  coordinates: readonly LngLat[],
  pointer: { lng: number; lat: number },
): number {
  const lngScale = Math.cos((pointer.lat * Math.PI) / 180);
  let nearest = 0;
  let nearestDistance = Number.POSITIVE_INFINITY;
  coordinates.forEach(([lng, lat], vertex) => {
    const dx = (lng - pointer.lng) * lngScale;
    const dy = lat - pointer.lat;
    const distance = dx * dx + dy * dy;
    if (distance < nearestDistance) {
      nearestDistance = distance;
      nearest = vertex;
    }
  });
  return nearest;
}

export function createRouteMap(options: RouteMapOptions): RouteMapHandle {
  const { container, coordinates, onRouteVertexHover, clock = animationFrameClock } = options;
  let theme = options.theme;
  let perspective = options.perspective;
  let highlight: readonly LngLat[] = [];

  const start = toMapPosition(coordinates[0]!);
  const end = toMapPosition(coordinates.at(-1)!);
  const bounds = coordinates.reduce(
    (routeBounds, position) => routeBounds.extend(toMapPosition(position)),
    new LngLatBounds(start, start),
  );

  const map = new MapLibreMap({
    container,
    style: BASEMAP_STYLES[theme],
    bounds,
    ...perspectiveCameraOptions(perspective, FIT_PADDING),
  });
  map.addControl(new NavigationControl({ visualizePitch: true }), 'top-left');

  const startEnd = [
    new Marker({ color: '#16a34a' }).setLngLat(start).addTo(map),
    new Marker({ color: '#dc2626' }).setLngLat(end).addTo(map),
  ];

  // Custom sources and layers are re-added after every style swap, which drops them.
  map.on('style.load', () => {
    const palette = PALETTE[theme];
    const highlightColor = BEST_EFFORT_HIGHLIGHT_COLOR[theme];
    addBuildingsLayer(map, BUILDINGS[theme], isTilted(perspective));
    if (map.getSource('route')) return;
    const roundedLineLayout = { 'line-join': 'round', 'line-cap': 'round' } as const;
    map.addSource('route', { type: 'geojson', data: lineFeature(coordinates) });
    map.addLayer({
      id: 'route-casing',
      type: 'line',
      source: 'route',
      layout: roundedLineLayout,
      paint: { 'line-color': palette.casing, 'line-width': 7 },
    });
    map.addLayer({
      id: 'route-line',
      type: 'line',
      source: 'route',
      layout: roundedLineLayout,
      paint: { 'line-color': palette.route, 'line-width': 4 },
    });
    // A wide, invisible line gives the route a forgiving hover target.
    map.addLayer({
      id: ROUTE_HIT_LAYER,
      type: 'line',
      source: 'route',
      paint: { 'line-color': '#000000', 'line-opacity': 0, 'line-width': 24 },
    });
    map.addSource(BEST_EFFORT_HIGHLIGHT_SOURCE, {
      type: 'geojson',
      data: highlightData(highlight),
    });
    map.addLayer({
      id: 'best-effort-highlight-line',
      type: 'line',
      source: BEST_EFFORT_HIGHLIGHT_SOURCE,
      layout: roundedLineLayout,
      paint: { 'line-color': highlightColor, 'line-width': 6 },
    });
  });

  // The nearest-vertex search is linear in the route, so it runs at most once per frame.
  const routeHovers = coalescePerFrame<{ readonly lng: number; readonly lat: number } | null>(
    (pointer) => onRouteVertexHover(pointer && nearestVertex(coordinates, pointer)),
    clock,
  );
  // Layer-scoped listeners survive style swaps, since the layer id is re-added.
  map.on('mousemove', ROUTE_HIT_LAYER, (event) => {
    map.getCanvas().style.cursor = 'crosshair';
    routeHovers.push(event.lngLat);
  });
  map.on('mouseleave', ROUTE_HIT_LAYER, () => {
    map.getCanvas().style.cursor = '';
    routeHovers.push(null);
  });

  const cursorElement = document.createElement('div');
  cursorElement.className =
    'size-3.5 rounded-full border-2 border-white bg-amber-500 shadow-md pointer-events-none';
  const cursor = new Marker({ element: cursorElement });
  let cursorShown = false;

  return {
    marker: {
      moveTo(position) {
        cursor.setLngLat([position[0], position[1]]);
        if (!cursorShown) cursor.addTo(map);
        cursorShown = true;
      },
      hide() {
        if (cursorShown) cursor.remove();
        cursorShown = false;
      },
    },
    setHighlight(path) {
      highlight = path;
      void map.getSource<GeoJSONSource>(BEST_EFFORT_HIGHLIGHT_SOURCE)?.setData(highlightData(path));
    },
    setTheme(next) {
      if (next === theme) return;
      theme = next;
      map.setStyle(BASEMAP_STYLES[theme], { diff: false });
    },
    setPerspective(next) {
      if (next === perspective) return;
      perspective = next;
      tiltTo(map, perspective, (camera) =>
        map.fitBounds(bounds, { padding: FIT_PADDING, ...camera }),
      );
    },
    destroy() {
      routeHovers.cancel();
      for (const marker of startEnd) marker.remove();
      cursor.remove();
      map.remove();
    },
  };
}
