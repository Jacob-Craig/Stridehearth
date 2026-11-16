import { ACTIVITY_SPORTS, type ActivitySport, formatSport } from '../../lib/format';
import { type HeatmapFilter, isAllTime, isInvertedRange, isYear, yearRange } from './heatmapFilter';

interface HeatmapFilterControlsProps {
  readonly filter: HeatmapFilter;
  /** The years with Activities on the Heatmap, newest first; `undefined` while they load. */
  readonly years: readonly number[] | undefined;
  readonly onChange: (filter: HeatmapFilter) => void;
}

// The Heatmap sits on a dark basemap whatever the app theme, so its controls are dark too.
const fieldClass =
  'mt-1 block w-full rounded-md border border-slate-700 bg-slate-950 px-2 py-1 text-sm text-slate-100 aria-invalid:border-red-400';

const chipClass =
  'rounded-full border border-slate-700 px-2.5 py-0.5 text-xs font-medium text-slate-300 hover:bg-slate-800 aria-pressed:border-orange-400 aria-pressed:bg-orange-500/20 aria-pressed:text-orange-100';

/** The **Heatmap Filter** over the map: a date range with year shortcuts, and any set of sports. */
export function HeatmapFilterControls({ filter, years, onChange }: HeatmapFilterControlsProps) {
  const allTime = isAllTime(filter);
  // Typed dates can still cross over despite `min` and `max`; the map says so.
  const invertedRange = isInvertedRange(filter);
  const toggleSport = (sport: ActivitySport, included: boolean) =>
    onChange({
      ...filter,
      sports: included ? [...filter.sports, sport] : filter.sports.filter((s) => s !== sport),
    });

  return (
    <form
      aria-label="Heatmap Filter"
      onSubmit={(event) => event.preventDefault()}
      className="w-full space-y-3 rounded-lg bg-slate-900/90 p-3 text-sm text-slate-200 shadow-lg [color-scheme:dark] sm:w-80"
    >
      <div className="flex flex-wrap gap-1.5">
        <button
          type="button"
          aria-pressed={allTime}
          className={chipClass}
          onClick={() => onChange({ ...filter, from: null, to: null })}
        >
          All time
        </button>
        {years?.map((year) => (
          <button
            key={year}
            type="button"
            aria-pressed={isYear(filter, year)}
            className={chipClass}
            onClick={() => onChange({ ...filter, ...yearRange(year) })}
          >
            {year}
          </button>
        ))}
      </div>
      <div className="grid grid-cols-2 gap-2">
        <label className="block text-xs font-medium text-slate-300">
          From
          <input
            type="date"
            value={filter.from ?? ''}
            max={filter.to ?? undefined}
            aria-invalid={invertedRange}
            onChange={(event) => onChange({ ...filter, from: event.target.value || null })}
            className={fieldClass}
          />
        </label>
        <label className="block text-xs font-medium text-slate-300">
          To
          <input
            type="date"
            value={filter.to ?? ''}
            min={filter.from ?? undefined}
            aria-invalid={invertedRange}
            onChange={(event) => onChange({ ...filter, to: event.target.value || null })}
            className={fieldClass}
          />
        </label>
      </div>
      <fieldset>
        <legend className="text-xs font-medium text-slate-300">Sports</legend>
        <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1">
          {ACTIVITY_SPORTS.map((sport) => (
            <label key={sport} className="inline-flex items-center gap-1.5">
              <input
                type="checkbox"
                checked={filter.sports.includes(sport)}
                onChange={(event) => toggleSport(sport, event.target.checked)}
                className="accent-orange-500"
              />
              {formatSport(sport)}
            </label>
          ))}
        </div>
      </fieldset>
    </form>
  );
}
