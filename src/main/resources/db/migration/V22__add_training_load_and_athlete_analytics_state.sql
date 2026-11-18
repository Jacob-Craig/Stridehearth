-- V22: Training Load of an Activity (ADR-0014, #35), computed in the same pass as its other derived data from its
-- Activity Details and the Thresholds in force on its athlete-local date, and never edited.
-- training_load: the load, scaled so one hour at threshold is 100; training_load_method: RSS, RTSS or HRSS.
-- training_load_unavailable_reason: why there is no load (NO_ACTIVITY_DETAILS, NO_THRESHOLDS, NO_USABLE_STREAM).
-- All three are NULL on rows written before DERIVED_DATA_VERSION 3, which the recompute worker replaces.
ALTER TABLE activity_derived_metrics
    ADD COLUMN training_load DOUBLE PRECISION,
    ADD COLUMN training_load_method VARCHAR(8),
    ADD COLUMN training_load_unavailable_reason VARCHAR(32),
    ADD CONSTRAINT ck_training_load_with_method CHECK ((training_load IS NULL) = (training_load_method IS NULL)),
    ADD CONSTRAINT ck_training_load_or_reason CHECK (training_load IS NULL OR training_load_unavailable_reason IS NULL);

-- athlete_analytics_state: one row per athlete for athlete-level derived-data bookkeeping.
-- A durable recompute request is pending while recompute_requested_at is set: the worker recomputes the Training Load
-- of the athlete's Activities on or after recompute_from (an athlete-local date; NULL means all of them), then clears
-- the request only if no newer one arrived meanwhile.
CREATE TABLE athlete_analytics_state (
    user_id BIGINT PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    recompute_from DATE,
    recompute_requested_at TIMESTAMPTZ
);
