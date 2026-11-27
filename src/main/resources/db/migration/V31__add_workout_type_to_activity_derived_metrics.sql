-- V31: the Workout Type of a Run (#48), classified by rules from derived data (ADR-0014).
-- workout_type is the Run's type (EASY, LONG_RUN, TEMPO, INTERVALS or RACE); NULL for other sports, for a Run without
-- Grade-Adjusted Pace, and on rows written before DERIVED_DATA_VERSION 10, which the recompute worker replaces.
-- The wt_* columns are the Run's own evidence for it (WorkoutEvidence), which depends on the Thresholds in force on its
-- date. They are stored because the Long Run rule also compares a Run with the median Moving Time of the athlete's Runs
-- in the 28 days before it, so a neighbouring Run's change reclassifies it from this evidence without analysing it
-- again. wt_threshold_work_intervals is NOT NULL exactly when the row has evidence.
ALTER TABLE activity_derived_metrics
    ADD COLUMN workout_type VARCHAR(16),
    ADD COLUMN wt_threshold_work_intervals INT,
    ADD COLUMN wt_tempo_seconds INT,
    ADD COLUMN wt_easy_share DOUBLE PRECISION,
    ADD COLUMN wt_race_intensity DOUBLE PRECISION,
    ADD COLUMN wt_distance_meters DOUBLE PRECISION;

-- The median Moving Time and the neighbour reclassification read one athlete's Runs by start time.
CREATE INDEX idx_activity_user_started_at ON activity (user_id, started_at);

-- Durable, append-only requests to reclassify an athlete's Runs started in [range_from, range_to], written when an
-- Activity in the 28 days before them is added, deleted, or changes its start or Moving Time. A refresh never writes
-- another Activity's derived data (ADR-0014), so the recompute worker serves them. Inserted only, so writers never wait
-- on each other; the worker deletes the ones up to the newest id it served.
CREATE TABLE workout_type_request (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    range_from TIMESTAMPTZ NOT NULL,
    range_to TIMESTAMPTZ NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_workout_type_request_user ON workout_type_request (user_id, id);
