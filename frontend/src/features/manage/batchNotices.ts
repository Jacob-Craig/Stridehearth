import type {
  BatchMutationResultDto,
  BestEffortBackfillResultDto,
} from '../../api/generated/types.gen';
import { formatCount, formatInteger } from '../../lib/format';
import type { BatchMode } from './batchModalState';

const activities = (count: number) => formatCount(count, 'activity', 'activities');

/** The banner reporting a completed batch update or delete, worded from the server's counts. */
export function batchResultNotice(
  mode: BatchMode,
  { affectedCount, matchedCount }: BatchMutationResultDto,
): string {
  if (mode === 'delete') return `Permanently deleted ${activities(affectedCount)}`;
  if (affectedCount === 0) {
    return 'No activities were updated (all selected activities already match the target values)';
  }
  if (affectedCount < matchedCount) {
    return `Updated ${formatInteger(affectedCount)} of ${activities(matchedCount)} (the rest already match the target values)`;
  }
  return `Updated ${activities(affectedCount)}`;
}

/** The banner reporting a completed **Best Efforts** backfill. */
export function backfillNotice({
  activitiesProcessed,
  bestEffortsCreated,
}: BestEffortBackfillResultDto): string {
  return `Backfilled Best Efforts across ${activities(activitiesProcessed)} (${formatCount(bestEffortsCreated, 'effort', 'efforts')} detected)`;
}
