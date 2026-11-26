-- V28: Detected Intervals of a Run (#47): the work and recovery stretches the derived-data refresh finds in its Moving
-- Time without any lap press (ADR-0014). Derived data, written only by the refresh and never edited, so every row is
-- replaced whenever the Run's Activity Details change and removed with its Activity. Only Runs with at least one work
-- interval have rows; a steady Run has none. Intervals tile the Run in order, numbered from 1.
-- start_index and end_index are samples of the Activity Details (as for activity_peak_effort); times are elapsed seconds,
-- end exclusive. avg_gap is the mean Grade-Adjusted Pace speed in m/s, avg_hr in bpm and avg_power in W (NULL when
-- the stream is missing), decoupling_pct the interval's own Aerobic Decoupling (work intervals of 2 min or more only).
CREATE TABLE activity_detected_interval (
    id BIGSERIAL PRIMARY KEY,
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    interval_number INT NOT NULL,
    start_index INT NOT NULL,
    end_index INT NOT NULL,
    start_time_seconds DOUBLE PRECISION NOT NULL,
    end_time_seconds DOUBLE PRECISION NOT NULL,
    moving_seconds INT NOT NULL,
    is_work BOOLEAN NOT NULL,
    distance_meters DOUBLE PRECISION,
    avg_gap DOUBLE PRECISION NOT NULL,
    avg_hr DOUBLE PRECISION,
    avg_power DOUBLE PRECISION,
    decoupling_pct DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_activity_detected_interval UNIQUE (activity_id, interval_number)
);
