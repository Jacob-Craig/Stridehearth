package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.PeakEffort
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** An athlete's best **Peak Effort** for one metric and **Benchmark Duration**, with the **Activity** holding it. */
data class BestPeakEffortRow(
    val metric: PeakEffortMetric,
    val benchmarkDuration: BenchmarkDuration,
    val value: Double,
    val activityId: Long,
    val activityTitle: String,
    val activityStartedAt: Instant,
)

/**
 * Data access for `activity_peak_effort`: an **Activity**'s **Peak Efforts**, one row per metric and **Benchmark
 * Duration**. Raw SQL through [JdbcClient], like `activity_derived_metrics`.
 *
 * Written only by [DerivedDataRefresh]; rows are removed with their Activity by `ON DELETE CASCADE`.
 */
@Repository
class ActivityPeakEffortRepository(
    private val jdbcClient: JdbcClient,
) {
    /** Replaces whatever Peak Efforts [activityId] has with [peakEfforts]. */
    fun replace(
        activityId: Long,
        peakEfforts: List<PeakEffort>,
    ) {
        jdbcClient
            .sql("DELETE FROM activity_peak_effort WHERE activity_id = :activityId")
            .param("activityId", activityId)
            .update()
        if (peakEfforts.isEmpty()) return
        // One multi-row INSERT (at most 22 rows), not a round trip per Peak Effort.
        val rows =
            peakEfforts.indices.joinToString(",\n") { i ->
                "(:activityId, :metric$i, :duration$i, :value$i, :startIndex$i, :endIndex$i, :startTime$i, :endTime$i)"
            }
        val params = mutableMapOf<String, Any>("activityId" to activityId)
        peakEfforts.forEachIndexed { i, peak ->
            params["metric$i"] = peak.metric.name
            params["duration$i"] = peak.benchmarkDuration.name
            params["value$i"] = peak.value
            params["startIndex$i"] = peak.startIndex
            params["endIndex$i"] = peak.endIndex
            params["startTime$i"] = peak.startTimeSeconds
            params["endTime$i"] = peak.endTimeSeconds
        }
        jdbcClient
            .sql(
                """
                INSERT INTO activity_peak_effort (activity_id, metric, benchmark_duration, value, start_index,
                    end_index, start_time_seconds, end_time_seconds)
                VALUES
                """.trimIndent() + "\n" + rows,
            ).params(params)
            .update()
    }

    /** [activityId]'s stored Peak Efforts, by metric then Benchmark Duration (shortest first). */
    fun findByActivityId(activityId: Long): List<PeakEffort> =
        jdbcClient
            .sql(
                """
                SELECT metric, benchmark_duration, value, start_index, end_index, start_time_seconds, end_time_seconds
                FROM activity_peak_effort
                WHERE activity_id = :activityId
                """.trimIndent(),
            ).param("activityId", activityId)
            .query { rs, _ ->
                PeakEffort(
                    metric = PeakEffortMetric.valueOf(rs.getString("metric")),
                    benchmarkDuration = BenchmarkDuration.valueOf(rs.getString("benchmark_duration")),
                    value = rs.getDouble("value"),
                    startIndex = rs.getInt("start_index"),
                    endIndex = rs.getInt("end_index"),
                    startTimeSeconds = rs.getDouble("start_time_seconds"),
                    endTimeSeconds = rs.getDouble("end_time_seconds"),
                )
            }.list()
            .sortedWith(compareBy({ it.metric }, { it.benchmarkDuration }))

    /**
     * [userId]'s best Peak Effort per metric and Benchmark Duration, over their Runs started at or after [since]
     * (all of them when null). A tie goes to the earlier Activity.
     */
    fun findBestPeakEfforts(
        userId: Long,
        since: Instant? = null,
    ): List<BestPeakEffortRow> {
        val sinceFilter = if (since == null) "" else "AND a.started_at >= :since"
        return jdbcClient
            .sql(
                """
                SELECT DISTINCT ON (pe.metric, pe.benchmark_duration)
                    pe.metric, pe.benchmark_duration, pe.value, a.id AS activity_id, a.title AS activity_title,
                    a.started_at AS activity_started_at
                FROM activity_peak_effort pe
                JOIN activity a ON a.id = pe.activity_id
                WHERE a.user_id = :userId
                  AND a.sport = 'RUN'
                  $sinceFilter
                ORDER BY pe.metric, pe.benchmark_duration, pe.value DESC, a.started_at, a.id
                """.trimIndent(),
            ).param("userId", userId)
            .apply { if (since != null) param("since", Timestamp.from(since)) }
            .query { rs, _ ->
                BestPeakEffortRow(
                    metric = PeakEffortMetric.valueOf(rs.getString("metric")),
                    benchmarkDuration = BenchmarkDuration.valueOf(rs.getString("benchmark_duration")),
                    value = rs.getDouble("value"),
                    activityId = rs.getLong("activity_id"),
                    activityTitle = rs.getString("activity_title"),
                    activityStartedAt = rs.getTimestamp("activity_started_at").toInstant(),
                )
            }.list()
    }
}
