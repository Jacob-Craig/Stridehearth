package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.DecouplingUnavailableReason
import com.jacobcraig.stridehearth.analytics.RunMetrics
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Types

/**
 * Data access for `activity_derived_metrics`: one row per **Activity**, of every sport, holding its derived data
 * (the **Grade-Adjusted Pace** stream and, for a Run, its [RunMetrics]) and the `algorithm_version` that computed it (ADR-0014). The row is the
 * ledger of what has been computed, so a column that does not apply to an Activity is NULL rather than the row
 * missing. Raw SQL through [JdbcClient], like `activity_details`: no JPA entity maps it.
 *
 * Written only by [DerivedDataRefresh]; a row is removed with its Activity by `ON DELETE CASCADE`.
 */
@Repository
class ActivityDerivedMetricsRepository(
    private val jdbcClient: JdbcClient,
) {
    /** Stores [gapSpeed] and [runMetrics] for [activityId] as computed by [version], replacing whatever is stored. */
    fun upsert(
        activityId: Long,
        version: Short,
        gapSpeed: Array<Float?>?,
        runMetrics: RunMetrics? = null,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO activity_derived_metrics (activity_id, algorithm_version, gap_speed, moving_seconds, ngp_mps,
                    efficiency_factor, aerobic_decoupling_pct, decoupling_unavailable_reason, computed_at)
                VALUES (:activityId, :version, :gapSpeed::real[], :movingSeconds, :ngpMps, :efficiencyFactor,
                    :aerobicDecouplingPct, :reason, now())
                ON CONFLICT (activity_id) DO UPDATE
                SET algorithm_version = EXCLUDED.algorithm_version,
                    gap_speed = EXCLUDED.gap_speed,
                    moving_seconds = EXCLUDED.moving_seconds,
                    ngp_mps = EXCLUDED.ngp_mps,
                    efficiency_factor = EXCLUDED.efficiency_factor,
                    aerobic_decoupling_pct = EXCLUDED.aerobic_decoupling_pct,
                    decoupling_unavailable_reason = EXCLUDED.decoupling_unavailable_reason,
                    computed_at = EXCLUDED.computed_at
                """.trimIndent(),
            ).param("activityId", activityId)
            .param("version", version)
            .param("gapSpeed", gapSpeed?.toSqlArrayLiteral())
            .param("movingSeconds", runMetrics?.movingSeconds, Types.INTEGER)
            .param("ngpMps", runMetrics?.ngpMps, Types.DOUBLE)
            .param("efficiencyFactor", runMetrics?.efficiencyFactor, Types.DOUBLE)
            .param("aerobicDecouplingPct", runMetrics?.aerobicDecouplingPct, Types.DOUBLE)
            .param("reason", runMetrics?.decouplingUnavailableReason?.name, Types.VARCHAR)
            .update()
    }

    /** The stored [RunMetrics] of [activityId], or null when it has no row or the row holds none (not a Run). */
    fun findRunMetrics(activityId: Long): RunMetrics? =
        jdbcClient
            .sql(
                """
                SELECT moving_seconds, ngp_mps, efficiency_factor, aerobic_decoupling_pct, decoupling_unavailable_reason
                FROM activity_derived_metrics
                WHERE activity_id = :activityId AND moving_seconds IS NOT NULL
                """.trimIndent(),
            ).param("activityId", activityId)
            .query { rs, _ ->
                RunMetrics(
                    movingSeconds = rs.getInt("moving_seconds"),
                    ngpMps = rs.getObject("ngp_mps") as Double?,
                    efficiencyFactor = rs.getObject("efficiency_factor") as Double?,
                    aerobicDecouplingPct = rs.getObject("aerobic_decoupling_pct") as Double?,
                    decouplingUnavailableReason =
                        rs.getString("decoupling_unavailable_reason")?.let { DecouplingUnavailableReason.valueOf(it) },
                )
            }.optional()
            .orElse(null)

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
