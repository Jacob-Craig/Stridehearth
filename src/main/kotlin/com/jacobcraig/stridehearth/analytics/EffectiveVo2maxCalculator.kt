package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.activities.BenchmarkDistance
import java.time.LocalDate

/** A Run with a **VO2max Estimate**: [raw] ml/kg/min over [movingSeconds] of Moving Time on athlete-local [day]. */
data class EstimatedRun(
    val activityId: Long,
    val day: LocalDate,
    val raw: Double,
    val movingSeconds: Int,
)

/** A Run that was essentially one [benchmarkDistance] **Best Effort** run in [elapsedSeconds], on athlete-local [day]. */
data class CalibrationCandidate(
    val activityId: Long,
    val day: LocalDate,
    val benchmarkDistance: BenchmarkDistance,
    val elapsedSeconds: Double,
) {
    /** The Daniels VDOT of the effort. */
    val vdot: Double get() = DanielsVdot.vdot(benchmarkDistance.distanceMeters, elapsedSeconds)
}

/** The Best Effort a **Correction Factor** came from. */
data class CorrectionSource(
    val activityId: Long,
    val benchmarkDistance: BenchmarkDistance,
    val vdot: Double,
)

/**
 * An athlete's **Effective VO2max** on [day] (null without an estimate in the window), the **Correction Factor** in
 * force that day and its [correctionSource] (null when it is 1.0 for want of a candidate).
 */
data class FitnessDay(
    val day: LocalDate,
    val effectiveVo2max: Double?,
    val correctionFactor: Double,
    val correctionSource: CorrectionSource?,
)

/**
 * An athlete's daily **Effective VO2max** (#42), pure: no Spring, no I/O.
 *
 * - U(D), the uncorrected value, is the mean of the **VO2max Estimates** of the Runs on the [WINDOW_DAYS] local days
 *   ending on D, weighted by Moving Time; null when there are none.
 * - The **Correction Factor** CF(D) calibrates against the athlete's best race: among the [CalibrationCandidate]s on
 *   the [CALIBRATION_DAYS] days ending on D whose own day has a U, the one with the highest VDOT (ties: earlier day,
 *   then lower id), CF = VDOT / U(its day), clamped to [[MIN_CORRECTION], [MAX_CORRECTION]]. Without one, CF = 1.
 *   Comparing against the 30-day value rather than the race's own estimate lets a race shorter than 20 minutes
 *   calibrate.
 * - Effective VO2max(D) = CF(D) x U(D).
 *
 * Every input looks backwards, so a day depends only on Runs on or before it.
 */
object EffectiveVo2maxCalculator {
    const val WINDOW_DAYS = 30L
    const val CALIBRATION_DAYS = 365L
    const val MIN_CORRECTION = 0.8
    const val MAX_CORRECTION = 1.2

    /** The least share of a Run's distance its Best Effort must cover for the Run to count as that race. */
    const val MIN_RACE_COVERAGE = 0.9

    /** The Benchmark Distances a race calibrates from. */
    val CALIBRATION_DISTANCES =
        listOf(BenchmarkDistance.FIVE_KILOMETERS, BenchmarkDistance.TEN_KILOMETERS, BenchmarkDistance.HALF_MARATHON)

    /** How many days before the first day of a series its inputs reach back. */
    const val LOOKBACK_DAYS = CALIBRATION_DAYS - 1 + WINDOW_DAYS - 1

    /**
     * The candidate a Run of [activityMeters] makes from its Best Effort times [efforts]: its longest effort at a
     * [CALIBRATION_DISTANCES] distance covering at least [MIN_RACE_COVERAGE] of the Run, or null when none does.
     */
    fun candidateOf(
        activityId: Long,
        day: LocalDate,
        activityMeters: Double,
        efforts: Map<BenchmarkDistance, Double>,
    ): CalibrationCandidate? =
        efforts
            .filterKeys { it in CALIBRATION_DISTANCES && it.distanceMeters >= MIN_RACE_COVERAGE * activityMeters }
            .maxByOrNull { (distance, _) -> distance.distanceMeters }
            ?.let { (distance, seconds) -> CalibrationCandidate(activityId, day, distance, seconds) }

    /** U([day]) over [runs]. */
    fun uncorrected(
        runs: List<EstimatedRun>,
        day: LocalDate,
    ): Double? = Window(runs).uncorrected(day)

    /** One [FitnessDay] per day from [from] to [to], inclusive, from [runs] and [candidates] (in any order). */
    fun series(
        runs: List<EstimatedRun>,
        candidates: List<CalibrationCandidate>,
        from: LocalDate,
        to: LocalDate,
    ): List<FitnessDay> {
        val window = Window(runs)
        val calibrations =
            candidates
                .mapNotNull { candidate -> window.uncorrected(candidate.day)?.let { candidate to it } }
                .sortedWith(compareBy({ it.first.day }, { it.first.activityId }))
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { it <= to }
            .map { day ->
                val best =
                    calibrations
                        .filter { (candidate, _) -> candidate.day <= day && candidate.day > day.minusDays(CALIBRATION_DAYS) }
                        .maxWithOrNull(BEST_CALIBRATION)
                val correction = best?.let { (candidate, u) -> (candidate.vdot / u).coerceIn(MIN_CORRECTION, MAX_CORRECTION) } ?: 1.0
                FitnessDay(
                    day = day,
                    effectiveVo2max = window.uncorrected(day)?.let { it * correction },
                    correctionFactor = correction,
                    correctionSource = best?.first?.let { CorrectionSource(it.activityId, it.benchmarkDistance, it.vdot) },
                )
            }.toList()
    }

    /** Highest VDOT first; of equal ones, the earlier day, then the lower id. */
    private val BEST_CALIBRATION: Comparator<Pair<CalibrationCandidate, Double>> =
        compareBy<Pair<CalibrationCandidate, Double>> { it.first.vdot }
            .thenByDescending { it.first.day }
            .thenByDescending { it.first.activityId }

    /** Per-day sums of the estimates, so U(D) costs [WINDOW_DAYS] lookups. */
    private class Window(
        runs: List<EstimatedRun>,
    ) {
        private val byDay: Map<LocalDate, Pair<Double, Double>> =
            runs.filter { it.movingSeconds > 0 }.groupBy { it.day }.mapValues { (_, list) ->
                list.sumOf { it.raw * it.movingSeconds } to list.sumOf { it.movingSeconds.toDouble() }
            }

        fun uncorrected(day: LocalDate): Double? {
            var weighted = 0.0
            var seconds = 0.0
            for (back in 0 until WINDOW_DAYS) {
                val (w, s) = byDay[day.minusDays(back)] ?: continue
                weighted += w
                seconds += s
            }
            return if (seconds > 0) weighted / seconds else null
        }
    }
}
