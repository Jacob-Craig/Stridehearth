import type { MapPerspective } from './mapPerspective';

const PERSPECTIVE_LABELS: ReadonlyArray<readonly [MapPerspective, string]> = [
  ['2d', '2D'],
  ['3d', '3D'],
];

interface PerspectiveToggleProps {
  readonly perspective: MapPerspective;
  readonly onChange: (perspective: MapPerspective) => void;
  /** Classes for the group, which each map places and colours to suit its own surroundings. */
  readonly className: string;
  /** Classes for each button, before the shared pressed-state styling. */
  readonly buttonClassName: string;
}

/** The 2D/3D buttons every map shares; only types are imported, so MapLibre stays out of the bundle. */
export function PerspectiveToggle({
  perspective,
  onChange,
  className,
  buttonClassName,
}: PerspectiveToggleProps) {
  return (
    <fieldset className={className}>
      <legend className="sr-only">Map perspective</legend>
      {PERSPECTIVE_LABELS.map(([value, label]) => (
        <button
          key={value}
          type="button"
          aria-pressed={perspective === value}
          onClick={() => onChange(value)}
          className={`${buttonClassName} aria-pressed:bg-blue-600 aria-pressed:text-white aria-pressed:hover:bg-blue-600`}
        >
          {label}
        </button>
      ))}
    </fieldset>
  );
}
