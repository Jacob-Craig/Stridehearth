package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.activities.BenchmarkDistance
import com.jacobcraig.stridehearth.analytics.CalibrationCandidate
import com.jacobcraig.stridehearth.analytics.CorrectionSource
import com.jacobcraig.stridehearth.analytics.EffectiveVo2maxCalculator
import com.jacobcraig.stridehearth.analytics.EstimatedRun
import com.jacobcraig.stridehearth.analytics.FitnessDay
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Date
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The **Effective VO2max** rollup (#42): `athlete_daily_fitness`, one row per athlete-local day (in the **Athlete Time
 * Zone**) from the athlete's first day with a **VO2max Estimate** to their last day with an Activity, but never past
 * their today, computed by [EffectiveVo2maxCalculator] from the stored estimates and the **Best Efforts** that
 * calibrate the **Correction Factor**.
 *
 * Every input of a day looks backwards (30 days of estimates, 365 days of candidates), so it rebuilds from the affected
 * day forward, reading inputs from [EffectiveVo2maxCalculator.LOOKBACK_DAYS] before it, and never rewrites an earlier
 * row. [AthleteFitnessService] continues the rows to today in memory.
 *
 * Its caller serialises it per athlete ([AthleteRollups], ADR-0016); it takes no lock of its own.
 */
@Component
class FitnessRollup(
    private val jdbcClient: JdbcClient,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    /** Rebuilds [userId]'s Effective VO2max from the local day containing [from] onwards, or everything when it is null. */
    fun recomputeFrom(
        userId: Long,
        from: Instant?,
    ) {
        val zone = zoneOf(userId)
        val lastDay = lastActivityDay(userId, zone)?.let { minOf(it, LocalDate.now(clock.withZone(zone))) }
        val firstDay = lastDay?.let { firstEstimateDay(userId, zone) }
        // Rows before the affected day stay; the rebuild starts the day after the latest of them, so a gap since the
        // last stored day (no Activity in between) is filled too. Without one, it rebuilds everything.
        val keepBefore = from?.atZone(zone)?.toLocalDate()?.let { latestStoredBefore(userId, it)?.plusDays(1) }
        if (lastDay == null || firstDay == null || keepBefore == null) {
            jdbcClient.sql("DELETE FROM athlete_daily_fitness WHERE user_id = :u").param("u", userId).update()
        } else {
            jdbcClient
                .sql("DELETE FROM athlete_daily_fitness WHERE user_id = :u AND (day >= :start OR day > :lastDay)")
                .param("u", userId)
                .param("start", keepBefore, Types.DATE)
                .param("lastDay", lastDay, Types.DATE)
                .update()
        }
        if (lastDay == null || firstDay == null) return
        val start = maxOf(keepBefore ?: firstDay, firstDay)
        if (start > lastDay) return
        val readFrom = start.minusDays(EffectiveVo2maxCalculator.LOOKBACK_DAYS)
        val series =
            EffectiveVo2maxCalculator.series(
                findRuns(userId, zone, readFrom, lastDay),
                findCandidates(userId, zone, readFrom, lastDay),
                start,
                lastDay,
            )
        insert(userId, series)
    }

    private fun latestStoredBefore(
        userId: Long,
        day: LocalDate,
    ): LocalDate? =
        jdbcClient
            .sql("SELECT max(day) AS last_day FROM athlete_daily_fitness WHERE user_id = :u AND day < :day")
            .param("u", userId)
            .param("day", day, Types.DATE)
            .query { rs, _ -> rs.getObject("last_day", LocalDate::class.java) }
            .optional()
            .orElse(null)

    /** [userId]'s stored days in [from, to], ascending. */
    fun findDays(
        userId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<FitnessDay> =
        jdbcClient
            .sql("SELECT * FROM athlete_daily_fitness WHERE user_id = :u AND day BETWEEN :from AND :to ORDER BY day")
            .param("u", userId)
            .param("from", from, Types.DATE)
            .param("to", to, Types.DATE)
            .query { rs, _ ->
                val sourceId = rs.getObject("correction_activity_id") as Long?
                FitnessDay(
                    day = rs.getObject("day", LocalDate::class.java),
                    effectiveVo2max = rs.getObject("effective_vo2max") as Double?,
                    correctionFactor = rs.getDouble("correction_factor"),
                    correctionSource =
                        sourceId?.let {
                            CorrectionSource(
                                activityId = it,
                                benchmarkDistance = BenchmarkDistance.valueOf(rs.getString("correction_benchmark_distance")),
                                vdot = rs.getDouble("correction_vdot"),
                            )
                        },
                )
            }.list()

    /** [userId]'s first and last stored days, or null when they have none. */
    fun findStoredRange(userId: Long): ClosedRange<LocalDate>? =
        jdbcClient
            .sql("SELECT min(day) AS first_day, max(day) AS last_day FROM athlete_daily_fitness WHERE user_id = :u")
            .param("u", userId)
            .query { rs, _ -> rs.getObject("first_day", LocalDate::class.java) to rs.getObject("last_day", LocalDate::class.java) }
            .single()
            .let { (first, last) -> if (first == null || last == null) null else first..last }

    /** [userId]'s Runs with a VO2max Estimate on local days [from, to] in [zone]. */
    fun findRuns(
        userId: Long,
        zone: ZoneId,
        from: LocalDate,
        to: LocalDate,
    ): List<EstimatedRun> =
        jdbcClient
            .sql(
                """
                SELECT a.id, (a.started_at AT TIME ZONE :zone)::date AS day, m.vo2max_raw, m.moving_seconds
                FROM activity a JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE a.user_id = :u AND a.sport = 'RUN' AND m.vo2max_raw IS NOT NULL AND m.moving_seconds IS NOT NULL
                  AND (a.started_at AT TIME ZONE :zone)::date BETWEEN :from AND :to
                """.trimIndent(),
            ).param("u", userId)
            .param("zone", sqlZone(zone))
            .param("from", from, Types.DATE)
            .param("to", to, Types.DATE)
            .query { rs, _ ->
                EstimatedRun(
                    activityId = rs.getLong("id"),
                    day = rs.getObject("day", LocalDate::class.java),
                    raw = rs.getDouble("vo2max_raw"),
                    movingSeconds = rs.getInt("moving_seconds"),
                )
            }.list()

    /** [userId]'s Runs on local days [from, to] in [zone] that were essentially one calibrating Best Effort. */
    fun findCandidates(
        userId: Long,
        zone: ZoneId,
        from: LocalDate,
        to: LocalDate,
    ): List<CalibrationCandidate> {
        data class Effort(
            val activityId: Long,
            val day: LocalDate,
            val meters: Double,
            val distance: BenchmarkDistance,
            val seconds: Double,
        )
        val efforts =
            jdbcClient
                .sql(
                    """
                    SELECT a.id, (a.started_at AT TIME ZONE :zone)::date AS day, a.distance, e.benchmark_distance,
                           e.elapsed_time_seconds
                    FROM activity a JOIN activity_best_effort e ON e.activity_id = a.id
                    WHERE a.user_id = :u AND a.sport = 'RUN' AND e.benchmark_distance IN (:distances)
                      AND (a.started_at AT TIME ZONE :zone)::date BETWEEN :from AND :to
                    """.trimIndent(),
                ).param("u", userId)
                .param("zone", sqlZone(zone))
                .param("distances", EffectiveVo2maxCalculator.CALIBRATION_DISTANCES.map { it.name })
                .param("from", from, Types.DATE)
                .param("to", to, Types.DATE)
                .query { rs, _ ->
                    Effort(
                        activityId = rs.getLong("id"),
                        day = rs.getObject("day", LocalDate::class.java),
                        // Activity distance is stored in kilometres.
                        meters = rs.getDouble("distance") * 1000,
                        distance = BenchmarkDistance.valueOf(rs.getString("benchmark_distance")),
                        seconds = rs.getDouble("elapsed_time_seconds"),
                    )
                }.list()
        return efforts.groupBy { it.activityId }.mapNotNull { (activityId, list) ->
            val first = list.first()
            EffectiveVo2maxCalculator.candidateOf(activityId, first.day, first.meters, list.associate { it.distance to it.seconds })
        }
    }

    private fun insert(
        userId: Long,
        series: List<FitnessDay>,
    ) {
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO athlete_daily_fitness (user_id, day, effective_vo2max, correction_factor, correction_activity_id,
                correction_benchmark_distance, correction_vdot)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            series,
            BATCH_SIZE,
        ) { ps, day ->
            ps.setLong(1, userId)
            ps.setDate(2, Date.valueOf(day.day))
            ps.setObject(3, day.effectiveVo2max, Types.DOUBLE)
            ps.setDouble(4, day.correctionFactor)
            ps.setObject(5, day.correctionSource?.activityId, Types.BIGINT)
            ps.setObject(6, day.correctionSource?.benchmarkDistance?.name, Types.VARCHAR)
            ps.setObject(7, day.correctionSource?.vdot, Types.DOUBLE)
        }
    }

    private fun firstEstimateDay(
        userId: Long,
        zone: ZoneId,
    ): LocalDate? =
        jdbcClient
            .sql(
                """
                SELECT min((a.started_at AT TIME ZONE :zone)::date) AS first_day
                FROM activity a JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE a.user_id = :u AND a.sport = 'RUN' AND m.vo2max_raw IS NOT NULL
                """.trimIndent(),
            ).param("u", userId)
            .param("zone", sqlZone(zone))
            .query { rs, _ -> rs.getObject("first_day", LocalDate::class.java) }
            .optional()
            .orElse(null)

    private fun lastActivityDay(
        userId: Long,
        zone: ZoneId,
    ): LocalDate? =
        jdbcClient
            .sql("SELECT max((started_at AT TIME ZONE :zone)::date) AS last_day FROM activity WHERE user_id = :u")
            .param("u", userId)
            .param("zone", sqlZone(zone))
            .query { rs, _ -> rs.getObject("last_day", LocalDate::class.java) }
            .optional()
            .orElse(null)

    /** [userId]'s Athlete Time Zone. */
    fun zoneOf(userId: Long): ZoneId =
        AthleteTimeZone.zoneOf(
            jdbcClient
                .sql("SELECT time_zone FROM app_user WHERE id = :u")
                .param("u", userId)
                .query { rs, _ -> rs.getString("time_zone") }
                .optional()
                .orElse(null),
        )

    // PostgreSQL knows UTC by that name, not as java.time's "Z".
    private fun sqlZone(zone: ZoneId) = if (zone == ZoneOffset.UTC) "UTC" else zone.id

    private companion object {
        const val BATCH_SIZE = 500
    }
}
