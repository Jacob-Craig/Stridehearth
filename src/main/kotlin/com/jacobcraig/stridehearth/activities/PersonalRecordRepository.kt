package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp

data class PersonalRecordRow(
    val benchmarkDistance: BenchmarkDistance,
    val bestEffortId: Long,
    val activityId: Long,
    val activityTitle: String,
    val activityStartedAt: java.time.Instant,
    val elapsedTimeSeconds: Double,
)

/** One step of a **Personal Record Progression**: a Best Effort faster than every earlier one for its distance. */
data class PersonalRecordStepRow(
    val benchmarkDistance: BenchmarkDistance,
    val activityId: Long,
    val activityTitle: String,
    val activityStartedAt: java.time.Instant,
    val elapsedTimeSeconds: Double,
)

/** The **Dashboard** read of an athlete's **Personal Records**. Only [BestEffortRefresh] writes them. */
@Repository
class PersonalRecordRepository(
    private val jdbcClient: JdbcClient,
) {
    fun findByUserIdAndSport(
        userId: Long,
        sport: ActivitySport,
    ): List<PersonalRecordRow> {
        val sql =
            """
            SELECT
                upr.benchmark_distance,
                upr.best_effort_id,
                upr.activity_id,
                a.title AS activity_title,
                a.started_at AS activity_started_at,
                upr.elapsed_time_seconds
            FROM user_personal_record upr
            JOIN activity a ON a.id = upr.activity_id
            WHERE upr.user_id = :userId
              AND upr.sport = :sport
              AND a.user_id = :userId
            """.trimIndent()

        return jdbcClient
            .sql(sql)
            .param("userId", userId)
            .param("sport", sport.name)
            .query { rs, _ ->
                PersonalRecordRow(
                    benchmarkDistance = BenchmarkDistance.valueOf(rs.getString("benchmark_distance")),
                    bestEffortId = rs.getLong("best_effort_id"),
                    activityId = rs.getLong("activity_id"),
                    activityTitle = rs.getString("activity_title"),
                    activityStartedAt = (rs.getTimestamp("activity_started_at") as Timestamp).toInstant(),
                    elapsedTimeSeconds = rs.getDouble("elapsed_time_seconds"),
                )
            }.list()
    }

    /**
     * [userId]'s **Personal Record Progression** in [sport]: each Best Effort strictly faster than every Best Effort
     * for its Benchmark Distance from an earlier Activity (ordered by `started_at`, then id), oldest first. It is
     * computed from the stored Best Efforts on every read, so the order Activities were imported in, and Activities
     * deleted since, never matter; a tie leaves the earlier Activity holding the record, so the last step is always
     * the current Personal Record.
     */
    fun findProgression(
        userId: Long,
        sport: ActivitySport,
    ): List<PersonalRecordStepRow> {
        val sql =
            """
            SELECT benchmark_distance, activity_id, activity_title, activity_started_at, elapsed_time_seconds
            FROM (
                SELECT
                    abe.benchmark_distance,
                    abe.activity_id,
                    a.id AS sort_id,
                    a.title AS activity_title,
                    a.started_at AS activity_started_at,
                    abe.elapsed_time_seconds,
                    MIN(abe.elapsed_time_seconds) OVER (
                        PARTITION BY abe.benchmark_distance
                        ORDER BY a.started_at, a.id
                        ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
                    ) AS earlier_best
                FROM activity_best_effort abe
                JOIN activity a ON a.id = abe.activity_id
                WHERE a.user_id = :userId
                  AND abe.sport = :sport
            ) efforts
            WHERE earlier_best IS NULL OR elapsed_time_seconds < earlier_best
            ORDER BY benchmark_distance, activity_started_at, sort_id
            """.trimIndent()

        return jdbcClient
            .sql(sql)
            .param("userId", userId)
            .param("sport", sport.name)
            .query { rs, _ ->
                PersonalRecordStepRow(
                    benchmarkDistance = BenchmarkDistance.valueOf(rs.getString("benchmark_distance")),
                    activityId = rs.getLong("activity_id"),
                    activityTitle = rs.getString("activity_title"),
                    activityStartedAt = (rs.getTimestamp("activity_started_at") as Timestamp).toInstant(),
                    elapsedTimeSeconds = rs.getDouble("elapsed_time_seconds"),
                )
            }.list()
    }
}
