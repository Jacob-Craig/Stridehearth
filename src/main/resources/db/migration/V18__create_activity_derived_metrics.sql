-- Derived metrics of an Activity, recomputed from its Activity Details (via Telemetry Cleaning, ADR-0013) and
-- never edited. One row means "computed by algorithm_version"; a Backfill recomputes rows of an older version.
-- gap_speed: Grade-Adjusted Pace in m/s, parallel to activity_details.t (null samples where cleaned speed is
-- missing); NULL when the Activity Details have no speed and no distance.
CREATE TABLE activity_derived_metrics (
    activity_id BIGINT PRIMARY KEY REFERENCES activity(id) ON DELETE CASCADE,
    algorithm_version SMALLINT NOT NULL,
    gap_speed REAL[] COMPRESSION lz4,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
