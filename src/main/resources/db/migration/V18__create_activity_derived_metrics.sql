-- V18: derived data of an Activity (ADR-0014, #32), computed from its Activity Details via Telemetry Cleaning
-- (ADR-0013) and never edited. One row per Activity of every sport, MANUAL included: the row means "computed by
-- algorithm_version", and the recompute worker fills in missing rows and replaces rows of an older version.
-- gap_speed: Grade-Adjusted Pace in m/s, parallel to activity_details.t (null samples where cleaned speed is
-- missing); NULL for a sport without GAP, without Activity Details, or when they have no speed and no distance.
CREATE TABLE activity_derived_metrics (
    activity_id BIGINT PRIMARY KEY REFERENCES activity(id) ON DELETE CASCADE,
    algorithm_version SMALLINT NOT NULL,
    gap_speed REAL[] COMPRESSION lz4,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
