package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.AcclimationCalculator
import com.jacobcraig.stridehearth.analytics.AcclimationSeed
import com.jacobcraig.stridehearth.analytics.DayExposure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** An athlete's acclimation: [current] on their today (null before their first exposed day), and the days asked for. */
data class AcclimationSeries(
    val today: LocalDate,
    val current: AcclimationSeed?,
    val days: List<AcclimationRow>,
)

/**
 * Reads an athlete's **Heat Acclimation** and **Altitude Acclimation** (#43). The stored rows end at their last day
 * with an Activity; days after it, up to today, are continued here without exposure, so acclimation decays to today
 * without a daily job.
 */
@Service
class AcclimationService(
    private val acclimationRollup: AcclimationRollup,
) {
    /**
     * [userId]'s acclimation for each day from [from] (default and floor: their first exposed day) to [to] (default
     * and cap: [today]), ascending and dense, with its value on [today]. Empty before their first exposed day.
     *
     * Read from one snapshot, so a rollup committing between reads cannot remove a row a later read relies on.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun series(
        userId: Long,
        from: LocalDate?,
        to: LocalDate?,
        today: LocalDate,
    ): AcclimationSeries {
        val latest = acclimationRollup.findLatestOnOrBefore(userId, today) ?: return AcclimationSeries(today, null, emptyList())
        val firstDay = checkNotNull(acclimationRollup.findFirstDay(userId))
        val current = AcclimationRollup.decayedTo(latest, today)

        val start = maxOf(from ?: firstDay, firstDay)
        val end = minOf(to ?: today, today)
        if (start > end) return AcclimationSeries(today, current, emptyList())

        val stored = acclimationRollup.findDays(userId, start, end)
        val lastStored = acclimationRollup.findLatestOnOrBefore(userId, end)!!
        val extended =
            if (end <= lastStored.day) {
                emptyList()
            } else {
                val gap = generateSequence(lastStored.day.plusDays(1)) { it.plusDays(1) }.takeWhile { it <= end }.toList()
                AcclimationCalculator
                    .series(gap.map { NONE }, AcclimationSeed(lastStored.heatPct, lastStored.altitudePct))
                    .zip(
                        gap,
                    ) { day, date -> AcclimationRow(date, day.heatPct, day.altitudePct, heatExposed = false, altitudeExposed = false) }
                    .filter { it.day >= start }
            }
        return AcclimationSeries(today, current, stored + extended)
    }

    private companion object {
        val NONE = DayExposure(heat = false, altitude = false)
    }
}
