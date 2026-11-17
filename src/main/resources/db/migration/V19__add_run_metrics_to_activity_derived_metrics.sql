-- V19: Run metrics of an Activity (ADR-0014, #33), derived from its Grade-Adjusted Pace and cleaned heart rate in
-- the same pass as gap_speed and never edited. NULL for every sport but RUN, and for rows written before
-- DERIVED_DATA_VERSION 2, which the recompute worker replaces.
-- moving_seconds: Moving Time, the seconds of the 1 Hz moving series.
-- ngp_mps: Normalized Graded Pace in m/s. efficiency_factor: NGP in m/min per bpm of average heart rate.
-- aerobic_decoupling_pct: Aerobic Decoupling in percent; NULL with decoupling_unavailable_reason set when the Run
-- has no pace data, too little heart rate, is shorter than 30 minutes or is not steady.
ALTER TABLE activity_derived_metrics
    ADD COLUMN moving_seconds INT,
    ADD COLUMN ngp_mps DOUBLE PRECISION,
    ADD COLUMN efficiency_factor DOUBLE PRECISION,
    ADD COLUMN aerobic_decoupling_pct DOUBLE PRECISION,
    ADD COLUMN decoupling_unavailable_reason VARCHAR(32);
