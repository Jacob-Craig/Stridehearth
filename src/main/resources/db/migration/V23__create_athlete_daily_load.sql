-- V23: an athlete's Daily Load and their Performance Management Chart (ADR-0016, #36), rebuilt before commit from
-- their Activities' Training Loads and never edited.
-- athlete_daily_load: one row per athlete-local day with at least one Activity. training_load sums the day's scored
-- Activities; unscored_activity_count counts the day's Activities without a Training Load (they contribute 0).
CREATE TABLE athlete_daily_load (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    training_load DOUBLE PRECISION NOT NULL,
    activity_count INT NOT NULL,
    unscored_activity_count INT NOT NULL,
    PRIMARY KEY (user_id, day)
);

-- athlete_daily_metrics: one row per day from the athlete's first scored day to their last day with an Activity.
-- ctl, atl: Fitness and Fatigue; tsb: Form; acwr, monotony, strain: NULL while withheld or undefined.
CREATE TABLE athlete_daily_metrics (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    ctl DOUBLE PRECISION NOT NULL,
    atl DOUBLE PRECISION NOT NULL,
    tsb DOUBLE PRECISION NOT NULL,
    acwr DOUBLE PRECISION,
    monotony DOUBLE PRECISION,
    strain DOUBLE PRECISION,
    PRIMARY KEY (user_id, day)
);

-- rollup_version: the version of the athlete rollups their stored rows were computed at. Rows below the current
-- version (0: never computed, or the time zone changed) are rebuilt in full by the recompute worker; until then the
-- before-commit rollup leaves them alone.
ALTER TABLE athlete_analytics_state ADD COLUMN rollup_version SMALLINT NOT NULL DEFAULT 0;

-- Every existing athlete starts at version 0, so the worker builds their rollups from the Activities they already have.
INSERT INTO athlete_analytics_state (user_id) SELECT id FROM app_user ON CONFLICT (user_id) DO NOTHING;
