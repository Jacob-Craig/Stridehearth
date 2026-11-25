-- V26: Effective VO2max (#42, ADR-0016).
-- On activity_derived_metrics, written by the derived-data refresh (ADR-0014) and recomputed with the Training Load when
-- the Thresholds change: vo2max_raw is a Run's uncorrected VO2max Estimate in ml/kg/min; vo2max_unavailable_reason
-- says why a Run has none. Both are NULL for any other sport, and never both set.
ALTER TABLE activity_derived_metrics
    ADD COLUMN vo2max_raw DOUBLE PRECISION,
    ADD COLUMN vo2max_unavailable_reason VARCHAR(32),
    ADD CONSTRAINT chk_activity_derived_metrics_vo2max
        CHECK (NOT (vo2max_raw IS NOT NULL AND vo2max_unavailable_reason IS NOT NULL));

-- athlete_daily_fitness: an athlete's Effective VO2max on each athlete-local day, from their first day with a VO2max
-- Estimate to their last day with an Activity (never past today), rebuilt before commit (ADR-0016) and never edited.
-- effective_vo2max is the Moving-Time-weighted mean of the estimates of the 30 days ending that day times the
-- Correction Factor, NULL when there are none. correction_factor is 1.0 without a calibrating Best Effort; otherwise
-- correction_activity_id, correction_benchmark_distance and correction_vdot name the Best Effort it came from. The
-- Activity id has no foreign key: the rollup never references an Activity it does not lock (ADR-0016).
CREATE TABLE athlete_daily_fitness (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    effective_vo2max DOUBLE PRECISION,
    correction_factor DOUBLE PRECISION NOT NULL,
    correction_activity_id BIGINT,
    correction_benchmark_distance VARCHAR(32),
    correction_vdot DOUBLE PRECISION,
    PRIMARY KEY (user_id, day),
    CONSTRAINT chk_athlete_daily_fitness_correction CHECK (
        (correction_activity_id IS NULL) = (correction_benchmark_distance IS NULL)
        AND (correction_activity_id IS NULL) = (correction_vdot IS NULL)
    )
);
