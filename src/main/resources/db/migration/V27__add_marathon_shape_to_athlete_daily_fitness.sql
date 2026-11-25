-- V27: Marathon Shape (#44, ADR-0016), rebuilt with the Effective VO2max of the same athlete-local day.
-- marathon_shape_pct is 100 · (⅔ · marathon_volume_score + ⅓ · marathon_long_run_score), both scores in [0, 1];
-- marathon_weekly_km is the Runs' recorded distance per week over the 182 days ending that day. All four are NULL
-- when the day has no Effective VO2max, and set together otherwise.
ALTER TABLE athlete_daily_fitness
    ADD COLUMN marathon_shape_pct DOUBLE PRECISION,
    ADD COLUMN marathon_volume_score DOUBLE PRECISION,
    ADD COLUMN marathon_long_run_score DOUBLE PRECISION,
    ADD COLUMN marathon_weekly_km DOUBLE PRECISION,
    ADD CONSTRAINT chk_athlete_daily_fitness_marathon_shape CHECK (
        (marathon_shape_pct IS NULL) = (marathon_volume_score IS NULL)
        AND (marathon_shape_pct IS NULL) = (marathon_long_run_score IS NULL)
        AND (marathon_shape_pct IS NULL) = (marathon_weekly_km IS NULL)
    );
