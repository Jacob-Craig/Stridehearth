/**
 * The MapLibre basemap shared by every map: the OpenFreeMap vector styles, the MapLibre worker
 * setup, the extruded 3D-buildings layer, and tilting a map between perspectives. Map adapters build
 * their own sources and layers on top.
 */
import { type Map as MapLibreMap, setWorkerUrl } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import maplibreWorkerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url';
import type { ColorScheme } from '../../lib/colorScheme';
import {
  isTilted,
  type MapPerspective,
  PERSPECTIVE_CAMERAS,
  PERSPECTIVE_DURATION_MS,
  type PerspectiveCamera,
} from './mapPerspective';

// Bundled explicitly, since MapLibre's own worker lookup does not survive Vite's dependency bundling.
// A module body runs once, so the worker URL is set once however many map adapters import it.
setWorkerUrl(maplibreWorkerUrl);

export const BASEMAP_STYLES: Readonly<Record<ColorScheme, string>> = {
  light: 'https://tiles.openfreemap.org/styles/positron',
  dark: 'https://tiles.openfreemap.org/styles/dark',
};

/** The vector tile source the OpenFreeMap styles bring, with building footprints. */
const OPENFREEMAP_SOURCE = 'openmaptiles';
const BUILDINGS_LAYER = '3d-buildings';

export interface BuildingsStyle {
  readonly color: string;
  /** From 0 (invisible) to 1 (opaque). */
  readonly opacity: number;
}

const visibility = (visible: boolean) => (visible ? 'visible' : 'none');

/**
 * Adds extruded buildings from the OpenFreeMap style's own tiles. Call it on every `style.load`,
 * since a style swap drops custom layers; it does nothing if the layer is already there or the style
 * has no building tiles.
 */
export function addBuildingsLayer(map: MapLibreMap, style: BuildingsStyle, visible: boolean): void {
  if (!map.getSource(OPENFREEMAP_SOURCE) || map.getLayer(BUILDINGS_LAYER)) return;
  map.addLayer({
    id: BUILDINGS_LAYER,
    type: 'fill-extrusion',
    source: OPENFREEMAP_SOURCE,
    'source-layer': 'building',
    minzoom: 13,
    layout: { visibility: visibility(visible) },
    paint: {
      'fill-extrusion-color': style.color,
      'fill-extrusion-height': ['coalesce', ['get', 'render_height'], 0],
      'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], 0],
      'fill-extrusion-opacity': style.opacity,
    },
  });
}

/** Shows or hides the extruded buildings, if the layer has been added. */
export function setBuildingsVisible(map: MapLibreMap, visible: boolean): void {
  if (!map.getLayer(BUILDINGS_LAYER)) return;
  map.setLayoutProperty(BUILDINGS_LAYER, 'visibility', visibility(visible));
}

/**
 * Tilts a map into a perspective: shows or hides the buildings, then hands the perspective's camera
 * and the shared tilt duration to `move`, which makes the map's own camera move with it.
 */
export function tiltTo(
  map: MapLibreMap,
  perspective: MapPerspective,
  move: (camera: PerspectiveCamera & { readonly duration: number }) => void,
): void {
  setBuildingsVisible(map, isTilted(perspective));
  move({ ...PERSPECTIVE_CAMERAS[perspective], duration: PERSPECTIVE_DURATION_MS });
}
