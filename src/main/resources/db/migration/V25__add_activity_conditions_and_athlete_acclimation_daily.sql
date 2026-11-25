-- V25: heat and altitude context (#43, ADR-0017).
-- On activity_derived_metrics, written by the derived-data refresh (ADR-0014), NULL when the Activity Details cannot
-- support them: device_temperature_c is the median of the device's temperature samples; ambient_temperature_c is that
-- less the Wrist-Heat Correction; mean_altitude_m is the mean of the cleaned altitude, in metres.
ALTER TABLE activity_derived_metrics
    ADD COLUMN device_temperature_c REAL,
    ADD COLUMN ambient_temperature_c REAL,
    ADD COLUMN mean_altitude_m REAL;

-- athlete_acclimation_daily: an athlete's Heat and Altitude Acclimation (0-100 %) at the end of each athlete-local day,
-- from their first exposed day to their last day with an Activity (never past today), rebuilt before commit
-- (ADR-0016) and never edited. heat_exposed / altitude_exposed: whether the day had an Activity of at least 20 minutes
-- above 22 °C Ambient Temperature / 800 m Mean Altitude.
CREATE TABLE athlete_acclimation_daily (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    heat_pct DOUBLE PRECISION NOT NULL,
    altitude_pct DOUBLE PRECISION NOT NULL,
    heat_exposed BOOLEAN NOT NULL,
    altitude_exposed BOOLEAN NOT NULL,
    PRIMARY KEY (user_id, day)
);
