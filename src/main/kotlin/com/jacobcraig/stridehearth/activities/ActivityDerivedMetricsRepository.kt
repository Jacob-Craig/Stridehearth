package com.jacobcraig.stridehearth.activities

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Data access for `activity_derived_metrics`: one row per **Activity**, of every sport, holding its derived data
 * (the **Grade-Adjusted Pace** stream) and the `algorithm_version` that computed it (ADR-0014). The row is the
 * ledger of what has been computed, so a column that does not apply to an Activity is NULL rather than the row
 * missing. Raw SQL through [JdbcClient], like `activity_details`: no JPA entity maps it.
 *
 * Written only by [DerivedDataRefresh]; a row is removed with its Activity by `ON DELETE CASCADE`.
 */
@Repository
class ActivityDerivedMetricsRepository(
    private val jdbcClient: JdbcClient,
) {
    /** Stores [gapSpeed] for [activityId] as computed by [version], replacing whatever is stored. */
    fun upsert(
        activityId: Long,
        version: Short,
        gapSpeed: Array<Float?>?,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO activity_derived_metrics (activity_id, algorithm_version, gap_speed, computed_at)
                VALUES (:activityId, :version, :gapSpeed::real[], now())
                ON CONFLICT (activity_id) DO UPDATE
                SET algorithm_version = EXCLUDED.algorithm_version,
                    gap_speed = EXCLUDED.gap_speed,
                    computed_at = EXCLUDED.computed_at
                """.trimIndent(),
            ).param("activityId", activityId)
            .param("version", version)
            .param("gapSpeed", gapSpeed?.toSqlArrayLiteral())
            .update()
    }

    /** Which of [activityIds] still exist as Activities. */
    fun findExistingActivityIds(activityIds: Collection<Long>): Set<Long> =
        activityIds.distinct().chunkedForIdLookup().flatMapTo(mutableSetOf()) { chunk ->
            jdbcClient
                .sql("SELECT id FROM activity WHERE id IN (:ids)")
                .param("ids", chunk)
                .query { rs, _ -> rs.getLong("id") }
                .list()
        }

    /**
     * The next [limit] Activities after [afterActivityId], in id order, whose derived data must be computed: every
     * Activity, of any sport and with or without Activity Details, that has no row or a row older than [version].
     * With [onlyUserIds], only those athletes' Activities.
     */
    fun findRecomputePage(
        afterActivityId: Long,
        limit: Int,
        version: Short,
        onlyUserIds: Collection<Long>? = null,
    ): List<Long> {
        if (onlyUserIds != null && onlyUserIds.isEmpty()) return emptyList()
        val userFilter = if (onlyUserIds == null) "" else "AND a.user_id IN (:userIds)"
        return jdbcClient
            .sql(
                """
                SELECT a.id
                FROM activity a
                LEFT JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE a.id > :afterId
                  AND (m.activity_id IS NULL OR m.algorithm_version < :version)
                  $userFilter
                ORDER BY a.id ASC
                LIMIT :limit
                """.trimIndent(),
            ).param("afterId", afterActivityId)
            .param("version", version)
            .param("limit", limit)
            .apply { if (onlyUserIds != null) param("userIds", onlyUserIds.distinct()) }
            .query { rs, _ -> rs.getLong("id") }
            .list()
    }

    /** A PostgreSQL array literal such as `{1.5,NULL,2}`, bound as one text parameter and cast to `real[]`. */
    private fun Array<Float?>.toSqlArrayLiteral(): String = joinToString(",", "{", "}") { it?.toString() ?: "NULL" }
}
