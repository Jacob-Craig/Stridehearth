/**
 * The `HeatmapLayer` adapter for the backend's raster density renderer (ADR-0008): 512-pixel PNG tiles,
 * already glowing and coloured by the server, drawn over a muted dark basemap. Only this module touches
 * the MapLibre API for the **Heatmap**.
 */
import {
  Map as MapLibreMap,
  type MapOptions,
  NavigationControl,
  type RasterTileSource,
} from 'maplibre-gl';
import type { HeatmapRendererDto } from '../../api/generated/types.gen';
import { withFilterParams } from '../../api/heatmap';
import { isTilted, perspectiveCameraOptions } from '../basemap/mapPerspective';
import {
  BASEMAP_STYLES,
  type BuildingsStyle,
  addBuildingsLayer,
  tiltTo,
} from '../basemap/maplibreBasemap';
import type {
  HeatmapLayerHandle,
  HeatmapLayerOptions,
  HeatmapTileKey,
  HeatmapView,
} from './heatmapLayers';

const SOURCE = 'heatmap';
const LAYER = 'heatmap';
const TILE_SIZE = 512;
const FIT_PADDING = 40;
const WORLD_VIEW: HeatmapView = { center: [0, 20], zoom: 1 };

/**
 * How far the basemap's labels and roads fade, so the glow carries the picture: labels stay legible
 * enough to find a place, and roads become a faint web the routes are read against. Water, land and
 * borders keep the dark style's own look.
 */
const MUTED_LABEL_OPACITY = 0.4;
const MUTED_ROAD_OPACITY = 0.3;
/**
 * Buildings sit above the heatmap, so they are dark and mostly see-through: just enough to read the
 * city's shape on the dark basemap while the glow shows through them.
 */
const BUILDINGS: BuildingsStyle = { color: '#1e293b', opacity: 0.35 };
/** The OpenMapTiles source layer holding the dark style's roads, paths and railways. */
const ROADS_SOURCE_LAYER = 'transportation';

/**
 * MapLibre fetches tiles from its workers, so the URL must be absolute. Built by concatenation, as a
 * `URL` would percent-encode the `{z}/{x}/{y}` placeholders MapLibre fills in.
 */
const tileUrl = (
  renderer: HeatmapRendererDto,
  { revision, filterParams }: HeatmapTileKey,
): string =>
  window.location.origin +
  withFilterParams(
    renderer.tileUrlTemplate.replace('{revision}', encodeURIComponent(revision)),
    filterParams,
  );

function muteBasemap(map: MapLibreMap): void {
  for (const layer of map.getStyle().layers) {
    if (layer.type === 'symbol') {
      map.setPaintProperty(layer.id, 'text-opacity', MUTED_LABEL_OPACITY);
      map.setPaintProperty(layer.id, 'icon-opacity', MUTED_LABEL_OPACITY);
    }
    if (layer.type === 'line' && layer['source-layer'] === ROADS_SOURCE_LAYER)
      map.setPaintProperty(layer.id, 'line-opacity', MUTED_ROAD_OPACITY);
  }
}

/**
 * Where the map opens: the remembered view, else the starting bounds, else the whole world. The
 * perspective adds the tilt, and frames the bounds as seen from it.
 */
function initialCamera(
  view: HeatmapLayerOptions['view'],
  startingBounds: HeatmapLayerOptions['startingBounds'],
): Pick<MapOptions, 'center' | 'zoom' | 'bounds'> {
  if (view) return view;
  if (!startingBounds) return WORLD_VIEW;
  return {
    bounds: [
      [startingBounds.west, startingBounds.south],
      [startingBounds.east, startingBounds.north],
    ],
  };
}

export function createHeatmapLayer(options: HeatmapLayerOptions): HeatmapLayerHandle {
  const { container, renderer, startingBounds, view, onViewChange } = options;
  let tileKey = options.tileKey;
  let perspective = options.perspective;

  // The Heatmap reads best on the dark basemap whatever the app theme.
  const map = new MapLibreMap({
    container,
    style: BASEMAP_STYLES.dark,
    ...initialCamera(view, startingBounds),
    // The fit frames the bounds as seen from the chosen tilt, as on the Activity route map.
    ...perspectiveCameraOptions(perspective, FIT_PADDING),
  });
  map.addControl(new NavigationControl({ visualizePitch: true }), 'top-left');

  map.on('moveend', () => {
    const { lng, lat } = map.getCenter();
    onViewChange({ center: [lng, lat], zoom: map.getZoom() });
  });

  map.on('style.load', () => {
    if (map.getSource(SOURCE)) return;
    muteBasemap(map);
    map.addSource(SOURCE, {
      type: 'raster',
      tiles: [tileUrl(renderer, tileKey)],
      tileSize: TILE_SIZE,
      // MapLibre enlarges the most detailed tiles beyond this.
      maxzoom: renderer.maxZoom,
    });
    map.addLayer({
      id: LAYER,
      type: 'raster',
      source: SOURCE,
      paint: { 'raster-resampling': 'linear', 'raster-fade-duration': 150 },
    });
    // Added after the heatmap, so the buildings draw over the glow rather than burying it.
    addBuildingsLayer(map, BUILDINGS, isTilted(perspective));
  });

  return {
    setTileKey(next) {
      if (next.revision === tileKey.revision && next.filterParams === tileKey.filterParams) return;
      tileKey = next;
      // Each revision x filter has its own tile URLs, which the browser caches independently.
      map.getSource<RasterTileSource>(SOURCE)?.setTiles([tileUrl(renderer, tileKey)]);
    },
    setPerspective(next) {
      if (next === perspective) return;
      perspective = next;
      tiltTo(map, perspective, (camera) => map.easeTo(camera));
    },
    destroy() {
      map.remove();
    },
  };
}
