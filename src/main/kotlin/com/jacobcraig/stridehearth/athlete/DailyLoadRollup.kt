package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.PmcCalculator
import com.jacobcraig.stridehearth.analytics.PmcDay
import com.jacobcraig.stridehearth.analytics.PmcSeed
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
import java.time.temporal.ChronoUnit

/** One athlete-local day's **Daily Load**: the summed Training Load of its Activities and how many had none. */
data class DailyLoad(
    val day: LocalDate,
    val trainingLoad: Double,
    val activityCount: Int,
    val unscoredActivityCount: Int,
)

/** One stored day of an athlete's **Performance Management Chart**. */
data class DailyMetrics(
    val day: LocalDate,
    val metrics: PmcDay,
)

/**
 * The **Daily Load** and **Performance Management Chart** rollup (#36): `athlete_daily_load` and
 * `athlete_daily_metrics`, rebuilt from an athlete's Activities and their Training Loads, from the earliest affected
 * day forward. Days are athlete-local, in the **Athlete Time Zone**. Rows before the affected day are never rewritten.
 *
 * Its caller serialises it per athlete ([AthleteRollups], ADR-0016); it takes no lock of its own.
 */
@Component
class DailyLoadRollup(
    private val jdbcClient: JdbcClient,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    /**
     * Rebuilds [userId]'s Daily Loads and chart from the local day containing [from] onwards, or everything when it
     * is null. The chart runs from the athlete's first scored day to their last day with an Activity, but never past
     * their today: one Activity dated years ahead would otherwise make every later write rewrite each day up to it.
     * [PmcService] continues the chart from the stored Daily Loads, so a future day's load counts once that day comes.
     * An athlete with no scored Activity up to today has none.
     */
    fun recomputeFrom(
        userId: Long,
        from: Instant?,
    ) {
        val zone = zoneOf(userId)
        val fromDay = from?.atZone(zone)?.toLocalDate()
        rebuildDailyLoads(userId, zone, fromDay)

        val bounds = scoredBounds(userId)
        if (bounds == null) {
            jdbcClient.sql("DELETE FROM athlete_daily_metrics WHERE user_id = :u").param("u", userId).update()
            return
        }
        val firstDay = bounds.first
        val lastDay = minOf(bounds.second, LocalDate.now(clock.withZone(zone)))
        if (firstDay > lastDay) {
            jdbcClient.sql("DELETE FROM athlete_daily_metrics WHERE user_id = :u").param("u", userId).update()
            return
        }
        // Continue from the latest stored day before the affected one, or from the first scored day.
        val seedRow = if (fromDay == null || fromDay <= firstDay) null else latestMetricsBefore(userId, fromDay, firstDay)
        val start = seedRow?.day?.plusDays(1) ?: firstDay
        val seed = seedRow?.let { PmcSeed(it.metrics.ctl, it.metrics.atl) } ?: PmcSeed.ZERO

        val origin = start.minusDays(PmcCalculator.LOOKBACK_DAYS.toLong())
        val loads = denseLoads(userId, origin, lastDay)
        val series =
            PmcCalculator.series(
                loads,
                firstDay = ChronoUnit.DAYS.between(origin, firstDay).toInt(),
                from = PmcCalculator.LOOKBACK_DAYS,
                seed = seed,
            )

        jdbcClient
            .sql("DELETE FROM athlete_daily_metrics WHERE user_id = :u AND (day >= :start OR day < :firstDay OR day > :lastDay)")
            .param("u", userId)
            .param("start", start, Types.DATE)
            .param("firstDay", firstDay, Types.DATE)
            .param("lastDay", lastDay, Types.DATE)
            .update()
        jdbcTemplate.batchUpdate(
            "INSERT INTO athlete_daily_metrics (user_id, day, ctl, atl, tsb, acwr, monotony, strain) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            series.withIndex().toList(),
            BATCH_SIZE,
        ) { ps, (index, day) ->
            ps.setLong(1, userId)
            ps.setDate(2, Date.valueOf(start.plusDays(index.toLong())))
            ps.setDouble(3, day.ctl)
            ps.setDouble(4, day.atl)
            ps.setDouble(5, day.tsb)
            ps.setObject(6, day.acwr, Types.DOUBLE)
            ps.setObject(7, day.monotony, Types.DOUBLE)
            ps.setObject(8, day.strain, Types.DOUBLE)
        }
    }

    /** [userId]'s Daily Loads on days in [from, to], ascending; days without Activities have no row. */
    fun findDailyLoads(
        userId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyLoad> =
        jdbcClient
            .sql(
                """
                SELECT day, training_load, activity_count, unscored_activity_count FROM athlete_daily_load
                WHERE user_id = :u AND day BETWEEN :from AND :to ORDER BY day
                """.trimIndent(),
            ).param("u", userId)
            .param("from", from, Types.DATE)
            .param("to", to, Types.DATE)
            .query { rs, _ ->
                DailyLoad(
                    day = rs.getObject("day", LocalDate::class.java),
                    trainingLoad = rs.getDouble("training_load"),
                    activityCount = rs.getInt("activity_count"),
                    unscoredActivityCount = rs.getInt("unscored_activity_count"),
                )
            }.list()

    /** [userId]'s stored chart on days in [from, to], ascending. */
    fun findMetrics(
        userId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DailyMetrics> =
        jdbcClient
            .sql("SELECT * FROM athlete_daily_metrics WHERE user_id = :u AND day BETWEEN :from AND :to ORDER BY day")
            .param("u", userId)
            .param("from", from, Types.DATE)
            .param("to", to, Types.DATE)
            .query { rs, _ -> rs.toDailyMetrics() }
            .list()

    /** The first and last day of [userId]'s stored chart, or null when they have none. */
    fun findMetricsBounds(userId: Long): Pair<LocalDate, LocalDate>? =
        jdbcClient
            .sql("SELECT min(day) AS first_day, max(day) AS last_day FROM athlete_daily_metrics WHERE user_id = :u")
            .param("u", userId)
            .query { rs, _ ->
                rs.getObject("first_day", LocalDate::class.java)?.let { it to rs.getObject("last_day", LocalDate::class.java) }
            }.list()
            .firstOrNull()

    /** The daily loads of [userId] from [origin] to [to] inclusive, one per day, 0 on days without Activities. */
    fun denseLoads(
        userId: Long,
        origin: LocalDate,
        to: LocalDate,
    ): DoubleArray {
        val loads = DoubleArray((ChronoUnit.DAYS.between(origin, to) + 1).toInt().coerceAtLeast(0))
        findDailyLoads(userId, origin, to).forEach { loads[ChronoUnit.DAYS.between(origin, it.day).toInt()] = it.trainingLoad }
        return loads
    }

    private fun zoneOf(userId: Long): ZoneId =
        AthleteTimeZone.zoneOf(
            jdbcClient
                .sql("SELECT time_zone FROM app_user WHERE id = :u")
                .param("u", userId)
                .query { rs, _ -> rs.getString("time_zone") }
                .optional()
                .orElse(null),
        )

    private fun rebuildDailyLoads(
        userId: Long,
        zone: ZoneId,
        fromDay: LocalDate?,
    ) {
        val dayFilter = if (fromDay == null) "" else "AND day >= :from"
        val activityFilter = if (fromDay == null) "" else "AND (a.started_at AT TIME ZONE :zone)::date >= :from"
        jdbcClient
            .sql("DELETE FROM athlete_daily_load WHERE user_id = :u $dayFilter")
            .param("u", userId)
            .apply { if (fromDay != null) param("from", fromDay, Types.DATE) }
            .update()
        jdbcClient
            .sql(
                """
                INSERT INTO athlete_daily_load (user_id, day, training_load, activity_count, unscored_activity_count)
                SELECT :u, (a.started_at AT TIME ZONE :zone)::date,
                       COALESCE(SUM(m.training_load), 0), COUNT(*), COUNT(*) FILTER (WHERE m.training_load IS NULL)
                FROM activity a LEFT JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE a.user_id = :u $activityFilter
                GROUP BY 2
                """.trimIndent(),
            ).param("u", userId)
            // PostgreSQL knows UTC by that name, not as java.time's "Z".
            .param("zone", if (zone == ZoneOffset.UTC) "UTC" else zone.id)
            .apply { if (fromDay != null) param("from", fromDay, Types.DATE) }
            .update()
    }

    /** The first day with a scored Activity and the last day with any Activity, or null without a scored Activity. */
    private fun scoredBounds(userId: Long): Pair<LocalDate, LocalDate>? =
        jdbcClient
            .sql(
                """
                SELECT min(day) FILTER (WHERE activity_count > unscored_activity_count) AS first_day, max(day) AS last_day
                FROM athlete_daily_load WHERE user_id = :u
                """.trimIndent(),
            ).param("u", userId)
            .query { rs, _ ->
                rs.getObject("first_day", LocalDate::class.java)?.let { it to rs.getObject("last_day", LocalDate::class.java) }
            }.list()
            .firstOrNull()

    private fun latestMetricsBefore(
        userId: Long,
        day: LocalDate,
        firstDay: LocalDate,
    ): DailyMetrics? =
        jdbcClient
            .sql(
                """
                SELECT * FROM athlete_daily_metrics WHERE user_id = :u AND day < :day AND day >= :firstDay
                ORDER BY day DESC LIMIT 1
                """.trimIndent(),
            ).param("u", userId)
            .param("day", day, Types.DATE)
            .param("firstDay", firstDay, Types.DATE)
            .query { rs, _ -> rs.toDailyMetrics() }
            .optional()
            .orElse(null)

    private fun java.sql.ResultSet.toDailyMetrics() =
        DailyMetrics(
            day = getObject("day", LocalDate::class.java),
            metrics =
                PmcDay(
                    ctl = getDouble("ctl"),
                    atl = getDouble("atl"),
                    tsb = getDouble("tsb"),
                    acwr = getObject("acwr") as Double?,
                    monotony = getObject("monotony") as Double?,
                    strain = getObject("strain") as Double?,
                ),
        )

    private companion object {
        const val BATCH_SIZE = 500
    }
}
