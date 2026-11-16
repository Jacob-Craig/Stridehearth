/**
 * The 2D/3D perspective every map shares: the camera tilt of each, how long tilting takes, and how far
 * the athlete may tilt by hand. Plain data with no MapLibre import, so the UI can use it without
 * pulling MapLibre into the main bundle.
 */

/** A map seen from straight above, or tilted into perspective with extruded buildings. */
export type MapPerspective = '2d' | '3d';

/** The camera tilt of a perspective. */
export interface PerspectiveCamera {
  readonly pitch: number;
  readonly bearing: number;
}

/** The camera tilt of each perspective, shared so every map tilts the same way. */
export const PERSPECTIVE_CAMERAS: Readonly<Record<MapPerspective, PerspectiveCamera>> = {
  '2d': { pitch: 0, bearing: 0 },
  '3d': { pitch: 55, bearing: -20 },
};

/** How long a map takes to tilt between perspectives. */
export const PERSPECTIVE_DURATION_MS = 600;

/** The steepest tilt a map allows the athlete to drag to. */
const MAX_PITCH = 70;

/** Whether a perspective tilts the map, which is when extruded buildings make sense. */
export const isTilted = (perspective: MapPerspective): boolean => perspective === '3d';

/**
 * The camera options a map is created with in a perspective. A map opening on bounds fits them as
 * seen from the perspective's tilt, with `fitPadding` pixels around them.
 */
export const perspectiveCameraOptions = (perspective: MapPerspective, fitPadding: number) => {
  const camera = PERSPECTIVE_CAMERAS[perspective];
  return { ...camera, maxPitch: MAX_PITCH, fitBoundsOptions: { padding: fitPadding, ...camera } };
};
