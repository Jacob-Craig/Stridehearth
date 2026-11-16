-- V5: Add PostGIS extension and create activity_details table for temporal telemetry
--
-- Features:
-- 1. PostGIS extension enabled for geometry types and spatial queries.
-- 2. activity_details shares primary key with activity(id) with ON DELETE CASCADE.
-- 3. route stores geometry(LineString, 4326), indexed via GiST.
-- 4. t holds seconds since session start as common timeline index.
-- 5. All telemetry array columns use native column-level COMPRESSION lz4.
-- 6. parser_version tracks schema versions for guarded, idempotent upserts.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE activity_details (
    activity_id BIGINT PRIMARY KEY REFERENCES activity(id) ON DELETE CASCADE,
    route geometry(LineString, 4326) COMPRESSION lz4,
    t INTEGER[] COMPRESSION lz4 NOT NULL,
    route_index INTEGER[] COMPRESSION lz4,
    parser_version SMALLINT NOT NULL,

    -- Primary Metrics
    heart_rate SMALLINT[] COMPRESSION lz4,
    power SMALLINT[] COMPRESSION lz4,
    cadence SMALLINT[] COMPRESSION lz4,
    altitude REAL[] COMPRESSION lz4,
    speed REAL[] COMPRESSION lz4,
    distance REAL[] COMPRESSION lz4,

    -- Running Dynamics
    vertical_oscillation REAL[] COMPRESSION lz4,
    stance_time REAL[] COMPRESSION lz4,
    stance_time_percent REAL[] COMPRESSION lz4,
    stance_time_balance REAL[] COMPRESSION lz4,
    step_length REAL[] COMPRESSION lz4,
    vertical_ratio REAL[] COMPRESSION lz4,

    -- Enhanced / Physiological
    enhanced_speed REAL[] COMPRESSION lz4,
    enhanced_altitude REAL[] COMPRESSION lz4,
    enhanced_respiration_rate REAL[] COMPRESSION lz4,
    respiration_rate SMALLINT[] COMPRESSION lz4,
    temperature SMALLINT[] COMPRESSION lz4,
    core_temperature REAL[] COMPRESSION lz4,
    fractional_cadence REAL[] COMPRESSION lz4,
    calories INTEGER[] COMPRESSION lz4,
    current_stress REAL[] COMPRESSION lz4,
    battery_soc REAL[] COMPRESSION lz4,
    gps_accuracy SMALLINT[] COMPRESSION lz4,
    vertical_speed REAL[] COMPRESSION lz4,

    -- Cycling Dynamics
    grade REAL[] COMPRESSION lz4,
    resistance SMALLINT[] COMPRESSION lz4,
    time_from_course REAL[] COMPRESSION lz4,
    cycle_length REAL[] COMPRESSION lz4,
    cycles SMALLINT[] COMPRESSION lz4,
    total_cycles BIGINT[] COMPRESSION lz4,
    accumulated_power BIGINT[] COMPRESSION lz4,
    left_right_balance SMALLINT[] COMPRESSION lz4,
    left_torque_effectiveness REAL[] COMPRESSION lz4,
    right_torque_effectiveness REAL[] COMPRESSION lz4,
    left_pedal_smoothness REAL[] COMPRESSION lz4,
    right_pedal_smoothness REAL[] COMPRESSION lz4,
    combined_pedal_smoothness REAL[] COMPRESSION lz4,
    time128 REAL[] COMPRESSION lz4,
    zone SMALLINT[] COMPRESSION lz4,
    ball_speed REAL[] COMPRESSION lz4,
    cadence256 REAL[] COMPRESSION lz4,
    device_index SMALLINT[] COMPRESSION lz4,
    left_pco SMALLINT[] COMPRESSION lz4,
    right_pco SMALLINT[] COMPRESSION lz4,
    motor_power SMALLINT[] COMPRESSION lz4,
    cycle_length16 REAL[] COMPRESSION lz4,

    -- Swimming & Diving
    stroke_type SMALLINT[] COMPRESSION lz4,
    absolute_pressure BIGINT[] COMPRESSION lz4,
    depth REAL[] COMPRESSION lz4,
    next_stop_depth REAL[] COMPRESSION lz4,
    next_stop_time INTEGER[] COMPRESSION lz4,
    time_to_surface INTEGER[] COMPRESSION lz4,
    ndl_time INTEGER[] COMPRESSION lz4,
    cns_load SMALLINT[] COMPRESSION lz4,
    n2_load SMALLINT[] COMPRESSION lz4,
    air_time_remaining INTEGER[] COMPRESSION lz4,
    pressure_sac REAL[] COMPRESSION lz4,
    volume_sac REAL[] COMPRESSION lz4,
    rmv REAL[] COMPRESSION lz4,
    ascent_rate REAL[] COMPRESSION lz4,
    po2 REAL[] COMPRESSION lz4,

    -- eBike & Motion
    grit REAL[] COMPRESSION lz4,
    flow REAL[] COMPRESSION lz4,
    ebike_travel_range INTEGER[] COMPRESSION lz4,
    ebike_battery_level SMALLINT[] COMPRESSION lz4,
    ebike_assist_mode SMALLINT[] COMPRESSION lz4,
    ebike_assist_level_percent SMALLINT[] COMPRESSION lz4,

    -- Muscle Oxygen
    total_hemoglobin_conc REAL[] COMPRESSION lz4,
    total_hemoglobin_conc_min REAL[] COMPRESSION lz4,
    total_hemoglobin_conc_max REAL[] COMPRESSION lz4,
    saturated_hemoglobin_percent REAL[] COMPRESSION lz4,
    saturated_hemoglobin_percent_min REAL[] COMPRESSION lz4,
    saturated_hemoglobin_percent_max REAL[] COMPRESSION lz4
);

CREATE INDEX idx_activity_details_route ON activity_details USING GIST (route);
