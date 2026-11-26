package com.jacobcraig.stridehearth.weather

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

/** The weather at an Activity's start hour, as a provider reports it. Any value is null when the provider had none. */
data class WeatherObservation(
    val temperatureC: Double?,
    val dewPointC: Double?,
    val humidityPct: Double?,
    val windSpeedMs: Double?,
)

/** An Activity still to look up: its id, its start, and its start point (not rounded; [OpenMeteoClient] rounds it). */
data class WeatherLookupCandidate(
    val activityId: Long,
    val startedAt: Instant,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Data access for `activity_weather` (ADR-0017): the weather cached per Activity by the opt-in lookup. Always present,
 * because the derived-data refresh reads the cache; it is empty unless an Operator enabled a provider.
 */
@Repository
class ActivityWeatherRepository(
    private val jdbcClient: JdbcClient,
) {
    /** The cached air temperature of [activityId] in °C, or null when no lookup is cached or it had none. */
    fun findTemperatureC(activityId: Long): Double? =
        jdbcClient
            .sql("SELECT temperature_c FROM activity_weather WHERE activity_id = :id")
            .param("id", activityId)
            .query { rs, _ -> (rs.getObject("temperature_c") as Number?)?.toDouble() }
            .optional()
            .orElse(null)

    /** Caches [observation] for [activityId]; returns false when a lookup was already cached. */
    fun insert(
        activityId: Long,
        observation: WeatherObservation,
    ): Boolean =
        jdbcClient
            .sql(
                """
                INSERT INTO activity_weather (activity_id, temperature_c, dew_point_c, humidity_pct, wind_speed_ms)
                VALUES (:id, :temperature, :dewPoint, :humidity, :wind)
                ON CONFLICT (activity_id) DO NOTHING
                """.trimIndent(),
            ).param("id", activityId)
            .param("temperature", observation.temperatureC, Types.REAL)
            .param("dewPoint", observation.dewPointC, Types.REAL)
            .param("humidity", observation.humidityPct, Types.REAL)
            .param("wind", observation.windSpeedMs, Types.REAL)
            .update() > 0

    /**
     * Up to [limit] Activities, lowest id first, that need a lookup: no cached weather, a GPS start point, no device
     * temperature (the device value stays the default), derived data of the current [derivedDataVersion], and a start
     * before [startedBefore] (ERA5 lags real time by about five days).
     */
    fun findLookupCandidates(
        startedBefore: Instant,
        derivedDataVersion: Short,
        limit: Int,
    ): List<WeatherLookupCandidate> =
        jdbcClient
            .sql(
                """
                SELECT a.id, a.started_at, ST_Y(ST_StartPoint(d.route)) AS lat, ST_X(ST_StartPoint(d.route)) AS lon
                FROM activity a
                JOIN activity_details d ON d.activity_id = a.id
                JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE d.route IS NOT NULL
                  AND m.algorithm_version = :version
                  AND m.device_temperature_c IS NULL
                  AND a.started_at < :before
                  AND NOT EXISTS (SELECT 1 FROM activity_weather w WHERE w.activity_id = a.id)
                ORDER BY a.id
                LIMIT :limit
                """.trimIndent(),
            ).param("version", derivedDataVersion, Types.SMALLINT)
            .param("before", Timestamp.from(startedBefore))
            .param("limit", limit)
            .query { rs, _ ->
                WeatherLookupCandidate(
                    rs.getLong("id"),
                    rs.getTimestamp("started_at").toInstant(),
                    rs.getDouble("lat"),
                    rs.getDouble("lon"),
                )
            }.list()
}
