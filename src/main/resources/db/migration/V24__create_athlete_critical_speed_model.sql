-- V24: an athlete's Critical Speed / Critical Power models (ADR-0016, #39), one row per metric and athlete-local date
-- on which they have a Run, refitted before commit from the Run Peak Efforts of the 90 days up to that date.
-- metric: GAP_SPEED (critical_value: Critical Speed, m/s; reserve_capacity: D', m) or POWER (CP, W; W', J).
-- status: MODEL, INSUFFICIENT_POINTS, POOR_FIT or NON_PHYSICAL; critical_value and reserve_capacity are NULL unless
-- it is MODEL, and r_squared is NULL when too few points were fitted.
CREATE TABLE athlete_critical_speed_model (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    metric VARCHAR(16) NOT NULL,
    model_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    critical_value DOUBLE PRECISION,
    reserve_capacity DOUBLE PRECISION,
    r_squared DOUBLE PRECISION,
    point_count INT NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, metric, model_date)
);
