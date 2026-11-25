package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.AcclimationCalculator
import com.jacobcraig.stridehearth.analytics.AcclimationSeed
import com.jacobcraig.stridehearth.analytics.DayExposure
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

/** One stored athlete-local day of **Heat Acclimation** and **Altitude Acclimation**. */
data class AcclimationRow(
    val day: LocalDate,
    val heatPct: Double,
    val altitudePct: Double,
    val heatExposed: Boolean,
    val altitudeExposed: Boolean,
)

/**
 * The **Heat Acclimation** and **Altitude Acclimation** rollup (#43): `athlete_acclimation_daily`, one row per
 * athlete-local day (in the **Athlete Time Zone**) from the athlete's first exposed day to their last day with an
 * Activity, but never past their today. A day is exposed when one of its Activities lasted at least
 * [AcclimationCalculator.MIN_EXPOSURE_SECONDS] and was above [AcclimationCalculator.HEAT_EXPOSURE_C] **Ambient
 * Temperature** (heat) or [AcclimationCalculator.ALTITUDE_EXPOSURE_M] **Mean Altitude** (altitude).
 *
 * It rebuilds from the affected day forward, continuing from the latest stored day before it, so rows before the
 * affected day are never rewritten. [AcclimationService] continues the rows to today with pure decay.
 *
 * Its caller serialises it per athlete ([AthleteRollups], ADR-0016); it takes no lock of its own.
 */
@Component
class AcclimationRollup(
    private val jdbcClient: JdbcClient,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {
    /** Rebuilds [userId]'s acclimation from the local day containing [from] onwards, or everything when it is null. */
    fun recomputeFrom(
        userId: Long,
        from: Instant?,
    ) {
        val zone = zoneOf(userId)
        val fromDay = from?.atZone(zone)?.toLocalDate()
        val seedRow = fromDay?.let { latestBefore(userId, it) }
        val lastDay = lastActivityDay(userId, zone)?.let { minOf(it, LocalDate.now(clock.withZone(zone))) }

        if (seedRow == null || lastDay == null) {
            jdbcClient.sql("DELETE FROM athlete_acclimation_daily WHERE user_id = :u").param("u", userId).update()
            if (lastDay == null) return
            val exposures = exposures(userId, zone, from = null, to = lastDay)
            val firstExposed = exposures.entries.firstOrNull { (_, it) -> it.heat || it.altitude }?.key ?: return
            insert(userId, firstExposed, AcclimationSeed.ZERO, exposures, lastDay)
            return
        }

        // Continue from the latest stored day before the affected one; rows past the last Activity's day go too.
        val start = seedRow.day.plusDays(1)
        jdbcClient
            .sql("DELETE FROM athlete_acclimation_daily WHERE user_id = :u AND (day >= :start OR day > :lastDay)")
            .param("u", userId)
            .param("start", start, Types.DATE)
            .param("lastDay", lastDay, Types.DATE)
            .update()
        if (start > lastDay) return
        val seed = AcclimationSeed(seedRow.heatPct, seedRow.altitudePct)
        insert(userId, start, seed, exposures(userId, zone, start, lastDay), lastDay)
    }

    /** [userId]'s stored days in [from, to], ascending. */
    fun findDays(
        userId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<AcclimationRow> =
        jdbcClient
            .sql("SELECT * FROM athlete_acclimation_daily WHERE user_id = :u AND day BETWEEN :from AND :to ORDER BY day")
            .param("u", userId)
            .param("from", from, Types.DATE)
            .param("to", to, Types.DATE)
            .query { rs, _ -> rs.toRow() }
            .list()

    /** [userId]'s latest stored day on or before [day], or null when there is none. */
    fun findLatestOnOrBefore(
        userId: Long,
        day: LocalDate,
    ): AcclimationRow? = latestBefore(userId, day.plusDays(1))

    /** [userId]'s stored day [day], or null when it has no row. */
    fun findDay(
        userId: Long,
        day: LocalDate,
    ): AcclimationRow? = findDays(userId, day, day).firstOrNull()

    private fun insert(
        userId: Long,
        start: LocalDate,
        seed: AcclimationSeed,
        exposures: Map<LocalDate, DayExposure>,
        lastDay: LocalDate,
    ) {
        val dense =
            generateSequence(start) { it.plusDays(1) }
                .takeWhile { it <= lastDay }
                .map { exposures[it] ?: NONE }
                .toList()
        val series = AcclimationCalculator.series(dense, seed)
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO athlete_acclimation_daily (user_id, day, heat_pct, altitude_pct, heat_exposed, altitude_exposed)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            series.withIndex().toList(),
            BATCH_SIZE,
        ) { ps, (index, day) ->
            ps.setLong(1, userId)
            ps.setDate(2, Date.valueOf(start.plusDays(index.toLong())))
            ps.setDouble(3, day.heatPct)
            ps.setDouble(4, day.altitudePct)
            ps.setBoolean(5, day.heatExposed)
            ps.setBoolean(6, day.altitudeExposed)
        }
    }

    /** Whether each local day in [from, to] (from the first when [from] is null) with an Activity was heat- or altitude-exposed, ascending. */
    private fun exposures(
        userId: Long,
        zone: ZoneId,
        from: LocalDate?,
        to: LocalDate,
    ): Map<LocalDate, DayExposure> {
        val fromFilter = if (from == null) "" else "AND (a.started_at AT TIME ZONE :zone)::date >= :from"
        return jdbcClient
            .sql(
                """
                SELECT (a.started_at AT TIME ZONE :zone)::date AS day,
                       COALESCE(bool_or(m.ambient_temperature_c > :heatC AND a.time_taken_seconds >= :minSeconds), false) AS heat,
                       COALESCE(bool_or(m.mean_altitude_m > :altitudeM AND a.time_taken_seconds >= :minSeconds), false) AS altitude
                FROM activity a LEFT JOIN activity_derived_metrics m ON m.activity_id = a.id
                WHERE a.user_id = :u AND (a.started_at AT TIME ZONE :zone)::date <= :to $fromFilter
                GROUP BY 1 ORDER BY 1
                """.trimIndent(),
            ).param("u", userId)
            .param("zone", sqlZone(zone))
            .param("heatC", AcclimationCalculator.HEAT_EXPOSURE_C)
            .param("altitudeM", AcclimationCalculator.ALTITUDE_EXPOSURE_M)
            .param("minSeconds", AcclimationCalculator.MIN_EXPOSURE_SECONDS)
            .param("to", to, Types.DATE)
            .apply { if (from != null) param("from", from, Types.DATE) }
            .query { rs, _ ->
                rs.getObject("day", LocalDate::class.java) to DayExposure(rs.getBoolean("heat"), rs.getBoolean("altitude"))
            }.list()
            .toMap(sortedMapOf())
    }

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

    private fun latestBefore(
        userId: Long,
        day: LocalDate,
    ): AcclimationRow? =
        jdbcClient
            .sql("SELECT * FROM athlete_acclimation_daily WHERE user_id = :u AND day < :day ORDER BY day DESC LIMIT 1")
            .param("u", userId)
            .param("day", day, Types.DATE)
            .query { rs, _ -> rs.toRow() }
            .optional()
            .orElse(null)

    private fun zoneOf(userId: Long): ZoneId =
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

    private fun java.sql.ResultSet.toRow() =
        AcclimationRow(
            day = getObject("day", LocalDate::class.java),
            heatPct = getDouble("heat_pct"),
            altitudePct = getDouble("altitude_pct"),
            heatExposed = getBoolean("heat_exposed"),
            altitudeExposed = getBoolean("altitude_exposed"),
        )

    companion object {
        private const val BATCH_SIZE = 500
        private val NONE = DayExposure(heat = false, altitude = false)

        /** [row] continued without exposure to [day], which must not be before it. */
        fun decayedTo(
            row: AcclimationRow,
            day: LocalDate,
        ): AcclimationSeed {
            val gap = ChronoUnit.DAYS.between(row.day, day).toInt()
            val seed = AcclimationSeed(row.heatPct, row.altitudePct)
            if (gap <= 0) return seed
            val last = AcclimationCalculator.series(List(gap) { NONE }, seed).last()
            return AcclimationSeed(last.heatPct, last.altitudePct)
        }
    }
}
