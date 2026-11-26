package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.DetectedInterval
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Data access for `activity_detected_interval`: a Run's **Detected Intervals**, in order. Raw SQL through [JdbcClient],
 * like `activity_peak_effort`.
 *
 * Written only by [DerivedDataRefresh]; rows are removed with their Activity by `ON DELETE CASCADE`.
 */
@Repository
class ActivityDetectedIntervalRepository(
    private val jdbcClient: JdbcClient,
) {
    /** Replaces whatever Detected Intervals [activityId] has with [intervals], numbered from 1 in the order given. */
    fun replace(
        activityId: Long,
        intervals: List<DetectedInterval>,
    ) {
        jdbcClient
            .sql("DELETE FROM activity_detected_interval WHERE activity_id = :activityId")
            .param("activityId", activityId)
            .update()
        if (intervals.isEmpty()) return
        val rows =
            intervals.indices.joinToString(",\n") { i ->
                "(:activityId, ${i + 1}, :startIndex$i, :endIndex$i, :startTime$i, :endTime$i, :moving$i, :work$i, " +
                    ":distance$i, :gap$i, :hr$i, :power$i, :decoupling$i)"
            }
        val params = mutableMapOf<String, Any?>("activityId" to activityId)
        intervals.forEachIndexed { i, interval ->
            params["startIndex$i"] = interval.startIndex
            params["endIndex$i"] = interval.endIndex
            params["startTime$i"] = interval.startTimeSeconds
            params["endTime$i"] = interval.endTimeSeconds
            params["moving$i"] = interval.movingSeconds
            params["work$i"] = interval.isWork
            params["distance$i"] = interval.distanceMeters
            params["gap$i"] = interval.avgGap
            params["hr$i"] = interval.avgHr
            params["power$i"] = interval.avgPower
            params["decoupling$i"] = interval.decouplingPct
        }
        jdbcClient
            .sql(
                """
                INSERT INTO activity_detected_interval (activity_id, interval_number, start_index, end_index,
                    start_time_seconds, end_time_seconds, moving_seconds, is_work, distance_meters, avg_gap, avg_hr,
                    avg_power, decoupling_pct)
                VALUES
                """.trimIndent() + "\n" + rows,
            ).params(params)
            .update()
    }

    /** [activityId]'s stored Detected Intervals, in order. */
    fun findByActivityId(activityId: Long): List<StoredDetectedInterval> =
        jdbcClient
            .sql(
                """
                SELECT interval_number, start_index, end_index, start_time_seconds, end_time_seconds, moving_seconds,
                    is_work, distance_meters, avg_gap, avg_hr, avg_power, decoupling_pct
                FROM activity_detected_interval
                WHERE activity_id = :activityId
                ORDER BY interval_number
                """.trimIndent(),
            ).param("activityId", activityId)
            .query { rs, _ ->
                StoredDetectedInterval(
                    intervalNumber = rs.getInt("interval_number"),
                    interval =
                        DetectedInterval(
                            startIndex = rs.getInt("start_index"),
                            endIndex = rs.getInt("end_index"),
                            startTimeSeconds = rs.getDouble("start_time_seconds"),
                            endTimeSeconds = rs.getDouble("end_time_seconds"),
                            movingSeconds = rs.getInt("moving_seconds"),
                            isWork = rs.getBoolean("is_work"),
                            distanceMeters = rs.getDouble("distance_meters").takeUnless { rs.wasNull() },
                            avgGap = rs.getDouble("avg_gap"),
                            avgHr = rs.getDouble("avg_hr").takeUnless { rs.wasNull() },
                            avgPower = rs.getDouble("avg_power").takeUnless { rs.wasNull() },
                            decouplingPct = rs.getDouble("decoupling_pct").takeUnless { rs.wasNull() },
                        ),
                )
            }.list()
}

/** A **Detected Interval** as stored, with its 1-based [intervalNumber] in the Run. */
data class StoredDetectedInterval(
    val intervalNumber: Int,
    val interval: DetectedInterval,
)
