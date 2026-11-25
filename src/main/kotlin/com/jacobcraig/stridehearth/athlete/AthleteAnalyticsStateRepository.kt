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
 * the durable Training Load recompute request (ADR-0014) and the version of their rollups (ADR-0016). Raw SQL through [JdbcClient]; every statement is scoped by
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
     *
     * It first takes [userId]'s scoring lock exclusively (see [lockScoringInputs]), so it waits for every Activity
     * write that already scored a Training Load against their old Thresholds or zone to commit: the worker serving this
     * request then sees those Activities.
     */
    fun requestRecompute(
        userId: Long,
        from: LocalDate?,
    ) {
        advisoryLock("pg_advisory_xact_lock", userId)
        jdbcClient
            .sql(
                """
                INSERT INTO athlete_analytics_state (user_id, recompute_from, recompute_requested_at, rollup_version)
                VALUES (
                    :userId, :from, clock_timestamp(),
                    CASE WHEN EXISTS (SELECT 1 FROM activity WHERE user_id = :userId) THEN 0 ELSE :rollupVersion END
                )
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
            // A new row is current only for an athlete with no Activities. One who has Activities but no row got them
            // without a writer (inserted by SQL, ADR-0014), so no rollup of theirs was ever built: the worker builds it.
            .param("rollupVersion", ROLLUP_VERSION)
            .update()
    }

    /**
     * Marks [userId]'s rollups stale, so the recompute worker rebuilds them in full and the before-commit rollup leaves
     * them alone until it has. Takes the scoring lock first, as [requestRecompute] does, so it never waits on a row
     * while a writer that holds the scoring lock waits on it.
     *
     * An athlete with no Activities has no rows to rebuild, so their rollups are left as they are: otherwise their first
     * import would skip its rollup and wait for the worker. The scoring lock makes that check safe: a writer that
     * already holds it has committed its Activity by now, and one that takes it later reads the new zone.
     */
    fun markRollupsStale(userId: Long) {
        advisoryLock("pg_advisory_xact_lock", userId)
        jdbcClient
            .sql(
                """
                INSERT INTO athlete_analytics_state (user_id, rollup_version)
                SELECT :userId, 0 WHERE EXISTS (SELECT 1 FROM activity WHERE user_id = :userId)
                ON CONFLICT (user_id) DO UPDATE SET rollup_version = 0
                """.trimIndent(),
            ).param("userId", userId)
            .update()
    }

    /**
     * Takes [userId]'s rollup lock until the transaction ends (ADR-0016): the last lock any transaction takes, only
     * before commit, so its holder never waits on another lock while holding it.
     */
    fun lockRollups(userId: Long) {
        jdbcClient
            .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .param("key", "$ROLLUP_LOCK_NAMESPACE:$userId")
            .query { _, _ -> }
            .list()
    }

    /**
     * The version [userId]'s stored rollups were computed at, or null when they have no state row (an athlete who
     * joined after rollups existed and has had none marked). With [forUpdate], the row stays locked to commit, so a
     * concurrent time-zone change cannot mark it stale between a rebuild's read and its [setRollupVersion].
     */
    fun findRollupVersion(
        userId: Long,
        forUpdate: Boolean = false,
    ): Int? =
        jdbcClient
            .sql("SELECT rollup_version FROM athlete_analytics_state WHERE user_id = :userId" + if (forUpdate) " FOR UPDATE" else "")
            .param("userId", userId)
            .query { rs, _ -> rs.getInt("rollup_version") }
            .optional()
            .orElse(null)

    /** Records that [userId]'s rollups are now current at [version], inserting their state row if they have none. */
    fun setRollupVersion(
        userId: Long,
        version: Int = ROLLUP_VERSION,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO athlete_analytics_state (user_id, rollup_version) VALUES (:userId, :version)
                ON CONFLICT (user_id) DO UPDATE SET rollup_version = EXCLUDED.rollup_version
                """.trimIndent(),
            ).param("userId", userId)
            .param("version", version)
            .update()
    }

    /**
     * Records [userId]'s rollups as current, unless a state row already exists: then a concurrent writer either set it
     * current, or a time-zone change marked it stale, and that must stand.
     */
    fun insertRollupVersionIfAbsent(userId: Long) {
        jdbcClient
            .sql(
                "INSERT INTO athlete_analytics_state (user_id, rollup_version) VALUES (:userId, :version) ON CONFLICT (user_id) DO NOTHING",
            ).param("userId", userId)
            .param("version", ROLLUP_VERSION)
            .update()
    }

    /**
     * Athletes whose rollups are stale, in id order, after [afterUserId]: every athlete's, or only those of
     * [onlyUserIds]. Stale means a state row below [ROLLUP_VERSION], or no state row at all while the athlete has
     * Activities: those arrived without a writer (inserted by SQL, ADR-0014), so no rollup of theirs was ever built. An
     * athlete with neither has nothing to build; their first Activity write builds their rollups before it commits.
     */
    fun findStaleRollups(
        afterUserId: Long,
        limit: Int,
        onlyUserIds: Collection<Long>? = null,
    ): List<Long> {
        if (onlyUserIds != null && onlyUserIds.isEmpty()) return emptyList()
        val userFilter = if (onlyUserIds == null) "" else "AND u.id IN (:userIds)"
        return jdbcClient
            .sql(
                """
                SELECT u.id FROM app_user u LEFT JOIN athlete_analytics_state s ON s.user_id = u.id
                WHERE u.id > :afterId $userFilter
                  AND (
                      s.rollup_version < :version
                      OR (s.user_id IS NULL AND EXISTS (SELECT 1 FROM activity a WHERE a.user_id = u.id))
                  )
                ORDER BY u.id
                LIMIT :limit
                """.trimIndent(),
            ).param("version", ROLLUP_VERSION)
            .param("afterId", afterUserId)
            .param("limit", limit)
            .apply { if (onlyUserIds != null) param("userIds", onlyUserIds.distinct()) }
            .query { rs, _ -> rs.getLong("id") }
            .list()
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

    /**
     * Takes the shared side of [userId]'s scoring lock until the transaction ends, and returns their **Athlete Time
     * Zone** as committed now. A writer that scores a Training Load calls it before reading their Thresholds, so a
     * Threshold or zone change ([requestRecompute]) either commits first, and is what the writer reads, or waits for
     * the writer to commit, and its recompute request then covers the writer's Activity. Writers never block each
     * other. Taken after the Personal Record and Activity row locks (ADR-0006), and never held while waiting for those.
     */
    fun lockScoringInputs(userId: Long): ZoneId {
        advisoryLock("pg_advisory_xact_lock_shared", userId)
        val timeZone =
            jdbcClient
                .sql("SELECT time_zone FROM app_user WHERE id = :userId")
                .param("userId", userId)
                .query { rs, _ -> rs.getString("time_zone") }
                .optional()
                .orElse(null)
        return AthleteTimeZone.zoneOf(timeZone)
    }

    private fun advisoryLock(
        function: String,
        userId: Long,
    ) {
        jdbcClient
            .sql("SELECT $function(hashtextextended(:key, 0))")
            .param("key", "$SCORING_LOCK_NAMESPACE:$userId")
            // The lock functions return void: run the query for its effect and discard the row.
            .query { _, _ -> }
            .list()
    }

    private companion object {
        /** Prefixes the scoring lock's key, distinct from every other advisory lock's prefix. */
        const val SCORING_LOCK_NAMESPACE = "training_load_scoring"

        /** Prefixes the rollup lock's key (ADR-0016). */
        const val ROLLUP_LOCK_NAMESPACE = "athlete_rollup"
    }
}
