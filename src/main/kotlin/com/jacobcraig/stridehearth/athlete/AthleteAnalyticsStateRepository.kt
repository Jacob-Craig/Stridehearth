package com.jacobcraig.stridehearth.athlete

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A pending request to recompute the **Training Load** of [userId]'s Activities dated on or after [from] (an
 * athlete-local date in [zone]; all of them when null). [requestedAt] identifies the request, so the worker clears
 * only the request it served.
 */
data class RecomputeRequest(
    val userId: Long,
    val from: LocalDate?,
    val requestedAt: Instant,
    val zone: ZoneId,
)

/**
 * Data access for `athlete_analytics_state`, one row per athlete holding their athlete-level derived-data bookkeeping:
 * the durable Training Load recompute request (ADR-0014). Raw SQL through [JdbcClient]; every statement is scoped by
 * the athlete.
 */
@Repository
class AthleteAnalyticsStateRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * Requests a recompute of [userId]'s Training Loads from [from] (all when null). A request already pending is
     * merged into this one: the earlier date wins, and null (everything) wins over any date. Each call stamps the
     * request anew, so a worker serving the older stamp keeps it pending.
     */
    fun requestRecompute(
        userId: Long,
        from: LocalDate?,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO athlete_analytics_state (user_id, recompute_from, recompute_requested_at)
                VALUES (:userId, :from, clock_timestamp())
                ON CONFLICT (user_id) DO UPDATE
                SET recompute_from =
                        CASE
                            WHEN athlete_analytics_state.recompute_requested_at IS NULL THEN EXCLUDED.recompute_from
                            WHEN athlete_analytics_state.recompute_from IS NULL OR EXCLUDED.recompute_from IS NULL THEN NULL
                            ELSE LEAST(athlete_analytics_state.recompute_from, EXCLUDED.recompute_from)
                        END,
                    recompute_requested_at = EXCLUDED.recompute_requested_at
                """.trimIndent(),
            ).param("userId", userId)
            .param("from", from, Types.DATE)
            .update()
    }

    /** The pending requests, in athlete id order: every athlete's, or only those of [onlyUserIds]. */
    fun findPendingRequests(onlyUserIds: Collection<Long>? = null): List<RecomputeRequest> {
        if (onlyUserIds != null && onlyUserIds.isEmpty()) return emptyList()
        val userFilter = if (onlyUserIds == null) "" else "AND s.user_id IN (:userIds)"
        return jdbcClient
            .sql(
                """
                SELECT s.user_id, s.recompute_from, s.recompute_requested_at, u.time_zone
                FROM athlete_analytics_state s JOIN app_user u ON u.id = s.user_id
                WHERE s.recompute_requested_at IS NOT NULL $userFilter
                ORDER BY s.user_id
                """.trimIndent(),
            ).apply { if (onlyUserIds != null) param("userIds", onlyUserIds.distinct()) }
            .query { rs, _ ->
                RecomputeRequest(
                    userId = rs.getLong("user_id"),
                    from = rs.getObject("recompute_from", LocalDate::class.java),
                    requestedAt = rs.getTimestamp("recompute_requested_at").toInstant(),
                    zone = AthleteTimeZone.zoneOf(rs.getString("time_zone")),
                )
            }.list()
    }

    /** Clears [request] if it is still the pending one; returns false when a newer request has replaced it. */
    fun clearRequest(request: RecomputeRequest): Boolean =
        jdbcClient
            .sql(
                """
                UPDATE athlete_analytics_state SET recompute_from = NULL, recompute_requested_at = NULL
                WHERE user_id = :userId AND recompute_requested_at = :requestedAt
                """.trimIndent(),
            ).param("userId", request.userId)
            .param("requestedAt", Timestamp.from(request.requestedAt))
            .update() > 0
}
