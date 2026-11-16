import { setWorkerUrl } from 'maplibre-gl';
import { describe, expect, it, vi } from 'vitest';
import {
  addBuildingsLayer,
  setBuildingsVisible,
  tiltTo,
} from '../src/features/basemap/maplibreBasemap';
import { FakeMap, asMapLibre } from './support/fakeMaplibre';

vi.mock('maplibre-gl', () => import('./support/fakeMaplibre').then((m) => m.fakeMaplibreModule));

/** Every source module, as text, keyed by its path relative to this file. */
const sources = import.meta.glob<string>('../src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
});

describe('MapLibre basemap', () => {
  const newMap = () => new FakeMap({ style: 'basemap', container: document.createElement('div') });

  it('sets the MapLibre worker URL once when the basemap loads', () => {
    expect(setWorkerUrl).toHaveBeenCalledTimes(1);
  });

  it('is the only module that sets the MapLibre worker URL', () => {
    const setters = Object.entries(sources)
      .filter(([, source]) => source.includes('setWorkerUrl('))
      .map(([path]) => path);

    expect(setters).toEqual(['../src/features/basemap/maplibreBasemap.ts']);
  });

  it('extrudes buildings in the colour and opacity it is given', () => {
    const map = newMap();

    addBuildingsLayer(asMapLibre(map), { color: '#111827', opacity: 0.35 }, true);

    expect(map.layers.get('3d-buildings')).toMatchObject({
      layout: { visibility: 'visible' },
      paint: { 'fill-extrusion-color': '#111827', 'fill-extrusion-opacity': 0.35 },
    });
  });

  it('shows and hides the buildings, and adds them only once', () => {
    const map = newMap();
    addBuildingsLayer(asMapLibre(map), { color: '#111827', opacity: 0.35 }, false);
    addBuildingsLayer(asMapLibre(map), { color: '#ffffff', opacity: 1 }, true);
    expect(map.layers.get('3d-buildings')).toMatchObject({
      layout: { visibility: 'none' },
      paint: { 'fill-extrusion-color': '#111827' },
    });

    setBuildingsVisible(asMapLibre(map), true);

    expect(map.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'visible' });
  });

  it('tilts into a perspective: buildings follow the tilt, and the map moves with its camera', () => {
    const map = newMap();
    addBuildingsLayer(asMapLibre(map), { color: '#111827', opacity: 0.35 }, false);
    const move = vi.fn();

    tiltTo(asMapLibre(map), '3d', move);
    expect(map.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'visible' });
    expect(move).toHaveBeenLastCalledWith({ pitch: 55, bearing: -20, duration: 600 });

    tiltTo(asMapLibre(map), '2d', move);
    expect(map.layers.get('3d-buildings')!.layout).toEqual({ visibility: 'none' });
    expect(move).toHaveBeenLastCalledWith({ pitch: 0, bearing: 0, duration: 600 });
  });
});
