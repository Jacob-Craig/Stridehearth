CREATE TABLE activity_laps (
    id BIGSERIAL PRIMARY KEY,
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    lap_number INT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    distance DOUBLE PRECISION NOT NULL,
    time_taken_seconds INT NOT NULL,
    average_pace_kmh DOUBLE PRECISION,
    average_heart_rate INT,
    elevation_gain_meters DOUBLE PRECISION,
    CONSTRAINT uq_activity_laps_activity_lap_number UNIQUE (activity_id, lap_number)
);

CREATE INDEX idx_activity_laps_activity_id ON activity_laps(activity_id);
