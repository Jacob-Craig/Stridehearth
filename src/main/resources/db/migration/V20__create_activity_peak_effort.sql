-- V23: Peak Efforts of an Activity (#38), derived data written only by the derived-data refresh (ADR-0014) and never
-- edited. One row per (Activity, metric, Benchmark Duration) the Activity spans: the highest average over one
-- continuous window of that duration, GAP_SPEED in m/s over the cleaned Grade-Adjusted Pace stream, or POWER in W.
-- Only Runs have rows. The window's samples and times mirror activity_best_effort, so a chart can highlight it.
-- The unique constraint's index (activity_id first) also serves lookups and deletes by Activity.
-- Versioned V23, not the next free V20: V20 to V22 belong to #34 to #36 on the Performance Management Chart epic,
-- which must merge first.
CREATE TABLE activity_peak_effort (
    id BIGSERIAL PRIMARY KEY,
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    metric VARCHAR(16) NOT NULL,
    benchmark_duration VARCHAR(32) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    start_index INT NOT NULL,
    end_index INT NOT NULL,
    start_time_seconds DOUBLE PRECISION NOT NULL,
    end_time_seconds DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_activity_peak_effort UNIQUE (activity_id, metric, benchmark_duration)
);
