CREATE TABLE import_batch (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    total_files INT NOT NULL DEFAULT 0,
    processed_files INT NOT NULL DEFAULT 0,
    successful_files INT NOT NULL DEFAULT 0,
    duplicate_files INT NOT NULL DEFAULT 0,
    failed_files INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_import_batch_user_id ON import_batch(user_id);
CREATE INDEX idx_import_batch_status ON import_batch(status);

CREATE TABLE import_batch_item (
    id BIGSERIAL PRIMARY KEY,
    batch_id BIGINT NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
    original_filename VARCHAR(255) NOT NULL,
    staged_file_path VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    activity_id BIGINT REFERENCES activity(id) ON DELETE SET NULL,
    error_message TEXT,
    processed_at TIMESTAMPTZ,
    last_reconciled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_import_batch_item_batch_id ON import_batch_item(batch_id);
CREATE INDEX idx_import_batch_item_status ON import_batch_item(batch_id, status);
CREATE INDEX idx_import_batch_item_status_recon ON import_batch_item(status, last_reconciled_at);

CREATE TABLE import_batch_item_activity (
    batch_item_id BIGINT NOT NULL REFERENCES import_batch_item(id) ON DELETE CASCADE,
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    PRIMARY KEY (batch_item_id, activity_id)
);

CREATE INDEX idx_import_batch_item_activity_item ON import_batch_item_activity(batch_item_id);
CREATE INDEX idx_import_batch_item_activity_act ON import_batch_item_activity(activity_id);
