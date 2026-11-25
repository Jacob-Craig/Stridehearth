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
        if (stored == null || stored.start > today) return FitnessSeries(today, null, emptyList(), emptyList())
        val start = maxOf(from ?: stored.start, stored.start)
        val end = minOf(to ?: today, today)
        val continued = continuation(userId, stored.endInclusive, maxOf(end, today))
        val current = if (today <= stored.endInclusive) fitnessRollup.findDays(userId, today, today).single() else continued.getValue(today)
        if (start > end) return FitnessSeries(today, current, emptyList(), emptyList())

        val days = fitnessRollup.findDays(userId, start, minOf(end, stored.endInclusive)) + continued.values.filter { it.day in start..end }
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

    /** The days after [lastStored] up to [until], computed from the inputs before them; empty when there are none. */
    private fun continuation(
        userId: Long,
        lastStored: LocalDate,
        until: LocalDate,
    ): Map<LocalDate, FitnessDay> {
        val first = lastStored.plusDays(1)
        if (first > until) return emptyMap()
        val zone = fitnessRollup.zoneOf(userId)
        val readFrom = first.minusDays(EffectiveVo2maxCalculator.LOOKBACK_DAYS)
        val history = fitnessRollup.marathonHistory(userId, zone, first, lastStored)
        return EffectiveVo2maxCalculator
            .series(
                fitnessRollup.findRuns(userId, zone, readFrom, lastStored),
                fitnessRollup.findCandidates(userId, zone, readFrom, lastStored),
                first,
                until,
            ).map(history::withShape)
            .associateBy { it.day }
    }
}
