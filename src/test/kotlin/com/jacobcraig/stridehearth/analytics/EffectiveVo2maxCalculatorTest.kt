package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.activities.BenchmarkDistance
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Hand-computed Effective VO2max: a Moving-Time-weighted 30-day mean times a Correction Factor clamped to 0.8-1.2. */
class EffectiveVo2maxCalculatorTest {
    private val day0: LocalDate = LocalDate.of(2026, 1, 1)

    private fun day(n: Long): LocalDate = day0.plusDays(n)

    private fun run(
        id: Long,
        dayIndex: Long,
        raw: Double,
        seconds: Int = 3600,
    ) = EstimatedRun(id, day(dayIndex), raw, seconds)

    /** A 5K raced in 19:57, VDOT 49.95. */
    private fun fiveK(
        id: Long,
        dayIndex: Long,
        seconds: Double = 1197.0,
    ) = CalibrationCandidate(id, day(dayIndex), BenchmarkDistance.FIVE_KILOMETERS, seconds)

    private fun on(
        dayIndex: Long,
        runs: List<EstimatedRun>,
        candidates: List<CalibrationCandidate> = emptyList(),
    ): FitnessDay = EffectiveVo2maxCalculator.series(runs, candidates, day(dayIndex), day(dayIndex)).single()

    @Nested
    inner class `the 30-day mean` {
        @Test
        fun `a 20-minute run at 50 and a 60-minute run at 54 average to 53`() {
            // (50 x 1200 + 54 x 3600) / 4800 = 53.0
            val runs = listOf(run(1, 0, 50.0, 1200), run(2, 3, 54.0, 3600))

            val result = on(3, runs)

            assertEquals(53.0, assertNotNull(result.effectiveVo2max), 1e-9)
            assertEquals(1.0, result.correctionFactor)
            assertNull(result.correctionSource)
        }

        @Test
        fun `a run counts for 30 days and then drops out`() {
            val runs = listOf(run(1, 0, 50.0))

            assertEquals(50.0, on(29, runs).effectiveVo2max)
            assertNull(on(30, runs).effectiveVo2max)
        }

        @Test
        fun `a later run does not change an earlier day`() {
            assertEquals(50.0, on(1, listOf(run(1, 0, 50.0), run(2, 2, 60.0))).effectiveVo2max)
        }

        @Test
        fun `the series is dense from its first to its last day`() {
            val series = EffectiveVo2maxCalculator.series(listOf(run(1, 0, 50.0)), emptyList(), day(-2), day(2))

            assertEquals((-2L..2L).map(::day), series.map { it.day })
            assertEquals(listOf(null, null, 50.0, 50.0, 50.0), series.map { it.effectiveVo2max })
        }
    }

    @Nested
    inner class `the Correction Factor` {
        @Test
        fun `a 19 57 5K with no estimate of its own calibrates against the 30-day value on its day`() {
            // U(day 5) = 45; VDOT 49.952 / 45 = 1.1100; E = 45 x 1.1100 = 49.952.
            val result = on(5, listOf(run(1, 0, 45.0)), listOf(fiveK(9, 5)))

            assertEquals(1.1100, result.correctionFactor, 1e-4)
            assertEquals(49.95, assertNotNull(result.effectiveVo2max), 0.01)
            val source = assertNotNull(result.correctionSource)
            assertEquals(9L, source.activityId)
            assertEquals(BenchmarkDistance.FIVE_KILOMETERS, source.benchmarkDistance)
            assertEquals(49.95, source.vdot, 0.01)
        }

        @Test
        fun `a later day keeps the calibration and applies it to the then 30-day value`() {
            // CF = 49.952 / 45 = 1.1100 from day 5; on day 10 U = (45 + 50) / 2 = 47.5, E = 52.73.
            val result = on(10, listOf(run(1, 0, 45.0), run(2, 10, 50.0)), listOf(fiveK(9, 5)))

            assertEquals(1.1100, result.correctionFactor, 1e-4)
            assertEquals(52.73, assertNotNull(result.effectiveVo2max), 0.01)
        }

        @Test
        fun `is clamped to 1,2`() {
            // 49.952 / 40 = 1.249
            assertEquals(1.2, on(0, listOf(run(1, 0, 40.0)), listOf(fiveK(9, 0))).correctionFactor)
        }

        @Test
        fun `is clamped to 0,8`() {
            // 49.952 / 65 = 0.768
            assertEquals(0.8, on(0, listOf(run(1, 0, 65.0)), listOf(fiveK(9, 0))).correctionFactor)
        }

        @Test
        fun `a candidate counts for 365 days and is ignored once it is 365 days old`() {
            val runs = listOf(run(1, 0, 45.0))
            val candidates = listOf(fiveK(9, 0))

            assertEquals(1.1100, on(364, runs, candidates).correctionFactor, 1e-4)
            val aYearOn = on(365, runs, candidates)
            assertEquals(1.0, aYearOn.correctionFactor)
            assertNull(aYearOn.correctionSource)
        }

        @Test
        fun `a candidate whose VDOT is not a number does not calibrate`() {
            val runs = listOf(run(1, 0, 50.0))

            val result = on(10, runs, listOf(fiveK(1, 5, seconds = Double.NaN), fiveK(2, 6)))

            assertEquals(2, assertNotNull(result.correctionSource).activityId)
        }

        @Test
        fun `a candidate on a day without a 30-day value does not calibrate`() {
            assertEquals(1.0, on(40, listOf(run(1, 0, 45.0)), listOf(fiveK(9, 40))).correctionFactor)
        }

        @Test
        fun `no candidate gives 1`() {
            assertEquals(1.0, on(0, listOf(run(1, 0, 45.0))).correctionFactor)
        }

        @Test
        fun `the candidate with the highest VDOT wins, and of equal ones the earlier`() {
            // 20:30 (VDOT 48.4) on day 1 and 19:57 on days 2 and 3: day 2's 19:57 calibrates.
            val result = on(3, listOf(run(1, 0, 45.0)), listOf(fiveK(7, 1, 1230.0), fiveK(9, 3), fiveK(8, 2)))

            assertEquals(8L, assertNotNull(result.correctionSource).activityId)
        }
    }

    @Nested
    inner class `which Runs are candidates` {
        @Test
        fun `a 5,2 km run with a 5K Best Effort was that 5K`() {
            val candidate = EffectiveVo2maxCalculator.candidateOf(1, day0, 5200.0, mapOf(BenchmarkDistance.FIVE_KILOMETERS to 1197.0))

            assertEquals(CalibrationCandidate(1, day0, BenchmarkDistance.FIVE_KILOMETERS, 1197.0), candidate)
        }

        @Test
        fun `a 10 km run is not its 5K Best Effort`() {
            assertNull(EffectiveVo2maxCalculator.candidateOf(1, day0, 10000.0, mapOf(BenchmarkDistance.FIVE_KILOMETERS to 1197.0)))
        }

        @Test
        fun `a 10,5 km run is its longest covering effort, the 10K`() {
            val efforts =
                mapOf(
                    BenchmarkDistance.ONE_KILOMETER to 200.0,
                    BenchmarkDistance.FIVE_KILOMETERS to 1197.0,
                    BenchmarkDistance.TEN_KILOMETERS to 2481.0,
                )

            assertEquals(
                BenchmarkDistance.TEN_KILOMETERS,
                EffectiveVo2maxCalculator.candidateOf(1, day0, 10500.0, efforts)?.benchmarkDistance,
            )
        }

        @Test
        fun `a 1 km run is never a candidate`() {
            assertNull(EffectiveVo2maxCalculator.candidateOf(1, day0, 1000.0, mapOf(BenchmarkDistance.ONE_KILOMETER to 200.0)))
        }
    }
}
