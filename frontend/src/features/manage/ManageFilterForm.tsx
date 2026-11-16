import type { FormEvent } from 'react';
import {
  ACTIVITY_SOURCES,
  ACTIVITY_SPORTS,
  type ActivitySource,
  type ActivitySport,
  formatSource,
  formatSport,
} from '../../lib/format';
import { MANAGE_PAGE_SIZES, type ManageFilters, type ManagePageSize } from './manageSearch';
import { fieldClass } from './manageStyles';

interface ManageFilterFormProps {
  readonly filters: ManageFilters;
  /** Called with the submitted filters, always on the first page. */
  readonly onApply: (filters: ManageFilters) => void;
}

/** Filter controls for the management workspace; uncontrolled so typing never refetches. */
export function ManageFilterForm({ filters, onApply }: ManageFilterFormProps) {
  const apply = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const titleContains = String(form.get('q') ?? '').trim();
    const sport = String(form.get('sport') ?? '');
    const source = String(form.get('source') ?? '');
    const size = String(form.get('size') ?? '');
    onApply({
      ...(titleContains ? { titleContains } : {}),
      ...(sport ? { sport: sport as ActivitySport } : {}),
      ...(source ? { source: source as ActivitySource } : {}),
      size: MANAGE_PAGE_SIZES.find((option) => String(option) === size) ?? filters.size,
      page: 0,
    });
  };

  return (
    <form
      aria-label="Filter activities"
      onSubmit={apply}
      className="grid gap-3 rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 sm:grid-cols-2 lg:grid-cols-[2fr_1fr_1fr_1fr_auto] lg:items-end"
    >
      <label className="block text-sm font-medium">
        Title contains
        <input
          type="search"
          name="q"
          defaultValue={filters.titleContains ?? ''}
          placeholder="e.g. Tempo"
          className={fieldClass}
        />
      </label>
      <label className="block text-sm font-medium">
        Sport
        <select name="sport" defaultValue={filters.sport ?? ''} className={fieldClass}>
          <option value="">All sports</option>
          {ACTIVITY_SPORTS.map((sport) => (
            <option key={sport} value={sport}>
              {formatSport(sport)}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm font-medium">
        Source
        <select name="source" defaultValue={filters.source ?? ''} className={fieldClass}>
          <option value="">All sources</option>
          {ACTIVITY_SOURCES.map((source) => (
            <option key={source} value={source}>
              {formatSource(source)}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm font-medium">
        Per page
        <select name="size" defaultValue={String(filters.size)} className={fieldClass}>
          {MANAGE_PAGE_SIZES.map((size) => (
            <option key={size} value={String(size)}>
              {pageSizeLabel(size)}
            </option>
          ))}
        </select>
      </label>
      <button
        type="submit"
        className="rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700"
      >
        Apply filters
      </button>
    </form>
  );
}

const pageSizeLabel = (size: ManagePageSize) => (size === 'all' ? 'All' : String(size));
