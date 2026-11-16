-- Each Import Batch Item column means one thing (#89):
--   error_message   is the failure reason of a FAILED item, and nothing else;
--   upgrade_message is the upgrade summary of an UPGRADED or MIXED item;
--   import_batch_item_activity is the single link from an item to the Activities its file resolved to.

ALTER TABLE import_batch_item ADD COLUMN upgrade_message TEXT;

UPDATE import_batch_item
SET upgrade_message = error_message,
    error_message = NULL
WHERE status IN ('UPGRADED', 'MIXED');

-- DUPLICATE items carried a constant "already imported" note that nothing reads.
UPDATE import_batch_item
SET error_message = NULL
WHERE status = 'DUPLICATE';

INSERT INTO import_batch_item_activity (batch_item_id, activity_id)
SELECT id, activity_id
FROM import_batch_item
WHERE activity_id IS NOT NULL
ON CONFLICT DO NOTHING;

ALTER TABLE import_batch_item DROP COLUMN activity_id;
