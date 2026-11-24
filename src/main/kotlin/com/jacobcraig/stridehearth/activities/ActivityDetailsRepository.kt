package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/**
 * Data access repository for the `activity_details` table using Spring's [JdbcClient].
 *
 * Architecture Notes:
 * - Operates entirely via raw SQL; no JPA entity maps `activity_details`, allowing Hibernate
 *   `ddl-auto: validate` to succeed without requiring Hibernate Spatial or JTS dependencies.
 * - Route LineStrings are constructed directly inside PostgreSQL using `ST_SetSRID(ST_MakeLine(...), 4326)`
 *   when $\ge 2$ GPS coordinates are present; otherwise `route` remains `NULL`.
 * - Telemetry is retrieved using PostgreSQL's native `jsonb_build_object` and `ST_AsGeoJSON`, passing
 *   structured JSON directly through to the caller (see [findTelemetryJson] for the nested contract).
 * - [upsert] is a plain insert or full update; [ActivityDetailsService] decides whether to write. It also writes
 *   the Activity's heatmap route row in the same statement.
 */
@Repository
class ActivityDetailsRepository(
    private val jdbcClient: JdbcClient,
    private val dataSource: DataSource,
) {
    /**
     * Returns the subset of [activityIds] that have recorded **Activity Details**. Looked up in chunks so an unpaged listing
     * (`size=all`) never exceeds the driver's bind-parameter limit.
     */
    fun findActivityIdsWithDetails(activityIds: Collection<Long>): Set<Long> =
        activityIds.chunkedForIdLookup().flatMapTo(mutableSetOf()) { chunk ->
            jdbcClient
                .sql("SELECT activity_id FROM activity_details WHERE activity_id IN (:activityIds)")
                .param("activityIds", chunk)
                .query(Long::class.javaObjectType)
                .list()
                .filterNotNull()
        }

    /**
     * Reads, exactly as stored, the series that **Best Effort** scanning needs: `t`, `distance`, `routeIndex`
     * and `enhancedSpeed`/`speed`. The route is not read (`lons`/`lats` are empty): it is needed only to
     * synthesize missing distance, so [findStoredRoute] reads it on demand. A pure read: it never writes.
     *
     * Scanning goes through [ActivityDetailsService.findTimeAndDistanceSeries], which decides whether distance
     * must be synthesized from these.
     */
    fun findStoredScanningSeries(activityId: Long): FitTelemetry? =
        jdbcClient
            .sql("SELECT t, distance, route_index, enhanced_speed, speed FROM activity_details WHERE activity_id = :activityId")
            .param("activityId", activityId)
            .query { rs, _ ->
                val tArr = (rs.getArray("t")?.array as? Array<*>)?.map { (it as Number).toInt() }?.toIntArray() ?: IntArray(0)
                FitTelemetry(
                    t = tArr,
                    lons = DoubleArray(0),
                    lats = DoubleArray(0),
                    routeIndex =
                        (rs.getArray("route_index")?.array as? Array<*>)
                            ?.map { (it as? Number)?.toInt() }
                            ?.toTypedArray() ?: arrayOfNulls(tArr.size),
                    distance = rs.getFloatArray("distance") ?: arrayOfNulls(tArr.size),
                    enhancedSpeed = rs.getFloatArray("enhanced_speed") ?: emptyArray(),
                    speed = rs.getFloatArray("speed") ?: emptyArray(),
                )
            }.optional()
            .orElse(null)

    /**
     * Reads, exactly as stored, the series derived analytics need: `t`, `distance`, `routeIndex`, speed, heart
     * rate, cadence and altitude (with their enhanced and fractional variants), which **Telemetry Cleaning** reads,
     * and power. Like [findStoredScanningSeries] the route is not read. A pure read: it never writes.
     */
    fun findStoredAnalyticsSeries(activityId: Long): FitTelemetry? =
        jdbcClient
            .sql(
                """
                SELECT t, distance, route_index, enhanced_speed, speed, heart_rate, cadence, fractional_cadence,
                       altitude, enhanced_altitude, power
                FROM activity_details WHERE activity_id = :activityId
                """.trimIndent(),
            ).param("activityId", activityId)
            .query { rs, _ ->
                val tArr = (rs.getArray("t")?.array as? Array<*>)?.map { (it as Number).toInt() }?.toIntArray() ?: IntArray(0)
                FitTelemetry(
                    t = tArr,
                    lons = DoubleArray(0),
                    lats = DoubleArray(0),
                    routeIndex =
                        (rs.getArray("route_index")?.array as? Array<*>)
                            ?.map { (it as? Number)?.toInt() }
                            ?.toTypedArray() ?: arrayOfNulls(tArr.size),
                    distance = rs.getFloatArray("distance") ?: arrayOfNulls(tArr.size),
                    enhancedSpeed = rs.getFloatArray("enhanced_speed") ?: emptyArray(),
                    speed = rs.getFloatArray("speed") ?: emptyArray(),
                    heartRate = rs.getShortArray("heart_rate") ?: emptyArray(),
                    cadence = rs.getShortArray("cadence") ?: emptyArray(),
                    fractionalCadence = rs.getFloatArray("fractional_cadence") ?: emptyArray(),
                    altitude = rs.getFloatArray("altitude") ?: emptyArray(),
                    enhancedAltitude = rs.getFloatArray("enhanced_altitude") ?: emptyArray(),
                    power = rs.getShortArray("power") ?: emptyArray(),
                )
            }.optional()
            .orElse(null)

    /**
     * Reads the stored route's points as `lons` to `lats`, in route order; both are empty when no route (or no
     * Activity Details) is stored. A pure read: it never writes.
     */
    fun findStoredRoute(activityId: Long): Pair<DoubleArray, DoubleArray> =
        jdbcClient
            .sql(
                """
                SELECT ST_X((dp).geom) AS lon, ST_Y((dp).geom) AS lat
                FROM activity_details, ST_DumpPoints(route) AS dp
                WHERE activity_id = :activityId AND route IS NOT NULL
                ORDER BY (dp).path[1]
                """.trimIndent(),
            ).param("activityId", activityId)
            .query { rs, _ -> rs.getDouble("lon") to rs.getDouble("lat") }
            .list()
            .let { points -> points.map { it.first }.toDoubleArray() to points.map { it.second }.toDoubleArray() }

    /**
     * Takes a no-key-update lock on the Activity's own row, which exists even when no Activity Details are
     * stored, so that concurrent writers of one Activity's details serialize. Requires a transaction.
     */
    fun lockActivity(activityId: Long) {
        jdbcClient
            .sql("SELECT id FROM activity WHERE id = :activityId FOR NO KEY UPDATE")
            .param("activityId", activityId)
            .query(Long::class.javaObjectType)
            .single()
    }

    /**
     * Takes the same no-key-update lock as [lockActivity] on each of [activityIds]' rows, in ascending id order so
     * that two callers locking overlapping sets wait rather than deadlock, and returns the ids that still exist (in
     * that order): an Activity deleted meanwhile is skipped. Requires a transaction.
     */
    fun lockActivities(activityIds: Collection<Long>): List<Long> =
        if (activityIds.isEmpty()) {
            emptyList()
        } else {
            jdbcClient
                .sql("SELECT id FROM activity WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE")
                .param("ids", activityIds.distinct())
                .query { rs, _ -> rs.getLong("id") }
                .list()
        }

    /**
     * Reads what is stored for an Activity, as far as [ActivityDetailsService]'s rule needs it.
     *
     * Kept apart from [lockActivity]: under READ COMMITTED a separate statement run after the lock sees what a
     * concurrent writer committed while this one waited, whereas a read joined into the locking statement would not.
     */
    fun findStoredDetailsState(activityId: Long): StoredDetailsState =
        jdbcClient
            .sql(
                """
                SELECT d.parser_version,
                       d.distance,
                       (SELECT count(*) FROM activity_laps l WHERE l.activity_id = a.id) AS lap_count
                FROM activity a
                LEFT JOIN activity_details d ON d.activity_id = a.id
                WHERE a.id = :activityId
                """.trimIndent(),
            ).param("activityId", activityId)
            .query { rs, _ ->
                val version = rs.getShort("parser_version").takeUnless { rs.wasNull() }
                StoredDetailsState(
                    version = version?.let(::ParserVersion),
                    hasDistance = FitTelemetry.hasDistance(rs.getFloatArray("distance") ?: emptyArray()),
                    lapCount = rs.getInt("lap_count"),
                )
            }.single()

    /**
     * Writes the Activity Details for [activityId]: inserts the row, or fully replaces a stored one.
     *
     * The same statement writes the Activity's heatmap route from the new route and the Activity's sport via
     * `heatmap_route_geometries` (ADR-0007), or removes it when the new details have no drawable route. So
     * create, **Upgrade** and **Backfill** of Activity Details all keep the **Heatmap** in step.
     *
     * Unguarded: whether details should be written is decided by [ActivityDetailsService.offer], the only
     * production caller, under a lock on the Activity. Tests also use it to seed stored details.
     *
     * @param activityId the ID of the parent `Activity` row.
     * @param telemetry the decoded time-series data and coordinates.
     * @param parserVersion the Parser Version that decoded [telemetry].
     * @return the number of rows written (always 1).
     */
    fun upsert(
        activityId: Long,
        telemetry: FitTelemetry,
        parserVersion: ParserVersion,
    ): Int {
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            val sql =
                """
                WITH details AS (
                INSERT INTO activity_details (
                    activity_id, route, t, route_index, parser_version,
                    heart_rate, power, cadence, altitude, speed, distance,
                    vertical_oscillation, stance_time, stance_time_percent, stance_time_balance, step_length, vertical_ratio,
                    enhanced_speed, enhanced_altitude, enhanced_respiration_rate, respiration_rate, temperature, core_temperature,
                    fractional_cadence, calories, current_stress, battery_soc, gps_accuracy, vertical_speed,
                    grade, resistance, time_from_course, cycle_length, cycles, total_cycles, accumulated_power,
                    left_right_balance, left_torque_effectiveness, right_torque_effectiveness, left_pedal_smoothness, right_pedal_smoothness, combined_pedal_smoothness,
                    time128, zone, ball_speed, cadence256, device_index, left_pco, right_pco, motor_power, cycle_length16,
                    stroke_type, absolute_pressure, depth, next_stop_depth, next_stop_time, time_to_surface, ndl_time,
                    cns_load, n2_load, air_time_remaining, pressure_sac, volume_sac, rmv, ascent_rate, po2,
                    grit, flow, ebike_travel_range, ebike_battery_level, ebike_assist_mode, ebike_assist_level_percent,
                    total_hemoglobin_conc, total_hemoglobin_conc_min, total_hemoglobin_conc_max, saturated_hemoglobin_percent, saturated_hemoglobin_percent_min, saturated_hemoglobin_percent_max
                )
                VALUES (
                    :activityId,
                    CASE
                        WHEN cardinality(:lons::float8[]) >= 2 THEN
                            ST_SetSRID(ST_MakeLine(ARRAY(SELECT ST_MakePoint(u.lon, u.lat) FROM unnest(:lons::float8[], :lats::float8[]) AS u(lon, lat))), 4326)
                        ELSE NULL
                    END,
                    :t, :routeIndex, :parserVersion,
                    :heartRate, :power, :cadence, :altitude, :speed, :distance,
                    :verticalOscillation, :stanceTime, :stanceTimePercent, :stanceTimeBalance, :stepLength, :verticalRatio,
                    :enhancedSpeed, :enhancedAltitude, :enhancedRespirationRate, :respirationRate, :temperature, :coreTemperature,
                    :fractionalCadence, :calories, :currentStress, :batterySoc, :gpsAccuracy, :verticalSpeed,
                    :grade, :resistance, :timeFromCourse, :cycleLength, :cycles, :totalCycles, :accumulatedPower,
                    :leftRightBalance, :leftTorqueEffectiveness, :rightTorqueEffectiveness, :leftPedalSmoothness, :rightPedalSmoothness, :combinedPedalSmoothness,
                    :time128, :zone, :ballSpeed, :cadence256, :deviceIndex, :leftPco, :rightPco, :motorPower, :cycleLength16,
                    :strokeType, :absolutePressure, :depth, :nextStopDepth, :nextStopTime, :timeToSurface, :ndlTime,
                    :cnsLoad, :n2Load, :airTimeRemaining, :pressureSac, :volumeSac, :rmv, :ascentRate, :po2,
                    :grit, :flow, :ebikeTravelRange, :ebikeBatteryLevel, :ebikeAssistMode, :ebikeAssistLevelPercent,
                    :totalHemoglobinConc, :totalHemoglobinConcMin, :totalHemoglobinConcMax, :saturatedHemoglobinPercent, :saturatedHemoglobinPercentMin, :saturatedHemoglobinPercentMax
                )
                ON CONFLICT (activity_id) DO UPDATE SET
                    route = EXCLUDED.route,
                    t = EXCLUDED.t,
                    route_index = EXCLUDED.route_index,
                    parser_version = EXCLUDED.parser_version,
                    heart_rate = EXCLUDED.heart_rate,
                    power = EXCLUDED.power,
                    cadence = EXCLUDED.cadence,
                    altitude = EXCLUDED.altitude,
                    speed = EXCLUDED.speed,
                    distance = EXCLUDED.distance,
                    vertical_oscillation = EXCLUDED.vertical_oscillation,
                    stance_time = EXCLUDED.stance_time,
                    stance_time_percent = EXCLUDED.stance_time_percent,
                    stance_time_balance = EXCLUDED.stance_time_balance,
                    step_length = EXCLUDED.step_length,
                    vertical_ratio = EXCLUDED.vertical_ratio,
                    enhanced_speed = EXCLUDED.enhanced_speed,
                    enhanced_altitude = EXCLUDED.enhanced_altitude,
                    enhanced_respiration_rate = EXCLUDED.enhanced_respiration_rate,
                    respiration_rate = EXCLUDED.respiration_rate,
                    temperature = EXCLUDED.temperature,
                    core_temperature = EXCLUDED.core_temperature,
                    fractional_cadence = EXCLUDED.fractional_cadence,
                    calories = EXCLUDED.calories,
                    current_stress = EXCLUDED.current_stress,
                    battery_soc = EXCLUDED.battery_soc,
                    gps_accuracy = EXCLUDED.gps_accuracy,
                    vertical_speed = EXCLUDED.vertical_speed,
                    grade = EXCLUDED.grade,
                    resistance = EXCLUDED.resistance,
                    time_from_course = EXCLUDED.time_from_course,
                    cycle_length = EXCLUDED.cycle_length,
                    cycles = EXCLUDED.cycles,
                    total_cycles = EXCLUDED.total_cycles,
                    accumulated_power = EXCLUDED.accumulated_power,
                    left_right_balance = EXCLUDED.left_right_balance,
                    left_torque_effectiveness = EXCLUDED.left_torque_effectiveness,
                    right_torque_effectiveness = EXCLUDED.right_torque_effectiveness,
                    left_pedal_smoothness = EXCLUDED.left_pedal_smoothness,
                    right_pedal_smoothness = EXCLUDED.right_pedal_smoothness,
                    combined_pedal_smoothness = EXCLUDED.combined_pedal_smoothness,
                    time128 = EXCLUDED.time128,
                    zone = EXCLUDED.zone,
                    ball_speed = EXCLUDED.ball_speed,
                    cadence256 = EXCLUDED.cadence256,
                    device_index = EXCLUDED.device_index,
                    left_pco = EXCLUDED.left_pco,
                    right_pco = EXCLUDED.right_pco,
                    motor_power = EXCLUDED.motor_power,
                    cycle_length16 = EXCLUDED.cycle_length16,
                    stroke_type = EXCLUDED.stroke_type,
                    absolute_pressure = EXCLUDED.absolute_pressure,
                    depth = EXCLUDED.depth,
                    next_stop_depth = EXCLUDED.next_stop_depth,
                    next_stop_time = EXCLUDED.next_stop_time,
                    time_to_surface = EXCLUDED.time_to_surface,
                    ndl_time = EXCLUDED.ndl_time,
                    cns_load = EXCLUDED.cns_load,
                    n2_load = EXCLUDED.n2_load,
                    air_time_remaining = EXCLUDED.air_time_remaining,
                    pressure_sac = EXCLUDED.pressure_sac,
                    volume_sac = EXCLUDED.volume_sac,
                    rmv = EXCLUDED.rmv,
                    ascent_rate = EXCLUDED.ascent_rate,
                    po2 = EXCLUDED.po2,
                    grit = EXCLUDED.grit,
                    flow = EXCLUDED.flow,
                    ebike_travel_range = EXCLUDED.ebike_travel_range,
                    ebike_battery_level = EXCLUDED.ebike_battery_level,
                    ebike_assist_mode = EXCLUDED.ebike_assist_mode,
                    ebike_assist_level_percent = EXCLUDED.ebike_assist_level_percent,
                    total_hemoglobin_conc = EXCLUDED.total_hemoglobin_conc,
                    total_hemoglobin_conc_min = EXCLUDED.total_hemoglobin_conc_min,
                    total_hemoglobin_conc_max = EXCLUDED.total_hemoglobin_conc_max,
                    saturated_hemoglobin_percent = EXCLUDED.saturated_hemoglobin_percent,
                    saturated_hemoglobin_percent_min = EXCLUDED.saturated_hemoglobin_percent_min,
                    saturated_hemoglobin_percent_max = EXCLUDED.saturated_hemoglobin_percent_max
                RETURNING activity_id, route
                ),
                heatmap AS ($HEATMAP_ROUTE_OF_DETAILS),
                written_heatmap_route AS ($UPSERT_HEATMAP_ROUTE),
                removed_heatmap_route AS (
                    DELETE FROM heatmap_route h
                    USING details d
                    WHERE h.activity_id = d.activity_id
                      AND NOT EXISTS (SELECT 1 FROM heatmap WHERE heatmap.activity_id = d.activity_id)
                )
                SELECT count(*) FROM details
                """.trimIndent()

            return jdbcClient
                .sql(sql)
                .param("activityId", activityId)
                .param("lons", connection.createDoubleArray(telemetry.lons))
                .param("lats", connection.createDoubleArray(telemetry.lats))
                .param("t", connection.createIntArray(telemetry.t))
                .param("routeIndex", connection.createNullableArray("integer", telemetry.routeIndex))
                .param("parserVersion", parserVersion.value)
                .param("heartRate", connection.createNullableArray("smallint", telemetry.heartRate))
                .param("power", connection.createNullableArray("smallint", telemetry.power))
                .param("cadence", connection.createNullableArray("smallint", telemetry.cadence))
                .param("altitude", connection.createNullableArray("real", telemetry.altitude))
                .param("speed", connection.createNullableArray("real", telemetry.speed))
                .param("distance", connection.createNullableArray("real", telemetry.distance))
                .param("verticalOscillation", connection.createNullableArray("real", telemetry.verticalOscillation))
                .param("stanceTime", connection.createNullableArray("real", telemetry.stanceTime))
                .param("stanceTimePercent", connection.createNullableArray("real", telemetry.stanceTimePercent))
                .param("stanceTimeBalance", connection.createNullableArray("real", telemetry.stanceTimeBalance))
                .param("stepLength", connection.createNullableArray("real", telemetry.stepLength))
                .param("verticalRatio", connection.createNullableArray("real", telemetry.verticalRatio))
                .param("enhancedSpeed", connection.createNullableArray("real", telemetry.enhancedSpeed))
                .param("enhancedAltitude", connection.createNullableArray("real", telemetry.enhancedAltitude))
                .param("enhancedRespirationRate", connection.createNullableArray("real", telemetry.enhancedRespirationRate))
                .param("respirationRate", connection.createNullableArray("smallint", telemetry.respirationRate))
                .param("temperature", connection.createNullableArray("smallint", telemetry.temperature))
                .param("coreTemperature", connection.createNullableArray("real", telemetry.coreTemperature))
                .param("fractionalCadence", connection.createNullableArray("real", telemetry.fractionalCadence))
                .param("calories", connection.createNullableArray("integer", telemetry.calories))
                .param("currentStress", connection.createNullableArray("real", telemetry.currentStress))
                .param("batterySoc", connection.createNullableArray("real", telemetry.batterySoc))
                .param("gpsAccuracy", connection.createNullableArray("smallint", telemetry.gpsAccuracy))
                .param("verticalSpeed", connection.createNullableArray("real", telemetry.verticalSpeed))
                .param("grade", connection.createNullableArray("real", telemetry.grade))
                .param("resistance", connection.createNullableArray("smallint", telemetry.resistance))
                .param("timeFromCourse", connection.createNullableArray("real", telemetry.timeFromCourse))
                .param("cycleLength", connection.createNullableArray("real", telemetry.cycleLength))
                .param("cycles", connection.createNullableArray("smallint", telemetry.cycles))
                .param("totalCycles", connection.createNullableArray("bigint", telemetry.totalCycles))
                .param("accumulatedPower", connection.createNullableArray("bigint", telemetry.accumulatedPower))
                .param("leftRightBalance", connection.createNullableArray("smallint", telemetry.leftRightBalance))
                .param("leftTorqueEffectiveness", connection.createNullableArray("real", telemetry.leftTorqueEffectiveness))
                .param("rightTorqueEffectiveness", connection.createNullableArray("real", telemetry.rightTorqueEffectiveness))
                .param("leftPedalSmoothness", connection.createNullableArray("real", telemetry.leftPedalSmoothness))
                .param("rightPedalSmoothness", connection.createNullableArray("real", telemetry.rightPedalSmoothness))
                .param("combinedPedalSmoothness", connection.createNullableArray("real", telemetry.combinedPedalSmoothness))
                .param("time128", connection.createNullableArray("real", telemetry.time128))
                .param("zone", connection.createNullableArray("smallint", telemetry.zone))
                .param("ballSpeed", connection.createNullableArray("real", telemetry.ballSpeed))
                .param("cadence256", connection.createNullableArray("real", telemetry.cadence256))
                .param("deviceIndex", connection.createNullableArray("smallint", telemetry.deviceIndex))
                .param("leftPco", connection.createNullableArray("smallint", telemetry.leftPco))
                .param("rightPco", connection.createNullableArray("smallint", telemetry.rightPco))
                .param("motorPower", connection.createNullableArray("smallint", telemetry.motorPower))
                .param("cycleLength16", connection.createNullableArray("real", telemetry.cycleLength16))
                .param("strokeType", connection.createNullableArray("smallint", telemetry.strokeType))
                .param("absolutePressure", connection.createNullableArray("bigint", telemetry.absolutePressure))
                .param("depth", connection.createNullableArray("real", telemetry.depth))
                .param("nextStopDepth", connection.createNullableArray("real", telemetry.nextStopDepth))
                .param("nextStopTime", connection.createNullableArray("integer", telemetry.nextStopTime))
                .param("timeToSurface", connection.createNullableArray("integer", telemetry.timeToSurface))
                .param("ndlTime", connection.createNullableArray("integer", telemetry.ndlTime))
                .param("cnsLoad", connection.createNullableArray("smallint", telemetry.cnsLoad))
                .param("n2Load", connection.createNullableArray("smallint", telemetry.n2Load))
                .param("airTimeRemaining", connection.createNullableArray("integer", telemetry.airTimeRemaining))
                .param("pressureSac", connection.createNullableArray("real", telemetry.pressureSac))
                .param("volumeSac", connection.createNullableArray("real", telemetry.volumeSac))
                .param("rmv", connection.createNullableArray("real", telemetry.rmv))
                .param("ascentRate", connection.createNullableArray("real", telemetry.ascentRate))
                .param("po2", connection.createNullableArray("real", telemetry.po2))
                .param("grit", connection.createNullableArray("real", telemetry.grit))
                .param("flow", connection.createNullableArray("real", telemetry.flow))
                .param("ebikeTravelRange", connection.createNullableArray("integer", telemetry.ebikeTravelRange))
                .param("ebikeBatteryLevel", connection.createNullableArray("smallint", telemetry.ebikeBatteryLevel))
                .param("ebikeAssistMode", connection.createNullableArray("smallint", telemetry.ebikeAssistMode))
                .param("ebikeAssistLevelPercent", connection.createNullableArray("smallint", telemetry.ebikeAssistLevelPercent))
                .param("totalHemoglobinConc", connection.createNullableArray("real", telemetry.totalHemoglobinConc))
                .param("totalHemoglobinConcMin", connection.createNullableArray("real", telemetry.totalHemoglobinConcMin))
                .param("totalHemoglobinConcMax", connection.createNullableArray("real", telemetry.totalHemoglobinConcMax))
                .param("saturatedHemoglobinPercent", connection.createNullableArray("real", telemetry.saturatedHemoglobinPercent))
                .param("saturatedHemoglobinPercentMin", connection.createNullableArray("real", telemetry.saturatedHemoglobinPercentMin))
                .param("saturatedHemoglobinPercentMax", connection.createNullableArray("real", telemetry.saturatedHemoglobinPercentMax))
                .query(Int::class.javaObjectType)
                .single()
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    /**
     * Retrieves telemetry as a JSON string in the nested telemetry contract:
     * `{ activityId, sport, route: GeoJSON LineString | null, bestEfforts, derivedStreams: { gapSpeed, staminaPct },
     * telemetry: { t, routeIndex, heartRate, ... } }`.
     * Enforces user authorization by requiring a match on `activity.user_id = :userId`.
     *
     * The route is deliberately NOT simplified: `telemetry.routeIndex[i]` is an index into the recorded
     * GPS fixes, so `route.coordinates` must contain every fix for that index to stay valid.
     *
     * The telemetry object is assembled from several concatenated `jsonb_build_object` calls because
     * PostgreSQL caps function calls at 100 arguments (50 key/value pairs).
     *
     * @param activityId the ID of the activity.
     * @param userId the ID of the authenticated user requesting the activity telemetry.
     * @param staminaPct the Activity's **Stamina** stream, computed on read by the caller and emitted as
     *   `derivedStreams.staminaPct`; null when it has none.
     * @return raw JSON string conforming to the telemetry response schema, or `null` if the activity
     *   does not exist, has no telemetry, or does not belong to [userId].
     */
    fun findTelemetryJson(
        activityId: Long,
        userId: Long,
        staminaPct: FloatArray? = null,
    ): String? {
        val sql =
            """
            SELECT jsonb_build_object(
                'activityId', a.id,
                'sport', a.sport,
                'route', CASE WHEN d.route IS NOT NULL THEN ST_AsGeoJSON(d.route)::jsonb ELSE NULL END,
                'bestEfforts', COALESCE(
                    (
                        SELECT jsonb_agg(
                            jsonb_build_object(
                                'benchmarkDistance', abe.benchmark_distance,
                                'elapsedTimeSeconds', abe.elapsed_time_seconds,
                                'startIndex', abe.start_index,
                                'endIndex', abe.end_index,
                                'startTimeSeconds', abe.start_time_seconds,
                                'endTimeSeconds', abe.end_time_seconds,
                                'startDistanceMeters', abe.start_distance_meters,
                                'endDistanceMeters', abe.end_distance_meters
                            )
                            ORDER BY (abe.end_distance_meters - abe.start_distance_meters) ASC, abe.id ASC
                        )
                        FROM activity_best_effort abe
                        WHERE abe.activity_id = a.id
                    ),
                    '[]'::jsonb
                ),
                -- Derived streams are emitted only while parallel to t: a stale row never misaligns the charts.
                'derivedStreams', jsonb_build_object(
                    'gapSpeed', CASE WHEN cardinality(m.gap_speed) = cardinality(d.t) THEN m.gap_speed END,
                    'staminaPct', CAST(:staminaPct AS real[])
                ),
                'telemetry', (
                jsonb_build_object(
                    't', d.t,
                    'routeIndex', d.route_index,
                    'heartRate', d.heart_rate,
                    'power', d.power,
                    'cadence', d.cadence,
                    'altitude', d.altitude,
                    'speed', d.speed,
                    'distance', d.distance,
                    'verticalOscillation', d.vertical_oscillation,
                    'stanceTime', d.stance_time,
                    'stanceTimePercent', d.stance_time_percent,
                    'stanceTimeBalance', d.stance_time_balance,
                    'stepLength', d.step_length,
                    'verticalRatio', d.vertical_ratio,
                    'enhancedSpeed', d.enhanced_speed,
                    'enhancedAltitude', d.enhanced_altitude,
                    'enhancedRespirationRate', d.enhanced_respiration_rate,
                    'respirationRate', d.respiration_rate,
                    'temperature', d.temperature,
                    'coreTemperature', d.core_temperature,
                    'fractionalCadence', d.fractional_cadence,
                    'calories', d.calories,
                    'currentStress', d.current_stress,
                    'batterySoc', d.battery_soc,
                    'gpsAccuracy', d.gps_accuracy,
                    'verticalSpeed', d.vertical_speed
                )
                ||
                jsonb_build_object(
                    'grade', d.grade,
                    'resistance', d.resistance,
                    'timeFromCourse', d.time_from_course,
                    'cycleLength', d.cycle_length,
                    'cycles', d.cycles,
                    'totalCycles', d.total_cycles,
                    'accumulatedPower', d.accumulated_power,
                    'leftRightBalance', d.left_right_balance,
                    'leftTorqueEffectiveness', d.left_torque_effectiveness,
                    'rightTorqueEffectiveness', d.right_torque_effectiveness,
                    'leftPedalSmoothness', d.left_pedal_smoothness,
                    'rightPedalSmoothness', d.right_pedal_smoothness,
                    'combinedPedalSmoothness', d.combined_pedal_smoothness,
                    'time128', d.time128,
                    'zone', d.zone,
                    'ballSpeed', d.ball_speed,
                    'cadence256', d.cadence256,
                    'deviceIndex', d.device_index,
                    'leftPco', d.left_pco,
                    'rightPco', d.right_pco,
                    'motorPower', d.motor_power,
                    'cycleLength16', d.cycle_length16
                )
                ||
                jsonb_build_object(
                    'strokeType', d.stroke_type,
                    'absolutePressure', d.absolute_pressure,
                    'depth', d.depth,
                    'nextStopDepth', d.next_stop_depth,
                    'nextStopTime', d.next_stop_time,
                    'timeToSurface', d.time_to_surface,
                    'ndlTime', d.ndl_time,
                    'cnsLoad', d.cns_load,
                    'n2Load', d.n2_load,
                    'airTimeRemaining', d.air_time_remaining,
                    'pressureSac', d.pressure_sac,
                    'volumeSac', d.volume_sac,
                    'rmv', d.rmv,
                    'ascentRate', d.ascent_rate,
                    'po2', d.po2,
                    'grit', d.grit,
                    'flow', d.flow,
                    'ebikeTravelRange', d.ebike_travel_range,
                    'ebikeBatteryLevel', d.ebike_battery_level,
                    'ebikeAssistMode', d.ebike_assist_mode,
                    'ebikeAssistLevelPercent', d.ebike_assist_level_percent,
                    'totalHemoglobinConc', d.total_hemoglobin_conc,
                    'totalHemoglobinConcMin', d.total_hemoglobin_conc_min,
                    'totalHemoglobinConcMax', d.total_hemoglobin_conc_max,
                    'saturatedHemoglobinPercent', d.saturated_hemoglobin_percent,
                    'saturatedHemoglobinPercentMin', d.saturated_hemoglobin_percent_min,
                    'saturatedHemoglobinPercentMax', d.saturated_hemoglobin_percent_max
                )
                )
            )::text
            FROM activity_details d
            JOIN activity a ON a.id = d.activity_id
            LEFT JOIN activity_derived_metrics m ON m.activity_id = a.id
            WHERE a.id = :activityId AND a.user_id = :userId
            """.trimIndent()

        return jdbcClient
            .sql(sql)
            .param("activityId", activityId)
            .param("userId", userId)
            // One text literal cast to real[]: a named array parameter would expand into a list of parameters.
            .param("staminaPct", staminaPct?.joinToString(",", "{", "}"), Types.VARCHAR)
            .query(String::class.java)
            .optional()
            .orElse(null)
    }

    /** Decodes a nullable `real[]` column, keeping null samples; `null` when the column itself is null. */
    private fun ResultSet.getFloatArray(column: String): Array<Float?>? =
        (getArray(column)?.array as? Array<*>)
            ?.map { (it as? Number)?.toFloat() }
            ?.toTypedArray()

    /** Decodes a nullable `smallint[]` column, keeping null samples; `null` when the column itself is null. */
    private fun ResultSet.getShortArray(column: String): Array<Short?>? =
        (getArray(column)?.array as? Array<*>)
            ?.map { (it as? Number)?.toShort() }
            ?.toTypedArray()

    /** Creates a PostgreSQL SQL array for primitive integer values. */
    private fun Connection.createIntArray(items: IntArray?): java.sql.Array =
        if (items == null) {
            createArrayOf("integer", emptyArray())
        } else {
            createArrayOf("integer", items.toTypedArray())
        }

    /** Creates a PostgreSQL SQL array for primitive double coordinates. */
    private fun Connection.createDoubleArray(items: DoubleArray?): java.sql.Array =
        if (items == null) {
            createArrayOf("float8", emptyArray())
        } else {
            createArrayOf("float8", items.toTypedArray())
        }

    /** Creates a PostgreSQL SQL array preserving null values for sensor dropouts. */
    private fun Connection.createNullableArray(
        typeName: String,
        items: Array<*>?,
    ): java.sql.Array =
        if (items == null) {
            createArrayOf(typeName, emptyArray())
        } else {
            createArrayOf(typeName, items)
        }
}

/** What is stored for an Activity, as far as the Activity Details rule needs it. */
data class StoredDetailsState(
    /** The Parser Version of the stored Activity Details, or `null` when none are stored. */
    val version: ParserVersion?,
    val hasDistance: Boolean,
    val lapCount: Int,
)

/**
 * The heatmap route row of each Activity in a `details (activity_id, route)` relation: derived by
 * `heatmap_route_geometries`, the single source of truth for splitting and simplification (ADR-0007). Shared with the
 * Heatmap performance harness (#110), so the write it times is the one [ActivityDetailsRepository.upsert] runs.
 */
internal const val HEATMAP_ROUTE_OF_DETAILS = """
    SELECT a.id AS activity_id, a.user_id, a.sport, a.started_at, g.*
    FROM details d
    JOIN activity a ON a.id = d.activity_id
    CROSS JOIN LATERAL heatmap_route_geometries(d.route, a.sport) AS g
"""

/** Upserts the rows of a `heatmap` relation, as [HEATMAP_ROUTE_OF_DETAILS] gives them, into `heatmap_route` with a fresh stamp. */
internal const val UPSERT_HEATMAP_ROUTE = """
    INSERT INTO heatmap_route (
        activity_id, user_id, sport, started_at, geom_z0, geom_z6, geom_z10, geom_z13, geom_z14, geom_z15, geom_z16
    )
    SELECT activity_id, user_id, sport, started_at, geom_z0, geom_z6, geom_z10, geom_z13, geom_z14, geom_z15, geom_z16
    FROM heatmap
    ON CONFLICT (activity_id) DO UPDATE SET
        user_id = EXCLUDED.user_id,
        sport = EXCLUDED.sport,
        started_at = EXCLUDED.started_at,
        stamp = nextval('heatmap_route_stamp_seq'),
        geom_z0 = EXCLUDED.geom_z0,
        geom_z6 = EXCLUDED.geom_z6,
        geom_z10 = EXCLUDED.geom_z10,
        geom_z13 = EXCLUDED.geom_z13,
        geom_z14 = EXCLUDED.geom_z14,
        geom_z15 = EXCLUDED.geom_z15,
        geom_z16 = EXCLUDED.geom_z16
"""
