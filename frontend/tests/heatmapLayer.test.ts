import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { HeatmapRendererDto } from '../src/api/generated/types.gen';
import { type HeatmapFilterParams, NO_FILTER_PARAMS } from '../src/api/heatmap';
import { DEFAULT_HEATMAP_FILTER, toParams } from '../src/features/heatmap/heatmapFilter';
import { createHeatmapLayer } from '../src/features/heatmap/rasterDensityHeatmapLayer';
import type { ActivitySport } from '../src/lib/format';
import { stubColourScheme } from './support/colourScheme';
import { FakeMap, FakeNavigationControl, resetFakeMaplibre } from './support/fakeMaplibre';

vi.mock('maplibre-gl', () => import('./support/fakeMaplibre').then((m) => m.fakeMaplibreModule));

const renderer: HeatmapRendererDto = {
  id: 'raster-density',
  tileUrlTemplate: '/api/heatmap/tiles/raster-density-v1/{revision}/{z}/{x}/{y}',
  maxZoom: 16,
};

const origin = window.location.origin;

/** The tile parameters of a filter on [sports] alone, at all time. */
const sportsOnly = (...sports: ActivitySport[]) => toParams({ ...DEFAULT_HEATMAP_FILTER, sports });

/** A few layers of the OpenFreeMap dark style the Heatmap is drawn over. */
const basemap = [
  { id: 'water', type: 'fill', 'source-layer': 'water' },
  { id: 'highway_minor', type: 'line', 'source-layer': 'transportation' },
  { id: 'highway_major_casing', type: 'line', 'source-layer': 'transportation' },
  { id: 'railway', type: 'line', 'source-layer': 'transportation' },
  { id: 'highway_name_other', type: 'symbol', 'source-layer': 'transportation_name' },
  { id: 'boundary_country', type: 'line', 'source-layer': 'boundary' },
  { id: 'place_city', type: 'symbol', 'source-layer': 'place' },
];

type Options = Parameters<typeof createHeatmapLayer>[0];

function mountHeatmap(
  startingBounds: Options['startingBounds'],
  {
    view = null,
    onViewChange = () => {},
    perspective = '2d',
    filterParams = NO_FILTER_PARAMS,
  }: Partial<Pick<Options, 'view' | 'onViewChange' | 'perspective'>> & {
    filterParams?: HeatmapFilterParams;
  } = {},
) {
  const handle = createHeatmapLayer({
    container: document.createElement('div'),
    renderer,
    tileKey: { revision: '3-41', filterParams },
    startingBounds,
    view,
    onViewChange,
    perspective,
  });
  const map = FakeMap.instances.at(-1)!;
  map.loadStyle(basemap);
  return { handle, map };
}

/** WCAG relative luminance of a `#rrggbb` colour, from 0 (black) to 1 (white). */
function relativeLuminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((at) => {
    const channel = Number.parseInt(hex.slice(at, at + 2), 16) / 255;
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!;
}

describe('raster density HeatmapLayer', () => {
  beforeEach(() => resetFakeMaplibre());
  afterEach(() => vi.unstubAllGlobals());

  it('adds 512-pixel raster tiles from the descriptor URL template at the revision, up to its max zoom', () => {
    const { map } = mountHeatmap(null);

    expect(map.sources.get('heatmap')!.spec).toMatchObject({
      type: 'raster',
      tiles: [`${origin}/api/heatmap/tiles/raster-density-v1/3-41/{z}/{x}/{y}`],
      tileSize: 512,
      maxzoom: 16,
    });
    expect(map.layers.get('heatmap')).toMatchObject({ type: 'raster', source: 'heatmap' });
  });

  it.each(['light', 'dark'] as const)(
    'draws over the dark basemap with its labels and roads muted in the %s app theme',
    (theme) => {
      stubColourScheme(theme);

      const { map } = mountHeatmap(null);

      expect(map.options.style).toBe('https://tiles.openfreemap.org/styles/dark');
      for (const road of ['highway_minor', 'highway_major_casing', 'railway'])
        expect(map.paintOf(road)['line-opacity']).toBeLessThanOrEqual(0.35);
      for (const label of ['place_city', 'highway_name_other']) {
        expect(map.paintOf(label)['text-opacity']).toBeLessThanOrEqual(0.5);
        expect(map.paintOf(label)['icon-opacity']).toBeLessThanOrEqual(0.5);
      }
      // Only roads and labels: water, land and borders keep the basemap's own look.
      expect(map.paintOf('water')).toEqual({});
      expect(map.paintOf('boundary_country')).toEqual({});
    },
  );

  it('opens on the starting bounds, or on the whole world without any', () => {
    const bounds = { west: -0.28, south: 51.46, east: -0.08, north: 51.56 };

    expect(mountHeatmap(bounds).map.options).toMatchObject({
      bounds: [
        [-0.28, 51.46],
        [-0.08, 51.56],
      ],
    });
    expect(mountHeatmap(null).map.options).toMatchObject({ center: [0, 20], zoom: 1 });
  });

  it('reopens on a remembered view in place of the starting bounds', () => {
    const bounds = { west: -0.28, south: 51.46, east: -0.08, north: 51.56 };
    const { map } = mountHeatmap(bounds, { view: { center: [2.35, 48.86], zoom: 12.5 } });

    expect(map.options).toMatchObject({ center: [2.35, 48.86], zoom: 12.5 });
    expect(map.options).not.toHaveProperty('bounds');
  });

  it('reports the view each time the camera comes to rest', () => {
    const onViewChange = vi.fn();
    const { map } = mountHeatmap(null, { onViewChange });

    map.moveCamera({ center: [-0.17, 51.515], zoom: 13.25 });

    expect(onViewChange).toHaveBeenCalledExactlyOnceWith({ center: [-0.17, 51.515], zoom: 13.25 });
  });

  it('switches the source to the new revision tiles, keeping the camera', () => {
    const { handle, map } = mountHeatmap(null);

    map.moveCamera({ center: [-0.17, 51.515], zoom: 13.25 });

    handle.setTileKey({ revision: '4-57', filterParams: NO_FILTER_PARAMS });

    expect(map.sources.get('heatmap')!.tiles).toEqual([
      `${origin}/api/heatmap/tiles/raster-density-v1/4-57/{z}/{x}/{y}`,
    ]);
    expect(map.cameraMoves).toEqual([]);
    expect(map.getCenter()).toEqual({ lng: -0.17, lat: 51.515 });
  });

  it('appends the Heatmap Filter to the tile URLs, and switches to another filter tiles at the same revision', () => {
    const { handle, map } = mountHeatmap(null, { filterParams: sportsOnly('RUN') });

    expect(map.sources.get('heatmap')!.spec).toMatchObject({
      tiles: [`${origin}/api/heatmap/tiles/raster-density-v1/3-41/{z}/{x}/{y}?sports=RUN`],
    });

    handle.setTileKey({ revision: '3-41', filterParams: sportsOnly('RIDE', 'SWIM') });
    expect(map.sources.get('heatmap')!.tiles).toEqual([
      `${origin}/api/heatmap/tiles/raster-density-v1/3-41/{z}/{x}/{y}?sports=RIDE&sports=SWIM`,
    ]);

    handle.setTileKey({ revision: '4-57', filterParams: sportsOnly('RIDE', 'SWIM') });
    expect(map.sources.get('heatmap')!.tiles).toEqual([
      `${origin}/api/heatmap/tiles/raster-density-v1/4-57/{z}/{x}/{y}?sports=RIDE&sports=SWIM`,
    ]);

    handle.setTileKey({ revision: '4-57', filterParams: NO_FILTER_PARAMS });
    expect(map.sources.get('heatmap')!.tiles).toEqual([
      `${origin}/api/heatmap/tiles/raster-density-v1/4-57/{z}/{x}/{y}`,
    ]);
  });

  it('opens flat with hidden buildings in 2D, and tilted with buildings shown in 3D', () => {
    const flat = mountHeatmap(null, { perspective: '2d' }).map;
    expect(flat.options).toMatchObject({ pitch: 0, bearing: 0 });
    expect(flat.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'none' });

    const tilted = mountHeatmap(null, { perspective: '3d' }).map;
    expect(tilted.options).toMatchObject({ pitch: 55, bearing: -20 });
    const fittedTilt = mountHeatmap(
      { west: -0.28, south: 51.46, east: -0.08, north: 51.56 },
      { perspective: '3d' },
    ).map;
    expect(fittedTilt.options.fitBoundsOptions).toMatchObject({ pitch: 55, bearing: -20 });
    expect(tilted.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'visible' });
  });

  it('tilts into 3D with buildings, and back to a flat 2D map without them', () => {
    const { handle, map } = mountHeatmap(null);

    handle.setPerspective('3d');
    expect(map.cameraMoves.at(-1)).toMatchObject({ pitch: 55, bearing: -20 });
    expect(map.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'visible' });

    handle.setPerspective('2d');
    expect(map.cameraMoves.at(-1)).toMatchObject({ pitch: 0, bearing: 0 });
    expect(map.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'none' });
  });

  it('shows the buildings when 3D is chosen before the basemap style has loaded', () => {
    const handle = createHeatmapLayer({
      container: document.createElement('div'),
      renderer,
      tileKey: { revision: '3-41', filterParams: NO_FILTER_PARAMS },
      startingBounds: null,
      view: null,
      onViewChange: () => {},
      perspective: '2d',
    });
    const map = FakeMap.instances.at(-1)!;

    handle.setPerspective('3d');
    map.loadStyle(basemap);

    expect(map.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'visible' });
  });

  it('draws the buildings above the raster heatmap in dark, see-through tones', () => {
    const { map } = mountHeatmap(null, { perspective: '3d' });

    const order = [...map.layers.keys()];
    expect(order.indexOf('3d-buildings')).toBeGreaterThan(order.indexOf('heatmap'));
    const paint = map.layers.get('3d-buildings')!.paint!;
    expect(paint['fill-extrusion-opacity']).toBeLessThanOrEqual(0.5);
    expect(relativeLuminance(paint['fill-extrusion-color'] as string)).toBeLessThan(0.1);
    // The heatmap itself stays drawn in 3D, never hidden behind the buildings.
    expect(map.layers.get('heatmap')!.layout?.visibility ?? 'visible').toBe('visible');
  });

  it('has zoom buttons and a compass showing the tilt, as on the Activity route map', () => {
    const { map } = mountHeatmap(null, { perspective: '3d' });

    const navigation = map.controls.filter((c) => c instanceof FakeNavigationControl);
    expect(navigation).toHaveLength(1);
    // The compass resets the bearing after a rotating drag; MapLibre shows it unless told not to.
    expect(navigation[0]!.options).toEqual({ visualizePitch: true });
  });

  it('removes the map on destroy', () => {
    const { handle, map } = mountHeatmap(null);

    handle.destroy();

    expect(map.remove).toHaveBeenCalledTimes(1);
  });
});
