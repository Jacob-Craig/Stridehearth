package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.WorkoutEvidence
import com.jacobcraig.stridehearth.analytics.WorkoutType
import com.jacobcraig.stridehearth.analytics.WorkoutTypeClassifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant

/** The Runs a neighbour's change may have reclassified: those of [userId] started in [from, to], served together. */
data class WorkoutTypeRequest(
    val userId: Long,
    val from: Instant,
    val to: Instant,
    /** The newest request id merged into this one: the worker clears the ones up to it, and leaves later ones pending. */
    val lastId: Long,
)

/** A Run's stored [evidence] with the Moving Time it was classified with, and the type it holds now. */
data class StoredWorkoutEvidence(
    val activityId: Long,
    val userId: Long,
    val startedAt: Instant,
    val movingSeconds: Int,
    val evidence: WorkoutEvidence,
    val workoutType: WorkoutType?,
)

/**
 * Data access for a Run's **Workout Type**: the median Moving Time it is compared with, the stored evidence it is
 * reclassified from, and the durable requests to reclassify neighbours (`workout_type_request`).
 *
 * The type and its evidence are columns of `activity_derived_metrics`, written by [ActivityDerivedMetricsRepository] from
 * [DerivedDataRefresh] and, for a neighbour's change, by [setTypes] from the recompute worker (ADR-0014): a refresh never
 * writes another Activity's row, so it only appends a request here.
 */
@Repository
class WorkoutTypeRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * The median Moving Time in seconds of [userId]'s Runs started in the [WorkoutTypeClassifier.MEDIAN_WINDOW_DAYS] days
     * before [startedAt] (not at it), and how many Runs that is; the median is null for none.
     */
    fun findMedianMovingSeconds(
        userId: Long,
        startedAt: Instant,
    ): Pair<Double?, Int> {
        val seconds =
            jdbcClient
                .sql(
                    """
                    SELECT m.moving_seconds
                    FROM activity a JOIN activity_derived_metrics m ON m.activity_id = a.id
                    WHERE a.user_id = :userId AND a.sport = 'RUN' AND m.moving_seconds IS NOT NULL
                      AND a.started_at >= :from AND a.started_at < :startedAt
                    ORDER BY m.moving_seconds
                    """.trimIndent(),
                ).param("userId", userId)
                .param("from", Timestamp.from(startedAt.minus(Duration.ofDays(WorkoutTypeClassifier.MEDIAN_WINDOW_DAYS))))
                .param("startedAt", Timestamp.from(startedAt))
                .query { rs, _ -> rs.getInt("moving_seconds") }
                .list()
        if (seconds.isEmpty()) return null to 0
        val middle = seconds.size / 2
        val median = if (seconds.size % 2 == 1) seconds[middle].toDouble() else (seconds[middle - 1] + seconds[middle]) / 2.0
        return median to seconds.size
    }

    /** The stored evidence of each of [activityIds] that is a Run with some, in id order. */
    fun findEvidence(activityIds: Collection<Long>): List<StoredWorkoutEvidence> {
        if (activityIds.isEmpty()) return emptyList()
        return jdbcClient
            .sql(
                """
                SELECT a.id, a.user_id, a.started_at, m.moving_seconds, m.workout_type, m.wt_threshold_work_intervals,
                    m.wt_tempo_seconds, m.wt_easy_share, m.wt_race_intensity, m.wt_distance_meters
                FROM activity a JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE a.id IN (:ids) AND a.sport = 'RUN' AND m.moving_seconds IS NOT NULL
                  AND m.wt_threshold_work_intervals IS NOT NULL
                ORDER BY a.id
                """.trimIndent(),
            ).param("ids", activityIds.distinct())
            .query { rs, _ ->
                StoredWorkoutEvidence(
                    activityId = rs.getLong("id"),
                    userId = rs.getLong("user_id"),
                    startedAt = rs.getTimestamp("started_at").toInstant(),
                    movingSeconds = rs.getInt("moving_seconds"),
                    workoutType = WorkoutType.fromStored(rs.getString("workout_type")),
                    evidence =
                        WorkoutEvidence(
                            thresholdWorkIntervals = rs.getInt("wt_threshold_work_intervals"),
                            tempoSeconds = rs.getInt("wt_tempo_seconds"),
                            easyShare = rs.getObject("wt_easy_share") as Double?,
                            raceIntensity = rs.getObject("wt_race_intensity") as Double?,
                            distanceMeters = rs.getObject("wt_distance_meters") as Double?,
                        ),
                )
            }.list()
    }

    /** Stores [types] by Activity id; returns how many rows it changed (a row already holding its type is not written). */
    fun setTypes(types: Map<Long, WorkoutType>): Int =
        types.entries.sortedBy { it.key }.sumOf { (activityId, type) ->
            jdbcClient
                .sql(
                    """
                    UPDATE activity_derived_metrics SET workout_type = :type
                    WHERE activity_id = :activityId AND workout_type IS DISTINCT FROM :type
                    """.trimIndent(),
                ).param("type", type.name)
                .param("activityId", activityId)
                .update()
        }

    /** The workout type stored for [activityId], or null for none. */
    fun findType(activityId: Long): WorkoutType? =
        jdbcClient
            .sql("SELECT workout_type FROM activity_derived_metrics WHERE activity_id = :activityId")
            .param("activityId", activityId)
            .query { rs, _ -> WorkoutType.fromStored(rs.getString("workout_type")) }
            .optional()
            .orElse(null)

    /**
     * Appends a request to reclassify [userId]'s Runs started from [moment] until [WorkoutTypeClassifier.MEDIAN_WINDOW_DAYS]
     * days after it: the Runs whose 28-day median includes an Activity that started, changed or was deleted at [moment].
     * It only inserts, so concurrent writers never wait on each other here.
     */
    fun request(
        userId: Long,
        moment: Instant,
    ) {
        jdbcClient
            .sql("INSERT INTO workout_type_request (user_id, range_from, range_to) VALUES (:userId, :from, :to)")
            .param("userId", userId)
            .param("from", Timestamp.from(moment))
            .param("to", Timestamp.from(moment.plus(Duration.ofDays(WorkoutTypeClassifier.MEDIAN_WINDOW_DAYS))))
            .update()
    }

    /** The pending requests merged per athlete, in athlete id order: every athlete's, or only those of [onlyUserIds]. */
    fun findPending(onlyUserIds: Collection<Long>? = null): List<WorkoutTypeRequest> {
        if (onlyUserIds != null && onlyUserIds.isEmpty()) return emptyList()
        val userFilter = if (onlyUserIds == null) "" else "WHERE user_id IN (:userIds)"
        return jdbcClient
            .sql(
                """
                SELECT user_id, min(range_from) AS range_from, max(range_to) AS range_to, max(id) AS last_id
                FROM workout_type_request $userFilter GROUP BY user_id ORDER BY user_id
                """.trimIndent(),
            ).apply { if (onlyUserIds != null) param("userIds", onlyUserIds.distinct()) }
            .query { rs, _ ->
                WorkoutTypeRequest(
                    userId = rs.getLong("user_id"),
                    from = rs.getTimestamp("range_from").toInstant(),
                    to = rs.getTimestamp("range_to").toInstant(),
                    lastId = rs.getLong("last_id"),
                )
            }.list()
    }

    /** Clears the requests merged into [request]; later ones stay pending. */
    fun clear(request: WorkoutTypeRequest) {
        jdbcClient
            .sql("DELETE FROM workout_type_request WHERE user_id = :userId AND id <= :lastId")
            .param("userId", request.userId)
            .param("lastId", request.lastId, Types.BIGINT)
            .update()
    }

    /** The next [limit] Runs of [userId] after [afterActivityId], in id order, that have evidence and started in [from, to]. */
    fun findRunIdsInRange(
        userId: Long,
        from: Instant,
        to: Instant,
        afterActivityId: Long,
        limit: Int,
    ): List<Long> =
        jdbcClient
            .sql(
                """
                SELECT a.id FROM activity a JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE a.user_id = :userId AND a.sport = 'RUN' AND m.wt_threshold_work_intervals IS NOT NULL
                  AND a.started_at >= :from AND a.started_at <= :to AND a.id > :afterId
                ORDER BY a.id LIMIT :limit
                """.trimIndent(),
            ).param("userId", userId)
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .param("afterId", afterActivityId)
            .param("limit", limit)
            .query { rs, _ -> rs.getLong("id") }
            .list()
}
