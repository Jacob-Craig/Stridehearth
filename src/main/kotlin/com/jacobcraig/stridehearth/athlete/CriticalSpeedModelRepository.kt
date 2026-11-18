package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.CriticalSpeedFit
import com.jacobcraig.stridehearth.analytics.CriticalSpeedFitter
import com.jacobcraig.stridehearth.analytics.CriticalSpeedStatus
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Date
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** One Run **Peak Effort** at a fit duration, on the athlete-local [day] its Activity started. */
data class FitPeak(
    val day: LocalDate,
    val metric: PeakEffortMetric,
    val benchmarkDuration: BenchmarkDuration,
    val value: Double,
    val activityId: Long,
    val activityStartedAt: Instant,
)

/** A stored model: [metric]'s fit over the window ending on the athlete-local [modelDate]. */
data class StoredCriticalSpeedModel(
    val metric: PeakEffortMetric,
    val modelDate: LocalDate,
    val fit: CriticalSpeedFit,
)

/**
 * Data access for `athlete_critical_speed_model` and the Run Peak Efforts its models are fitted from. Raw SQL through
 * [JdbcClient]; every statement is scoped by the athlete. Local days are counted in the zone the caller passes.
 */
@Repository
class CriticalSpeedModelRepository(
    private val jdbcClient: JdbcClient,
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * [userId]'s Run Peak Efforts at the [CriticalSpeedFitter.FIT_DURATIONS], of Activities whose local start day in
     * [zone] is in [from, to] (unbounded on a null side).
     */
    fun findFitPeaks(
        userId: Long,
        zone: ZoneId,
        from: LocalDate?,
        to: LocalDate?,
    ): List<FitPeak> =
        jdbcClient
            .sql(
                """
                SELECT (a.started_at AT TIME ZONE :zone)::date AS day, pe.metric, pe.benchmark_duration, pe.value,
                       a.id AS activity_id, a.started_at
                FROM activity_peak_effort pe JOIN activity a ON a.id = pe.activity_id
                WHERE a.user_id = :userId AND a.sport = 'RUN' AND pe.benchmark_duration IN (:durations)
                  ${dayFilter(from, to)}
                """.trimIndent(),
            ).param("userId", userId)
            .param("zone", sqlZone(zone))
            .param("durations", CriticalSpeedFitter.FIT_DURATIONS.map { it.name })
            .withDays(from, to)
            .query { rs, _ ->
                FitPeak(
                    day = rs.getObject("day", LocalDate::class.java),
                    metric = PeakEffortMetric.valueOf(rs.getString("metric")),
                    benchmarkDuration = BenchmarkDuration.valueOf(rs.getString("benchmark_duration")),
                    value = rs.getDouble("value"),
                    activityId = rs.getLong("activity_id"),
                    activityStartedAt = rs.getTimestamp("started_at").toInstant(),
                )
            }.list()

    /** The local days in [zone] within [from, to] (unbounded on a null side) on which [userId] started a Run, ascending. */
    fun findRunDays(
        userId: Long,
        zone: ZoneId,
        from: LocalDate?,
        to: LocalDate?,
    ): List<LocalDate> =
        jdbcClient
            .sql(
                """
                SELECT DISTINCT (a.started_at AT TIME ZONE :zone)::date AS day
                FROM activity a
                WHERE a.user_id = :userId AND a.sport = 'RUN' ${dayFilter(from, to)}
                ORDER BY day
                """.trimIndent(),
            ).param("userId", userId)
            .param("zone", sqlZone(zone))
            .withDays(from, to)
            .query { rs, _ -> rs.getObject("day", LocalDate::class.java) }
            .list()

    /** Replaces [userId]'s stored models dated in [from, to] (every one when both are null) with [models]. */
    fun replace(
        userId: Long,
        from: LocalDate?,
        to: LocalDate?,
        models: List<StoredCriticalSpeedModel>,
    ) {
        val filter =
            buildString {
                if (from != null) append(" AND model_date >= :from")
                if (to != null) append(" AND model_date <= :to")
            }
        jdbcClient
            .sql("DELETE FROM athlete_critical_speed_model WHERE user_id = :userId$filter")
            .param("userId", userId)
            .apply { if (from != null) param("from", from, Types.DATE) }
            .apply { if (to != null) param("to", to, Types.DATE) }
            .update()
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO athlete_critical_speed_model
                (user_id, metric, model_date, status, critical_value, reserve_capacity, r_squared, point_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            models,
            BATCH_SIZE,
        ) { ps, model ->
            ps.setLong(1, userId)
            ps.setString(2, model.metric.name)
            ps.setDate(3, Date.valueOf(model.modelDate))
            ps.setString(4, model.fit.status.name)
            ps.setObject(5, model.fit.criticalValue, Types.DOUBLE)
            ps.setObject(6, model.fit.reserveCapacity, Types.DOUBLE)
            ps.setObject(7, model.fit.rSquared, Types.DOUBLE)
            ps.setInt(8, model.fit.pointCount)
        }
    }

    /** [userId]'s stored [metric] model for [date], or null when none is stored for that day. */
    fun find(
        userId: Long,
        metric: PeakEffortMetric,
        date: LocalDate,
    ): StoredCriticalSpeedModel? =
        jdbcClient
            .sql("SELECT * FROM athlete_critical_speed_model WHERE user_id = :userId AND metric = :metric AND model_date = :date")
            .param("userId", userId)
            .param("metric", metric.name)
            .param("date", date, Types.DATE)
            .query { rs, _ -> rs.toModel() }
            .optional()
            .orElse(null)

    /** [userId]'s latest stored [metric] model, or null when they have none. */
    fun findLatest(
        userId: Long,
        metric: PeakEffortMetric,
    ): StoredCriticalSpeedModel? =
        jdbcClient
            .sql(
                """
                SELECT * FROM athlete_critical_speed_model WHERE user_id = :userId AND metric = :metric
                ORDER BY model_date DESC LIMIT 1
                """.trimIndent(),
            ).param("userId", userId)
            .param("metric", metric.name)
            .query { rs, _ -> rs.toModel() }
            .optional()
            .orElse(null)

    /** Every stored model of [userId], by metric then date. */
    fun findAll(userId: Long): List<StoredCriticalSpeedModel> =
        jdbcClient
            .sql("SELECT * FROM athlete_critical_speed_model WHERE user_id = :userId ORDER BY metric, model_date")
            .param("userId", userId)
            .query { rs, _ -> rs.toModel() }
            .list()

    private fun dayFilter(
        from: LocalDate?,
        to: LocalDate?,
    ): String =
        buildString {
            if (from != null) append(" AND (a.started_at AT TIME ZONE :zone)::date >= :from")
            if (to != null) append(" AND (a.started_at AT TIME ZONE :zone)::date <= :to")
        }

    private fun JdbcClient.StatementSpec.withDays(
        from: LocalDate?,
        to: LocalDate?,
    ): JdbcClient.StatementSpec =
        apply { if (from != null) param("from", from, Types.DATE) }
            .apply { if (to != null) param("to", to, Types.DATE) }

    private fun ResultSet.toModel() =
        StoredCriticalSpeedModel(
            metric = PeakEffortMetric.valueOf(getString("metric")),
            modelDate = getObject("model_date", LocalDate::class.java),
            fit =
                CriticalSpeedFit(
                    status = CriticalSpeedStatus.valueOf(getString("status")),
                    criticalValue = getObject("critical_value") as Double?,
                    reserveCapacity = getObject("reserve_capacity") as Double?,
                    rSquared = getObject("r_squared") as Double?,
                    pointCount = getInt("point_count"),
                ),
        )

    private companion object {
        const val BATCH_SIZE = 500

        /** PostgreSQL knows UTC by that name, not as java.time's "Z". */
        fun sqlZone(zone: ZoneId): String = if (zone == ZoneOffset.UTC) "UTC" else zone.id
    }
}
