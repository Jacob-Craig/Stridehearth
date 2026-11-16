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
}
