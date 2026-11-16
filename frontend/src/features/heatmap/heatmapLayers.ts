/**
 * The `HeatmapLayer` contract and the adapter for each backend renderer id (ADR-0007, ADR-0008), loaded on
 * demand so MapLibre stays out of the main bundle until the Heatmap is opened.
 */
import type { GeoBoundsDto, HeatmapRendererDto } from '../../api/generated/types.gen';
import type { HeatmapFilterParams } from '../../api/heatmap';
import type { MapPerspective } from '../basemap/mapPerspective';
import { sharedImport } from '../telemetry/lazyRenderers';

/**
 * What picks a set of tile URLs: the athlete's heatmap revision, which lets the browser cache them for good,
 * and the Heatmap Filter. Each combination has its own URLs.
 */
export interface HeatmapTileKey {
  readonly revision: string;
  readonly filterParams: HeatmapFilterParams;
}

export interface HeatmapLayerOptions {
  readonly container: HTMLElement;
  /** The active renderer's descriptor, whose tile URL template the tiles come from. */
  readonly renderer: HeatmapRendererDto;
  /** The tiles the map opens with. */
  readonly tileKey: HeatmapTileKey;
  /**
   * The busiest area of the athlete's routes, where the map opens without a remembered `view`; the
   * whole world when both are `null`.
   */
  readonly startingBounds: GeoBoundsDto | null;
  /**
   * The athlete's last viewed area, which the map reopens on in place of `startingBounds`; `null`
   * when there is none, or no routes to show.
   */
  readonly view: HeatmapView | null;
  /** Called with the camera each time the athlete stops moving the map. */
  readonly onViewChange: (view: HeatmapView) => void;
  /** Flat, or tilted with extruded buildings; later changes go through `setPerspective`. */
  readonly perspective: MapPerspective;
}

/** A point on the map in degrees, in MapLibre's `[longitude, latitude]` order. */
export type LngLat = [longitude: number, latitude: number];

/** A camera position on the Heatmap: the centre and the zoom. */
export interface HeatmapView {
  readonly center: LngLat;
  readonly zoom: number;
}

export interface HeatmapLayerHandle {
  /** Moves the map to the tiles of another revision or Heatmap Filter, keeping the camera. */
  setTileKey(tileKey: HeatmapTileKey): void;
  /** Tilts the map into 3D with buildings, or back to a flat 2D map, keeping where it looks. */
  setPerspective(perspective: MapPerspective): void;
  destroy(): void;
}

export interface HeatmapLayerModule {
  createHeatmapLayer(options: HeatmapLayerOptions): HeatmapLayerHandle;
}

export const heatmapLayerLoaders: Readonly<Record<string, () => Promise<HeatmapLayerModule>>> = {
  'raster-density': sharedImport(() => import('./rasterDensityHeatmapLayer')),
};
