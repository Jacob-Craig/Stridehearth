-- V30: The external weather an Activity was done in (#83, ADR-0017), cached so each Activity is looked up once. Written
-- only by the Operator opt-in weather lookup (stridehearth.weather.provider), so the table stays empty while it is
-- `none`. One row per Activity, removed with it. Any value is NULL when the provider had none for that hour; the row
-- still records that the lookup was made. Temperature and dew point are in °C, humidity in percent, wind in m/s.
CREATE TABLE activity_weather (
    activity_id BIGINT PRIMARY KEY REFERENCES activity(id) ON DELETE CASCADE,
    temperature_c REAL,
    dew_point_c REAL,
    humidity_pct REAL,
    wind_speed_ms REAL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
