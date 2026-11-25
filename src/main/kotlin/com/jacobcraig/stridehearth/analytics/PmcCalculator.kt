package com.jacobcraig.stridehearth.analytics

import kotlin.math.exp
import kotlin.math.sqrt

/** **Fitness** and **Fatigue** on the day before a series starts; both 0 before an athlete's first scored day. */
data class PmcSeed(
    val ctl: Double,
    val atl: Double,
) {
    companion object {
        val ZERO = PmcSeed(0.0, 0.0)
    }
}

/**
 * One day of the **Performance Management Chart**: Fitness ([ctl]), Fatigue ([atl]), Form ([tsb]), the uncoupled
 * **Acute:Chronic Workload Ratio** ([acwr]), **Monotony** and **Strain**. The last three are null while they are
 * withheld or undefined.
 */
data class PmcDay(
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val acwr: Double?,
    val monotony: Double?,
    val strain: Double?,
)

/**
 * The **Performance Management Chart** recurrence over a dense series of **Daily Loads** (#36). Pure: no Spring, no
 * I/O. Every value is unrounded.
 *
 * - Fitness (CTL) and Fatigue (ATL) are exponentially weighted moving averages with time constants of 42 and 7 days:
 *   `X_d = X_(d-1) + alpha (L_d - X_(d-1))`, `alpha = 1 - e^(-1/tau)`, seeded at 0 the day before the first scored day.
 * - Form (TSB) is yesterday's Fitness minus yesterday's Fatigue: readiness before the day's training.
 * - ACWR, uncoupled: the mean load of the last 7 days over the mean load of the 28 days before them. Null until the
 *   first scored day is 34 days back (a partial chronic window would inflate it), and when the chronic mean is 0.
 * - Foster's Monotony: the last 7 days' mean over their sample standard deviation (n - 1); Strain: their sum times
 *   Monotony. Both null until the first scored day is 6 days back, and when the standard deviation is 0 (at most
 *   1e-9, which absorbs only floating-point rounding noise).
 */
object PmcCalculator {
    /** Fitness's smoothing constant, `1 - e^(-1/42)`. */
    val CTL_ALPHA = 1 - exp(-1.0 / 42)

    /** Fatigue's smoothing constant, `1 - e^(-1/7)`. */
    val ATL_ALPHA = 1 - exp(-1.0 / 7)

    /** Days in the acute window of ACWR, and in the week of Monotony and Strain. */
    const val ACUTE_DAYS = 7

    /** Days in the chronic window of ACWR, which ends the day before the acute window starts. */
    const val CHRONIC_DAYS = 28

    /** How many days before a computed day its windows reach back: 34, the oldest day of the chronic window. */
    const val LOOKBACK_DAYS = ACUTE_DAYS + CHRONIC_DAYS - 1

    /**
     * A standard deviation at most this is taken as 0: a week of equal loads whose float sum or mean left rounding
     * noise (around 1e-14 for realistic loads). Absolute, so any real variation, however small, still gives Foster's
     * finite Monotony.
     */
    private const val ZERO_SD_EPSILON = 1e-9

    /**
     * The chart for each day from [from] to the last of [loads], where `loads[i]` is day `i`'s summed load (0 for a day
     * without Activities). [firstDay] is the index of the athlete's first scored day (it may be negative, before the
     * array starts), and [seed] holds Fitness and Fatigue on day `from - 1`. Windows reaching before index 0 read
     * zeros, so the array must start no later than [LOOKBACK_DAYS] days before [from], or at the first scored day.
     */
    fun series(
        loads: DoubleArray,
        firstDay: Int = 0,
        from: Int = firstDay,
        seed: PmcSeed = PmcSeed.ZERO,
    ): List<PmcDay> {
        fun load(day: Int) = if (day < 0) 0.0 else loads[day]
        var ctl = seed.ctl
        var atl = seed.atl
        return (from until loads.size).map { day ->
            val tsb = ctl - atl
            ctl += CTL_ALPHA * (loads[day] - ctl)
            atl += ATL_ALPHA * (loads[day] - atl)
            val daysSinceFirst = day - firstDay
            val week = DoubleArray(ACUTE_DAYS) { load(day - ACUTE_DAYS + 1 + it) }
            val monotony = if (daysSinceFirst >= ACUTE_DAYS - 1) monotony(week) else null
            PmcDay(
                ctl = ctl,
                atl = atl,
                tsb = tsb,
                acwr = if (daysSinceFirst >= LOOKBACK_DAYS) acwr(week, day, ::load) else null,
                monotony = monotony,
                strain = monotony?.let { it * week.sum() },
            )
        }
    }

    private fun acwr(
        week: DoubleArray,
        day: Int,
        load: (Int) -> Double,
    ): Double? {
        val chronicSum = (day - LOOKBACK_DAYS..day - ACUTE_DAYS).sumOf(load)
        if (chronicSum == 0.0) return null
        return (week.sum() / ACUTE_DAYS) / (chronicSum / CHRONIC_DAYS)
    }

    private fun monotony(week: DoubleArray): Double? {
        val mean = week.average()
        val sd = sqrt(week.sumOf { (it - mean) * (it - mean) } / (week.size - 1))
        if (sd <= ZERO_SD_EPSILON) return null
        return mean / sd
    }
}
