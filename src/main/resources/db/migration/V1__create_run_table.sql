CREATE TABLE run (
                     id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                     title VARCHAR(255) NOT NULL,
                     started_at TIMESTAMPTZ NOT NULL,
                     distance DOUBLE PRECISION NOT NULL,
                     time_taken_seconds INTEGER NOT NULL,
                     average_pace_kmh DOUBLE PRECISION,
                     average_heart_rate INTEGER,
                     calories INTEGER
);
