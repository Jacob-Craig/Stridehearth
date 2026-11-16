-- Activity History Entries and Import Batch Item upgrades become typed changes stored as jsonb (#69).
-- This migration only adds: existing free-text rows are left exactly as they are and are served as legacy changes.

ALTER TABLE activity_history ADD COLUMN change JSONB;
ALTER TABLE activity_history ALTER COLUMN description DROP NOT NULL;
-- A row is either typed (change) or legacy (description), never both or neither.
ALTER TABLE activity_history
    ADD CONSTRAINT ck_activity_history_change_or_description CHECK (num_nonnulls(change, description) = 1);

-- The typed upgrades of an UPGRADED or MIXED item; upgrade_message (V11) keeps the legacy text.
ALTER TABLE import_batch_item ADD COLUMN upgrades JSONB;
