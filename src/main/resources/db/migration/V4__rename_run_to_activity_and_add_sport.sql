ALTER TABLE run RENAME TO activity;

ALTER TABLE activity RENAME CONSTRAINT uq_run_user_source_external_id TO uq_activity_user_source_external_id;
ALTER TABLE activity RENAME CONSTRAINT fk_run_app_user TO fk_activity_app_user;
ALTER TABLE activity RENAME CONSTRAINT run_pkey TO activity_pkey;
ALTER INDEX idx_run_user_id RENAME TO idx_activity_user_id;
ALTER SEQUENCE IF EXISTS run_id_seq RENAME TO activity_id_seq;

ALTER TABLE activity ADD COLUMN sport VARCHAR(255);
UPDATE activity SET sport = 'RUN' WHERE sport IS NULL;
ALTER TABLE activity ALTER COLUMN sport SET NOT NULL;
