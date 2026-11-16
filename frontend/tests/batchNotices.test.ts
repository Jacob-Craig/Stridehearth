import { describe, expect, it } from 'vitest';
import { backfillNotice, batchResultNotice } from '../src/features/manage/batchNotices';

describe('batchResultNotice', () => {
  it('summarizes a batch update from its counts', () => {
    expect(batchResultNotice('modify', { affectedCount: 2, matchedCount: 2 })).toBe(
      'Updated 2 activities',
    );
    expect(batchResultNotice('modify', { affectedCount: 1, matchedCount: 1 })).toBe(
      'Updated 1 activity',
    );
    expect(batchResultNotice('modify', { affectedCount: 2, matchedCount: 3 })).toBe(
      'Updated 2 of 3 activities (the rest already match the target values)',
    );
    expect(batchResultNotice('modify', { affectedCount: 0, matchedCount: 3 })).toBe(
      'No activities were updated (all selected activities already match the target values)',
    );
  });

  it('summarizes a batch delete from its counts', () => {
    expect(batchResultNotice('delete', { affectedCount: 1, matchedCount: 1 })).toBe(
      'Permanently deleted 1 activity',
    );
    expect(batchResultNotice('delete', { affectedCount: 1999, matchedCount: 1999 })).toBe(
      'Permanently deleted 1,999 activities',
    );
  });
});

describe('backfillNotice', () => {
  it('summarizes the Activities processed and Best Efforts detected', () => {
    expect(backfillNotice({ activitiesProcessed: 3, bestEffortsCreated: 1 })).toBe(
      'Backfilled Best Efforts across 3 activities (1 effort detected)',
    );
    expect(backfillNotice({ activitiesProcessed: 1, bestEffortsCreated: 4 })).toBe(
      'Backfilled Best Efforts across 1 activity (4 efforts detected)',
    );
  });
});
