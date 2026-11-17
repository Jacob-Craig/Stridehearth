package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.GradeAdjustedPace
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Data access for `activity_derived_metrics`, one row per **Activity** holding its derived metrics (the
 * **Grade-Adjusted Pace** stream). Raw SQL through [JdbcClient], like `activity_details`: no JPA entity maps it.
 *
 * Written only by [DerivedMetricsRefresh]; a row is removed with its Activity by `ON DELETE CASCADE`.
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

    /**
     * Stores [gapSpeed] for [activityId] only when no row exists or the stored row is of an older version than
     * [version], and only while the Activity's sport still carries derived metrics. Returns whether it wrote.
     * A Backfill writes this way, so it never overwrites what a concurrent import or edit wrote.
     */
    fun upsertIfOlderVersion(
        activityId: Long,
        version: Short,
        gapSpeed: Array<Float?>?,
    ): Boolean =
        jdbcClient
            .sql(
                """
                INSERT INTO activity_derived_metrics (activity_id, algorithm_version, gap_speed, computed_at)
                SELECT a.id, :version, :gapSpeed::real[], now()
                FROM activity a
                WHERE a.id = :activityId AND a.sport IN (:sports)
                ON CONFLICT (activity_id) DO UPDATE
                SET algorithm_version = EXCLUDED.algorithm_version,
                    gap_speed = EXCLUDED.gap_speed,
                    computed_at = EXCLUDED.computed_at
                WHERE activity_derived_metrics.algorithm_version < EXCLUDED.algorithm_version
                """.trimIndent(),
            ).param("activityId", activityId)
            .param("version", version)
            .param("gapSpeed", gapSpeed?.toSqlArrayLiteral())
            .param("sports", GradeAdjustedPace.GAP_SPORTS.map { it.name })
            .update() > 0

    fun deleteByActivityId(activityId: Long) {
        jdbcClient
            .sql("DELETE FROM activity_derived_metrics WHERE activity_id = :activityId")
            .param("activityId", activityId)
            .update()
    }

    /**
     * The next [limit] Activities after [afterActivityId], in id order, whose derived metrics a Backfill must
     * compute: a sport that carries them, stored Activity Details, and no row or a row older than [version].
     */
    fun findNextBackfillPage(
        afterActivityId: Long,
        limit: Int,
        version: Short,
    ): List<Long> =
        jdbcClient
            .sql(
                """
                SELECT a.id
                FROM activity a
                JOIN activity_details d ON d.activity_id = a.id
                LEFT JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE a.id > :afterId
                  AND a.sport IN (:sports)
                  AND (m.activity_id IS NULL OR m.algorithm_version < :version)
                ORDER BY a.id ASC
                LIMIT :limit
                """.trimIndent(),
            ).param("afterId", afterActivityId)
            .param("sports", GradeAdjustedPace.GAP_SPORTS.map { it.name })
            .param("version", version)
            .param("limit", limit)
            .query { rs, _ -> rs.getLong("id") }
            .list()

    /** A PostgreSQL array literal such as `{1.5,NULL,2}`; floats print without locale or exponent surprises. */
    private fun Array<Float?>.toSqlArrayLiteral(): String = joinToString(",", "{", "}") { it?.toString() ?: "NULL" }
}
