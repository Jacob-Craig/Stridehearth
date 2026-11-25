package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.ActivityConditions
import com.jacobcraig.stridehearth.analytics.DecouplingUnavailableReason
import com.jacobcraig.stridehearth.analytics.RunMetrics
import com.jacobcraig.stridehearth.analytics.ThresholdDependentMetrics
import com.jacobcraig.stridehearth.analytics.TrainingLoadMethod
import com.jacobcraig.stridehearth.analytics.TrainingLoadResult
import com.jacobcraig.stridehearth.analytics.TrainingLoadUnavailableReason
import com.jacobcraig.stridehearth.analytics.Vo2maxResult
import com.jacobcraig.stridehearth.analytics.Vo2maxUnavailableReason
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Types
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Data access for `activity_derived_metrics`: one row per **Activity**, of every sport, holding its derived data
 * (the **Grade-Adjusted Pace** stream, for a Run its [RunMetrics] and **VO2max Estimate**, its **Training Load** and its
 * [ActivityConditions]) and the `algorithm_version` that computed it (ADR-0014). The row is the
 * ledger of what has been computed, so a column that does not apply to an Activity is NULL rather than the row
 * missing. Raw SQL through [JdbcClient], like `activity_details`: no JPA entity maps it.
 *
 * Written only by [DerivedDataRefresh]; a row is removed with its Activity by `ON DELETE CASCADE`.
 */
@Repository
class ActivityDerivedMetricsRepository(
    private val jdbcClient: JdbcClient,
) {
    /**
     * Stores [gapSpeed], [runMetrics], [thresholdDependent] (the **Training Load** and **VO2max Estimate**) and
     * [conditions] for [activityId] as computed by [version], replacing whatever is stored.
     */
    fun upsert(
        activityId: Long,
        version: Short,
        gapSpeed: Array<Float?>?,
        runMetrics: RunMetrics? = null,
        thresholdDependent: ThresholdDependentMetrics? = null,
        conditions: ActivityConditions? = null,
    ) {
        jdbcClient
            .sql(
                """
                INSERT INTO activity_derived_metrics (activity_id, algorithm_version, gap_speed, moving_seconds, ngp_mps,
                    efficiency_factor, aerobic_decoupling_pct, decoupling_unavailable_reason, training_load,
                    training_load_method, training_load_unavailable_reason, device_temperature_c, ambient_temperature_c,
                    mean_altitude_m, vo2max_raw, vo2max_unavailable_reason, computed_at)
                VALUES (:activityId, :version, :gapSpeed::real[], :movingSeconds, :ngpMps, :efficiencyFactor,
                    :aerobicDecouplingPct, :reason, :trainingLoad, :trainingLoadMethod, :trainingLoadReason,
                    :deviceTemperatureC, :ambientTemperatureC, :meanAltitudeM, :vo2maxRaw, :vo2maxReason, now())
                ON CONFLICT (activity_id) DO UPDATE
                SET algorithm_version = EXCLUDED.algorithm_version,
                    gap_speed = EXCLUDED.gap_speed,
                    moving_seconds = EXCLUDED.moving_seconds,
                    ngp_mps = EXCLUDED.ngp_mps,
                    efficiency_factor = EXCLUDED.efficiency_factor,
                    aerobic_decoupling_pct = EXCLUDED.aerobic_decoupling_pct,
                    decoupling_unavailable_reason = EXCLUDED.decoupling_unavailable_reason,
                    training_load = EXCLUDED.training_load,
                    training_load_method = EXCLUDED.training_load_method,
                    training_load_unavailable_reason = EXCLUDED.training_load_unavailable_reason,
                    device_temperature_c = EXCLUDED.device_temperature_c,
                    ambient_temperature_c = EXCLUDED.ambient_temperature_c,
                    mean_altitude_m = EXCLUDED.mean_altitude_m,
                    vo2max_raw = EXCLUDED.vo2max_raw,
                    vo2max_unavailable_reason = EXCLUDED.vo2max_unavailable_reason,
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
            .thresholdDependentParams(thresholdDependent)
            .param("deviceTemperatureC", conditions?.deviceTemperatureC, Types.REAL)
            .param("ambientTemperatureC", conditions?.ambientTemperatureC, Types.REAL)
            .param("meanAltitudeM", conditions?.meanAltitudeM, Types.REAL)
            .update()
    }

    /**
     * Replaces only the Threshold-dependent derived data (the **Training Load** and **VO2max Estimate**) of
     * [activityId]'s row if [version] computed it, as a recompute after a Threshold or time-zone change does; returns
     * whether it replaced one. The other derived data and the row's version
     * are left as they are: an Activity without a row, or with a row of another version, is left to the version
     * Backfill or Recompute, which computes it in full.
     */
    fun updateThresholdDependent(
        activityId: Long,
        version: Short,
        metrics: ThresholdDependentMetrics,
    ): Boolean =
        jdbcClient
            .sql(
                """
                UPDATE activity_derived_metrics
                SET training_load = :trainingLoad,
                    training_load_method = :trainingLoadMethod,
                    training_load_unavailable_reason = :trainingLoadReason,
                    vo2max_raw = :vo2maxRaw,
                    vo2max_unavailable_reason = :vo2maxReason,
                    computed_at = now()
                WHERE activity_id = :activityId AND algorithm_version = :version
                """.trimIndent(),
            ).param("activityId", activityId)
            .param("version", version)
            .thresholdDependentParams(metrics)
            .update() > 0

    private fun JdbcClient.StatementSpec.thresholdDependentParams(metrics: ThresholdDependentMetrics?): JdbcClient.StatementSpec =
        trainingLoadParams(metrics?.trainingLoad)
            .param("vo2maxRaw", (metrics?.vo2max as? Vo2maxResult.Estimate)?.raw, Types.DOUBLE)
            .param("vo2maxReason", (metrics?.vo2max as? Vo2maxResult.Unavailable)?.reason?.name, Types.VARCHAR)

    private fun JdbcClient.StatementSpec.trainingLoadParams(trainingLoad: TrainingLoadResult?): JdbcClient.StatementSpec {
        val scored = trainingLoad as? TrainingLoadResult.Scored
        val unavailable = trainingLoad as? TrainingLoadResult.Unavailable
        return param("trainingLoad", scored?.value, Types.DOUBLE)
            .param("trainingLoadMethod", scored?.method?.name, Types.VARCHAR)
            .param("trainingLoadReason", unavailable?.reason?.name, Types.VARCHAR)
    }

    /**
     * The stored **Training Load** of [activityId], or null when it has no row or the row holds neither a load nor a
     * reason (written by a version before Training Load). A method or reason a later version no longer has reads as
     * null, so the row still reads until the recompute worker replaces it.
     */
    fun findTrainingLoad(activityId: Long): TrainingLoadResult? =
        jdbcClient
            .sql(
                """
                SELECT training_load, training_load_method, training_load_unavailable_reason
                FROM activity_derived_metrics WHERE activity_id = :activityId
                """.trimIndent(),
            ).param("activityId", activityId)
            .query { rs, _ ->
                val value = rs.getObject("training_load") as Double?
                val method =
                    rs.getString("training_load_method")?.let { stored ->
                        TrainingLoadMethod.entries.firstOrNull {
                            it.name ==
                                stored
                        }
                    }
                val reason =
                    rs.getString("training_load_unavailable_reason")?.let { stored ->
                        TrainingLoadUnavailableReason.entries.firstOrNull { it.name == stored }
                    }
                when {
                    value != null && method != null -> TrainingLoadResult.Scored(value, method)
                    reason != null -> TrainingLoadResult.Unavailable(reason)
                    else -> null
                }
            }.optional()
            .orElse(null)

    /**
     * The stored uncorrected **VO2max Estimate** of [activityId] or why it has none, or null when it has no row, is not
     * a Run, or the row holds neither (written by a version before VO2max). A reason a later version no longer has
     * reads as null. The estimate's segment count is not stored and reads as 0.
     */
    fun findVo2max(activityId: Long): Vo2maxResult? =
        jdbcClient
            .sql(
                """
                SELECT m.vo2max_raw, m.vo2max_unavailable_reason, m.moving_seconds
                FROM activity_derived_metrics m WHERE m.activity_id = :activityId
                """.trimIndent(),
            ).param("activityId", activityId)
            .query { rs, _ ->
                val raw = rs.getObject("vo2max_raw") as Double?
                val reason =
                    rs.getString("vo2max_unavailable_reason")?.let { stored ->
                        Vo2maxUnavailableReason.entries.firstOrNull { it.name == stored }
                    }
                when {
                    raw != null -> Vo2maxResult.Estimate(raw, segments = 0, movingSeconds = rs.getInt("moving_seconds"))
                    reason != null -> Vo2maxResult.Unavailable(reason)
                    else -> null
                }
            }.optional()
            .orElse(null)

    /**
     * The stored [ActivityConditions] of [activityId], or null when it has no row. A row an older version wrote holds
     * no conditions (every value null) until the recompute worker replaces it.
     */
    fun findConditions(activityId: Long): ActivityConditions? =
        jdbcClient
            .sql(
                """
                SELECT device_temperature_c, ambient_temperature_c, mean_altitude_m
                FROM activity_derived_metrics WHERE activity_id = :activityId
                """.trimIndent(),
            ).param("activityId", activityId)
            .query { rs, _ ->
                ActivityConditions(
                    deviceTemperatureC = (rs.getObject("device_temperature_c") as Number?)?.toDouble(),
                    ambientTemperatureC = (rs.getObject("ambient_temperature_c") as Number?)?.toDouble(),
                    meanAltitudeM = (rs.getObject("mean_altitude_m") as Number?)?.toDouble(),
                )
            }.optional()
            .orElse(null)

    /**
     * The stored [RunMetrics] of [activityId], or null when it has no row or the row holds none (not a Run). A stored
     * unavailable reason that [DecouplingUnavailableReason] no longer has is read as null, so a row an older version
     * wrote still reads until the recompute worker replaces it.
     */
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
                    // A reason a later version renamed or removed reads as none until the worker recomputes the row.
                    decouplingUnavailableReason =
                        rs.getString("decoupling_unavailable_reason")?.let { stored ->
                            DecouplingUnavailableReason.entries.firstOrNull { it.name == stored }
                        },
                )
            }.optional()
            .orElse(null)

    /**
     * What **Stamina** is integrated from for [activityId]: its start, its sample times and its stored Grade-Adjusted
     * speed. Null unless [userId] owns it, it is a Run, and its GAP stream is stored parallel to its Activity Details.
     */
    fun findStaminaInputs(
        activityId: Long,
        userId: Long,
    ): StaminaInputs? =
        jdbcClient
            .sql(
                """
                SELECT a.started_at, d.t, m.gap_speed
                FROM activity a
                JOIN activity_details d ON d.activity_id = a.id
                JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE a.id = :activityId AND a.user_id = :userId AND a.sport = 'RUN'
                  AND cardinality(m.gap_speed) = cardinality(d.t)
                """.trimIndent(),
            ).param("activityId", activityId)
            .param("userId", userId)
            .query { rs, _ ->
                StaminaInputs(
                    startedAt = rs.getTimestamp("started_at").toInstant(),
                    t = (rs.getArray("t").array as Array<*>).map { (it as Number).toInt() }.toIntArray(),
                    gapSpeed = (rs.getArray("gap_speed").array as Array<*>).map { (it as? Number)?.toFloat() }.toTypedArray(),
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

    /**
     * The next [limit] of [userId]'s Activities after [afterActivityId], in id order, whose start falls on or after the
     * athlete-local date [from] in [zone] (every one of them when [from] is null): the Activities a Training Load
     * recompute request covers.
     */
    fun findTrainingLoadRecomputePage(
        userId: Long,
        from: LocalDate?,
        zone: ZoneId,
        afterActivityId: Long,
        limit: Int,
    ): List<Long> {
        val dateFilter = if (from == null) "" else "AND (a.started_at AT TIME ZONE :zone)::date >= :from"
        return jdbcClient
            .sql(
                """
                SELECT a.id FROM activity a
                WHERE a.user_id = :userId AND a.id > :afterId $dateFilter
                ORDER BY a.id ASC
                LIMIT :limit
                """.trimIndent(),
            ).param("userId", userId)
            .param("afterId", afterActivityId)
            .param("limit", limit)
            .apply {
                if (from != null) {
                    // PostgreSQL knows UTC by that name, not as java.time's "Z".
                    param("zone", if (zone == ZoneOffset.UTC) "UTC" else zone.id)
                    param("from", from, Types.DATE)
                }
            }.query { rs, _ -> rs.getLong("id") }
            .list()
    }

    /** A PostgreSQL array literal such as `{1.5,NULL,2}`, bound as one text parameter and cast to `real[]`. */
    private fun Array<Float?>.toSqlArrayLiteral(): String = joinToString(",", "{", "}") { it?.toString() ?: "NULL" }
}
