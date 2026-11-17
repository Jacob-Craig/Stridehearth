package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.PeakEffort
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

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
        for (peak in peakEfforts) {
            jdbcClient
                .sql(
                    """
                    INSERT INTO activity_peak_effort (activity_id, metric, benchmark_duration, value, start_index,
                        end_index, start_time_seconds, end_time_seconds)
                    VALUES (:activityId, :metric, :duration, :value, :startIndex, :endIndex, :startTime, :endTime)
                    """.trimIndent(),
                ).param("activityId", activityId)
                .param("metric", peak.metric.name)
                .param("duration", peak.benchmarkDuration.name)
                .param("value", peak.value)
                .param("startIndex", peak.startIndex)
                .param("endIndex", peak.endIndex)
                .param("startTime", peak.startTimeSeconds)
                .param("endTime", peak.endTimeSeconds)
                .update()
        }
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
}
