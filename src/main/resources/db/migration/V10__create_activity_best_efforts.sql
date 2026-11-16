CREATE TABLE activity_best_effort (
    id BIGSERIAL PRIMARY KEY,
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    sport VARCHAR(32) NOT NULL,
    benchmark_distance VARCHAR(32) NOT NULL,
    elapsed_time_seconds DOUBLE PRECISION NOT NULL,
    start_index INT NOT NULL,
    end_index INT NOT NULL,
    start_time_seconds DOUBLE PRECISION NOT NULL,
    end_time_seconds DOUBLE PRECISION NOT NULL,
    start_distance_meters DOUBLE PRECISION NOT NULL,
    end_distance_meters DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_activity_best_effort_activity_benchmark UNIQUE (activity_id, benchmark_distance)
);

CREATE INDEX idx_activity_best_effort_activity_id ON activity_best_effort(activity_id);
CREATE INDEX idx_activity_best_effort_sport_benchmark ON activity_best_effort(sport, benchmark_distance, elapsed_time_seconds);

CREATE TABLE user_personal_record (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    sport VARCHAR(32) NOT NULL,
    benchmark_distance VARCHAR(32) NOT NULL,
    best_effort_id BIGINT NOT NULL REFERENCES activity_best_effort(id) ON DELETE CASCADE,
    activity_id BIGINT NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    elapsed_time_seconds DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_personal_record UNIQUE (user_id, sport, benchmark_distance)
);

CREATE INDEX idx_user_personal_record_user_sport ON user_personal_record(user_id, sport);
