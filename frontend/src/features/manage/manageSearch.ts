import { z } from 'zod';
import type { ActivityListQuery } from '../../api/activityManagement';
import {
  ACTIVITY_SOURCES,
  ACTIVITY_SPORTS,
  type ActivitySource,
  type ActivitySport,
} from '../../lib/format';

/** Page sizes offered by the management workspace; `all` switches the table to row virtualization. */
export const MANAGE_PAGE_SIZES = [20, 50, 100, 'all'] as const;
export type ManagePageSize = (typeof MANAGE_PAGE_SIZES)[number];
export const DEFAULT_MANAGE_PAGE_SIZE: ManagePageSize = 20;

const sportSchema = z.enum(ACTIVITY_SPORTS as [ActivitySport, ...ActivitySport[]]);
const sourceSchema = z.enum(ACTIVITY_SOURCES as [ActivitySource, ...ActivitySource[]]);
const pageSizeSchema = z.custom<ManagePageSize>((value) =>
  MANAGE_PAGE_SIZES.some((size) => size === value),
);

/**
 * URL search params of `/activities/manage`. Anything invalid is dropped (falling back to the default)
 * rather than failing the route, so a hand-edited or stale link still opens the workspace.
 */
export const manageSearchSchema = z.object({
  q: z.coerce.string().trim().min(1).optional().catch(undefined),
  sport: sportSchema.optional().catch(undefined),
  source: sourceSchema.optional().catch(undefined),
  size: z.coerce
    .string()
    .transform((value) => (value === 'all' ? value : Number(value)))
    .pipe(pageSizeSchema)
    .optional()
    .catch(undefined),
  page: z.coerce.number().int().min(0).optional().catch(undefined),
});

export type ManageSearch = z.infer<typeof manageSearchSchema>;

/** The fully-resolved filters of the workspace, restricted to the page sizes it offers. */
export interface ManageFilters extends ActivityListQuery {
  readonly size: ManagePageSize;
}

export function toManageFilters(search: ManageSearch): ManageFilters {
  return {
    ...(search.q === undefined ? {} : { titleContains: search.q }),
    ...(search.sport === undefined ? {} : { sport: search.sport }),
    ...(search.source === undefined ? {} : { source: search.source }),
    size: search.size ?? DEFAULT_MANAGE_PAGE_SIZE,
    // A single page holds every Activity when size is `all`.
    page: search.size === 'all' ? 0 : (search.page ?? 0),
  };
}

/** The minimal search params for [filters]: defaults and the first page are left out of the URL. */
export function toManageSearch(filters: ManageFilters): ManageSearch {
  return {
    ...(filters.titleContains ? { q: filters.titleContains } : {}),
    ...(filters.sport === undefined ? {} : { sport: filters.sport }),
    ...(filters.source === undefined ? {} : { source: filters.source }),
    ...(filters.size === DEFAULT_MANAGE_PAGE_SIZE ? {} : { size: filters.size }),
    ...(filters.page > 0 ? { page: filters.page } : {}),
  };
}
