package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #86: the Marathon Shape and Effective VO2max series stay identical to the straightforward per-day definitions while
 * scaling to a decade of daily Runs. The reference implementations below are the per-day scans the calculators used
 * before #86, kept here as the oracle.
 */
class SeriesScalingTest {
    private val day0: LocalDate = LocalDate.of(2016, 1, 1)
    private val tenYears = 3652L

    private fun day(n: Long): LocalDate = day0.plusDays(n)

    /** A seeded athlete over [days] days: most days a Run, some long, some races with tied VDOTs and same-day pairs. */
    private data class Athlete(
        val distances: List<RunDistance>,
        val estimates: List<EstimatedRun>,
        val candidates: List<CalibrationCandidate>,
    )

    private fun athlete(
        seed: Int,
        days: Long,
        raceEvery: Int = 9,
    ): Athlete {
        val random = Random(seed)
        val distances = mutableListOf<RunDistance>()
        val estimates = mutableListOf<EstimatedRun>()
        val candidates = mutableListOf<CalibrationCandidate>()
        var id = 1L
        for (n in 0 until days) {
            if (random.nextInt(10) == 0) continue // rest day
            val runsToday = if (random.nextInt(8) == 0) 2 else 1
            repeat(runsToday) {
                val km = if (random.nextInt(6) == 0) 13.0 + random.nextInt(0, 25) else 3.0 + random.nextInt(0, 12)
                distances += RunDistance(day(n), km)
                // Coarse values so equal estimates and equal race times (ties) are common.
                estimates += EstimatedRun(id, day(n), 40.0 + random.nextInt(0, 16), 600 * random.nextInt(0, 10))
                if (random.nextInt(raceEvery) == 0) {
                    val distance = EffectiveVo2maxCalculator.CALIBRATION_DISTANCES.random(random)
                    val pace = 3.5 + random.nextInt(0, 6) * 0.25 // minutes per km
                    candidates += CalibrationCandidate(id, day(n), distance, distance.distanceMeters / 1000 * pace * 60)
                }
                id++
            }
        }
        // Shuffled: both calculators accept their inputs in any order.
        return Athlete(distances.shuffled(random), estimates.shuffled(random), candidates.shuffled(random))
    }

    private fun shapeSeries(
        history: (List<RunDistance>, LocalDate?) -> (LocalDate, Double) -> Double,
        athlete: Athlete,
        days: Long,
    ): List<Double> {
        val points = history(athlete.distances, athlete.distances.minOfOrNull { it.day })
        return (0 until days).map { n -> points(day(n), 45.0 + n % 20) }
    }

    @Test
    fun `the long-run points match the per-day scan on every day`() {
        for (seed in 1..5) {
            val athlete = athlete(seed, days = 800)
            val actual =
                shapeSeries({ runs, first -> MarathonShapeCalculator.History(runs, first)::longRunPoints }, athlete, 830)
            val expected = shapeSeries({ runs, _ -> ReferenceLongRuns(runs)::longRunPoints }, athlete, 830)
            assertEquals(expected, actual, "seed $seed")
        }
    }

    @Test
    fun `the Effective VO2max series matches the per-day scan on every day`() {
        for (seed in 1..5) {
            val athlete = athlete(seed, days = 1200, raceEvery = 3)
            for ((from, to) in listOf(day(0) to day(1230), day(400) to day(900), day(700) to day(700))) {
                val actual = EffectiveVo2maxCalculator.series(athlete.estimates, athlete.candidates, from, to)
                val expected = referenceSeries(athlete.estimates, athlete.candidates, from, to)
                assertEquals(expected, actual, "seed $seed, $from..$to")
            }
        }
    }

    @Test
    fun `ten years of daily Runs rebuild both series well under a second`() {
        // Worst case: a Run every day, every one of them a race and every sixth a long run.
        val athlete = athlete(seed = 86, days = tenYears, raceEvery = 1)
        val to = day(tenYears - 1)

        fun rebuild() {
            val fitness = EffectiveVo2maxCalculator.series(athlete.estimates, athlete.candidates, day0, to)
            val history = MarathonShapeCalculator.History(athlete.distances, day0)
            fitness.forEach { history.withShape(it) }
        }
        rebuild() // warm up the JIT
        val started = System.nanoTime()
        rebuild()
        val millis = (System.nanoTime() - started) / 1_000_000
        println("SeriesScalingTest: ten-year rebuild took $millis ms")
        assertTrue(millis < 1000, "ten-year rebuild took $millis ms")
    }

    /** MarathonShapeCalculator.History.longRunPoints before #86. */
    private class ReferenceLongRuns(
        runs: List<RunDistance>,
    ) {
        private val longRuns =
            runs.filter { it.km >= MarathonShapeCalculator.MIN_LONG_RUN_KM }.sortedBy { it.day }

        fun longRunPoints(
            day: LocalDate,
            vo2max: Double,
        ): Double {
            val target = MarathonShapeCalculator.targetLongRunKm(vo2max)
            val earliest = day.minusDays(MarathonShapeCalculator.LONG_RUN_DAYS - 1)
            return longRuns
                .asSequence()
                .dropWhile { it.day < earliest }
                .takeWhile { it.day <= day }
                .sumOf { run ->
                    val age = ChronoUnit.DAYS.between(run.day, day)
                    val term =
                        ((run.km - MarathonShapeCalculator.MIN_LONG_RUN_KM) / (target - MarathonShapeCalculator.MIN_LONG_RUN_KM))
                            .pow(2)
                    (1 - age.toDouble() / MarathonShapeCalculator.LONG_RUN_DAYS) *
                        min(MarathonShapeCalculator.MAX_LONG_RUN_TERM, term)
                }
        }
    }

    /** EffectiveVo2maxCalculator.series before #86: every day scans every calibration. */
    private fun referenceSeries(
        runs: List<EstimatedRun>,
        candidates: List<CalibrationCandidate>,
        from: LocalDate,
        to: LocalDate,
    ): List<FitnessDay> {
        val best =
            compareBy<Pair<CalibrationCandidate, Double>> { it.first.vdot }
                .thenByDescending { it.first.day }
                .thenByDescending { it.first.activityId }
        val calibrations =
            candidates
                .mapNotNull { candidate -> EffectiveVo2maxCalculator.uncorrected(runs, candidate.day)?.let { candidate to it } }
                .sortedWith(compareBy({ it.first.day }, { it.first.activityId }))
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { it <= to }
            .map { day ->
                val chosen =
                    calibrations
                        .filter { (candidate, _) ->
                            candidate.day <= day && candidate.day > day.minusDays(EffectiveVo2maxCalculator.CALIBRATION_DAYS)
                        }.maxWithOrNull(best)
                val correction =
                    chosen?.let { (candidate, u) ->
                        (candidate.vdot / u).coerceIn(EffectiveVo2maxCalculator.MIN_CORRECTION, EffectiveVo2maxCalculator.MAX_CORRECTION)
                    } ?: 1.0
                FitnessDay(
                    day = day,
                    effectiveVo2max = EffectiveVo2maxCalculator.uncorrected(runs, day)?.let { it * correction },
                    correctionFactor = correction,
                    correctionSource = chosen?.first?.let { CorrectionSource(it.activityId, it.benchmarkDistance, it.vdot) },
                )
            }.toList()
    }
}
