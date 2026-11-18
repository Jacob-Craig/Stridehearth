package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.PmcCalculator
import com.jacobcraig.stridehearth.analytics.PmcDay
import com.jacobcraig.stridehearth.analytics.PmcSeed
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One day of an athlete's **Performance Management Chart** with its **Daily Load**. */
data class PmcSeriesDay(
    val date: LocalDate,
    val load: DailyLoad,
    val metrics: PmcDay,
)

/** An athlete's chart: the zone its days are counted in, their first scored day, and one entry per day. */
data class PmcSeries(
    val timeZone: ZoneId,
    val firstDay: LocalDate?,
    val days: List<PmcSeriesDay>,
)

/**
 * Reads an athlete's **Performance Management Chart** (#36). The stored rows end at their last day with an Activity;
 * days after it, up to today, are continued here with zero loads, so no daily job is needed for Fitness and Fatigue to
 * decay to today.
 */
@Service
class PmcService(
    private val dailyLoadRollup: DailyLoadRollup,
    private val athleteTimeZone: AthleteTimeZone,
) {
    /**
     * [userId]'s chart for each day from [from] (default and floor: their first scored day) to [to] (default and cap:
     * [today] in their zone), ascending and dense. Empty without a scored Activity.
     */
    @Transactional(readOnly = true)
    fun series(
        userId: Long,
        from: LocalDate?,
        to: LocalDate?,
        today: LocalDate,
    ): PmcSeries {
        val zone = athleteTimeZone.of(userId)
        val (firstDay, lastStored) = dailyLoadRollup.findMetricsBounds(userId) ?: return PmcSeries(zone, null, emptyList())
        val start = maxOf(from ?: firstDay, firstDay)
        val end = minOf(to ?: today, today)
        if (start > end) return PmcSeries(zone, firstDay, emptyList())

        val loads = dailyLoadRollup.findDailyLoads(userId, start, end).associateBy { it.day }
        val metrics = dailyLoadRollup.findMetrics(userId, start, minOf(end, lastStored)).associate { it.day to it.metrics }.toMutableMap()
        if (end > lastStored) metrics += extend(userId, firstDay, lastStored, end)

        val days =
            generateSequence(start) { it.plusDays(1) }.takeWhile { it <= end }.map { date ->
                PmcSeriesDay(date, loads[date] ?: DailyLoad(date, 0.0, 0, 0), metrics.getValue(date))
            }
        return PmcSeries(zone, firstDay, days.toList())
    }

    /** The chart from the day after [lastStored] to [end], continuing the stored recurrence. */
    private fun extend(
        userId: Long,
        firstDay: LocalDate,
        lastStored: LocalDate,
        end: LocalDate,
    ): Map<LocalDate, PmcDay> {
        val seed = dailyLoadRollup.findMetrics(userId, lastStored, lastStored).single().metrics
        val next = lastStored.plusDays(1)
        val origin = next.minusDays(PmcCalculator.LOOKBACK_DAYS.toLong())
        val series =
            PmcCalculator.series(
                dailyLoadRollup.denseLoads(userId, origin, end),
                firstDay = ChronoUnit.DAYS.between(origin, firstDay).toInt(),
                from = PmcCalculator.LOOKBACK_DAYS,
                seed = PmcSeed(seed.ctl, seed.atl),
            )
        return series.withIndex().associate { (index, day) -> next.plusDays(index.toLong()) to day }
    }
}
