package com.jacobcraig.stridehearth.analytics

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A Run's recorded distance in [km] on its athlete-local [day]. */
data class RunDistance(
    val day: LocalDate,
    val km: Double,
)

/**
 * An athlete's **Marathon Shape** on one day: [pct] in [0, 100] from the **Volume Score** ([volumeScore], their
 * [weeklyKm] against the weekly target) and the **Long-Run Score** ([longRunScore]), both in [0, 1].
 */
data class MarathonShape(
    val pct: Double,
    val volumeScore: Double,
    val longRunScore: Double,
    val weeklyKm: Double,
)

/**
 * **Marathon Shape** (#44, after Runalyze), pure: no Spring, no I/O. With V the day's **Effective VO2max**:
 *
 * - The weekly target is V^1.135 km and the target long run max(15, 12·ln(V/4) − 6) km.
 * - **Volume Score**: the Runs' recorded distance over the [VOLUME_DAYS] days ending on the day, per week of the days
 *   since the athlete's first Run (at least [MIN_VOLUME_DAYS], at most [VOLUME_DAYS]), against the weekly target,
 *   capped at 1.
 * - **Long-Run Score**: each Run of at least [MIN_LONG_RUN_KM] km on the [LONG_RUN_DAYS] days ending on the day scores
 *   ((km − 13) / (target − 13))², capped at [MAX_LONG_RUN_TERM], weighted by 1 − age/70; their sum against
 *   [LONG_RUN_REFERENCE_POINTS], one target long run a week for 10 weeks, capped at 1.
 * - Marathon Shape = 100·(⅔·Volume Score + ⅓·Long-Run Score); none without an Effective VO2max.
 *
 * Every input looks backwards, so a day depends only on Runs on or before it.
 */
object MarathonShapeCalculator {
    const val VOLUME_DAYS = 182L
    const val LONG_RUN_DAYS = 70L
    const val MIN_LONG_RUN_KM = 13.0
    const val MIN_TARGET_LONG_RUN_KM = 15.0
    const val MIN_VOLUME_DAYS = 28L
    const val LONG_RUN_REFERENCE_POINTS = 5.5
    const val MAX_LONG_RUN_TERM = 2.0
    const val VOLUME_WEIGHT = 2.0 / 3.0

    /** How many days before a day its inputs reach back. */
    const val LOOKBACK_DAYS = VOLUME_DAYS - 1

    private const val WEEKLY_TARGET_EXPONENT = 1.135

    /** The weekly running distance, in km, an athlete of Effective VO2max [vo2max] targets. */
    fun targetWeeklyKm(vo2max: Double): Double = vo2max.pow(WEEKLY_TARGET_EXPONENT)

    /** The long run, in km, an athlete of Effective VO2max [vo2max] targets. */
    fun targetLongRunKm(vo2max: Double): Double = max(MIN_TARGET_LONG_RUN_KM, 12 * ln(vo2max / 4) - 6)

    /**
     * An athlete's Runs ([runs], in any order, covering at least the [LOOKBACK_DAYS] days before the first day asked
     * about) and the day of their first Run ever ([firstRunDay]), from which each day's Marathon Shape is read.
     */
    class History(
        runs: List<RunDistance>,
        private val firstRunDay: LocalDate?,
    ) {
        private val origin: LocalDate? = runs.minOfOrNull { it.day }

        /** prefix[i] = the distance of the Runs on the days before origin + i. */
        private val prefix: DoubleArray =
            if (origin == null) {
                DoubleArray(1)
            } else {
                val totals = DoubleArray(index(runs.maxOf { it.day }) + 1)
                runs.forEach { totals[index(it.day)] += it.km }
                DoubleArray(totals.size + 1).also { sums -> totals.forEachIndexed { i, km -> sums[i + 1] = sums[i] + km } }
            }

        private val longRuns: List<RunDistance> = runs.filter { it.km >= MIN_LONG_RUN_KM }.sortedBy { it.day }

        private fun index(day: LocalDate): Int = ChronoUnit.DAYS.between(origin, day).toInt()

        /** The distance of the Runs on the days [from, to]. */
        private fun distance(
            from: LocalDate,
            to: LocalDate,
        ): Double {
            if (origin == null) return 0.0

            fun cumulative(day: LocalDate): Double = prefix[(index(day) + 1).coerceIn(0, prefix.size - 1)]
            return cumulative(to) - cumulative(from.minusDays(1))
        }

        /** The weekly distance, in km, on [day]. */
        fun weeklyKm(day: LocalDate): Double {
            val since = firstRunDay?.let { ChronoUnit.DAYS.between(it, day) + 1 } ?: 0
            val days = since.coerceIn(MIN_VOLUME_DAYS, VOLUME_DAYS)
            return distance(day.minusDays(VOLUME_DAYS - 1), day) / (days / 7.0)
        }

        /** The long-run points on [day] against the target long run of Effective VO2max [vo2max]. */
        fun longRunPoints(
            day: LocalDate,
            vo2max: Double,
        ): Double {
            val target = targetLongRunKm(vo2max)
            val earliest = day.minusDays(LONG_RUN_DAYS - 1)
            var points = 0.0
            var i = firstOnOrAfter(earliest)
            while (i < longRuns.size && longRuns[i].day <= day) {
                val run = longRuns[i++]
                val age = ChronoUnit.DAYS.between(run.day, day)
                val term = ((run.km - MIN_LONG_RUN_KM) / (target - MIN_LONG_RUN_KM)).pow(2)
                points += (1 - age.toDouble() / LONG_RUN_DAYS) * min(MAX_LONG_RUN_TERM, term)
            }
            return points
        }

        /** The index of the first long run on or after [day] (binary search, so a day costs only its own window). */
        private fun firstOnOrAfter(day: LocalDate): Int {
            var low = 0
            var high = longRuns.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (longRuns[mid].day < day) low = mid + 1 else high = mid
            }
            return low
        }

        /** [day] with its Marathon Shape, from its Effective VO2max. */
        fun withShape(day: FitnessDay): FitnessDay = day.copy(marathonShape = shapeOn(day.day, day.effectiveVo2max))

        /** The Marathon Shape on [day] with Effective VO2max [vo2max], or null without one. */
        fun shapeOn(
            day: LocalDate,
            vo2max: Double?,
        ): MarathonShape? {
            if (vo2max == null) return null
            val weeklyKm = weeklyKm(day)
            val volumeScore = min(1.0, weeklyKm / targetWeeklyKm(vo2max))
            val longRunScore = min(1.0, longRunPoints(day, vo2max) / LONG_RUN_REFERENCE_POINTS)
            return MarathonShape(
                pct = 100 * (VOLUME_WEIGHT * volumeScore + (1 - VOLUME_WEIGHT) * longRunScore),
                volumeScore = volumeScore,
                longRunScore = longRunScore,
                weeklyKm = weeklyKm,
            )
        }
    }
}
