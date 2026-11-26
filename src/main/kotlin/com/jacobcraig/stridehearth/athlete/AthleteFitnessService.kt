package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.EffectiveVo2maxCalculator
import com.jacobcraig.stridehearth.analytics.FitnessDay
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** A Run's **VO2max Estimate** on its athlete-local [day], uncorrected ([raw]) and times that day's Correction Factor. */
data class RunVo2max(
    val activityId: Long,
    val day: LocalDate,
    val raw: Double,
    val vo2maxEstimate: Double,
)

/**
 * An athlete's **Effective VO2max**: [current] on their [today] (null before their first VO2max Estimate), the days
 * asked for, and the Runs with an estimate among them.
 */
data class FitnessSeries(
    val today: LocalDate,
    val current: FitnessDay?,
    val days: List<FitnessDay>,
    val runs: List<RunVo2max>,
)

/**
 * Reads an athlete's **Effective VO2max** (#42) and **Marathon Shape** (#44). The stored rows ([FitnessRollup]) end at their last day with an
 * Activity; days after it, up to today, are computed here with the same [EffectiveVo2maxCalculator] from the estimates
 * and Best Efforts before them, so the value runs out 30 days after the last estimate without a daily job. Later
 * athlete-level predictions read the series through this one place.
 */
@Service
class AthleteFitnessService(
    private val fitnessRollup: FitnessRollup,
) {
    /**
     * [userId]'s Effective VO2max for each day from [from] (default and floor: their first day with an estimate) to
     * [to] (default and cap: [today]), ascending and dense, with its value on [today]. Empty before their first
     * estimate.
     *
     * Read from one snapshot, so a rollup committing between reads cannot remove a row a later read relies on.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun series(
        userId: Long,
        from: LocalDate?,
        to: LocalDate?,
        today: LocalDate,
    ): FitnessSeries {
        val stored = fitnessRollup.findStoredRange(userId)
        // No rows are stored when every estimate was dated after the rollup's today (a device clock ahead): the
        // series then starts at the first estimate once that day has arrived, as PmcService does.
        val first = stored?.start ?: fitnessRollup.firstEstimateDay(userId, fitnessRollup.zoneOf(userId))
        if (first == null || first > today) return FitnessSeries(today, null, emptyList(), emptyList())
        val lastStored = stored?.endInclusive ?: first.minusDays(1)
        val start = maxOf(from ?: first, first)
        val end = minOf(to ?: today, today)
        // Days after the stored rows are computed, but only the ones this response shows: the requested days and
        // today. Each day reads only its own lookback, so a long history awaiting its rollup rebuild is not replayed.
        val continued = continuation(userId, maxOf(lastStored.plusDays(1), start), end)
        val current =
            when {
                today <= lastStored -> fitnessRollup.findDays(userId, today, today).single()
                else -> continued[today] ?: continuation(userId, today, today).getValue(today)
            }
        if (start > end) return FitnessSeries(today, current, emptyList(), emptyList())

        val storedDays = if (start <= minOf(end, lastStored)) fitnessRollup.findDays(userId, start, minOf(end, lastStored)) else emptyList()
        val days = storedDays + continued.values
        val correctionOn = days.associate { it.day to it.correctionFactor }
        val runs =
            fitnessRollup
                .findRuns(userId, fitnessRollup.zoneOf(userId), start, end)
                .mapNotNull { run -> correctionOn[run.day]?.let { RunVo2max(run.activityId, run.day, run.raw, run.raw * it) } }
                .sortedWith(compareBy({ it.day }, { it.activityId }))
        return FitnessSeries(today, current, days, runs)
    }

    /**
     * The **Correction Factor** in force on [userId]'s local [day], or null when no row covers it (before their first
     * estimate, or while their rollups wait for a rebuild).
     */
    @Transactional(readOnly = true)
    fun correctionFactorOn(
        userId: Long,
        day: LocalDate,
    ): Double? = fitnessRollup.findDays(userId, day, day).singleOrNull()?.correctionFactor

    /**
     * The days [first] to [until] that have no stored row, computed from the inputs up to [until]; empty when the
     * range is. Runs dated after the rollup's today are stored without rows (#64), so the inputs reach past the last
     * stored day; each day still reads only the inputs on or before it, and no further back than its lookback.
     */
    private fun continuation(
        userId: Long,
        first: LocalDate,
        until: LocalDate,
    ): Map<LocalDate, FitnessDay> {
        if (first > until) return emptyMap()
        val zone = fitnessRollup.zoneOf(userId)
        val readFrom = first.minusDays(EffectiveVo2maxCalculator.LOOKBACK_DAYS)
        val history = fitnessRollup.marathonHistory(userId, zone, first, until)
        return EffectiveVo2maxCalculator
            .series(
                fitnessRollup.findRuns(userId, zone, readFrom, until),
                fitnessRollup.findCandidates(userId, zone, readFrom, until),
                first,
                until,
            ).map(history::withShape)
            .associateBy { it.day }
    }
}
