package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-computed **Marathon Shape** (#44). At Effective VO2max 50 the weekly target is 50^1.135 = 84.787 km and the
 * target long run 12·ln(50/4) − 6 = 24.309 km.
 */
class MarathonShapeCalculatorTest {
    private val today: LocalDate = LocalDate.of(2026, 7, 1)

    private fun daysAgo(n: Long): LocalDate = today.minusDays(n)

    private fun run(
        daysAgo: Long,
        km: Double,
    ) = RunDistance(daysAgo(daysAgo), km)

    private fun shape(
        runs: List<RunDistance>,
        vo2max: Double? = 50.0,
        firstRunDay: LocalDate? = runs.minOfOrNull { it.day },
    ): MarathonShape? = MarathonShapeCalculator.History(runs, firstRunDay).shapeOn(today, vo2max)

    @Nested
    inner class `the targets` {
        @Test
        fun `Effective VO2max 50 targets 84_79 km a week and a 24_31 km long run`() {
            assertEquals(84.787, MarathonShapeCalculator.targetWeeklyKm(50.0), 0.001)
            assertEquals(24.309, MarathonShapeCalculator.targetLongRunKm(50.0), 0.001)
        }

        @Test
        fun `the target long run is never under 15 km`() {
            // 12·ln(20/4) − 6 = 13.31 km, raised to 15.
            assertEquals(15.0, MarathonShapeCalculator.targetLongRunKm(20.0))
        }
    }

    @Nested
    inner class `worked examples` {
        @Test
        fun `a low-mileage athlete, 30 km a week for 26 weeks with no run of 13 km, scores 23_6 percent`() {
            // Three 10 km runs a week from 181 days ago: 780 km over 26 weeks.
            val runs = (0 until 26).flatMap { week -> listOf(0L, 2L, 4L).map { run(181 - 7L * week - it, 10.0) } }

            val result = assertNotNull(shape(runs))

            assertEquals(30.0, result.weeklyKm, 1e-9)
            // 30 / 84.787 = 0.3538; 100 · ⅔ · 0.3538 = 23.59.
            assertEquals(0.3538, result.volumeScore, 0.0001)
            assertEquals(0.0, result.longRunScore)
            assertEquals(23.59, result.pct, 0.01)
            assertTrue(result.pct < 50)
        }

        @Test
        fun `a consistent high-mileage athlete with a 25 km run every week for 10 weeks scores 100 percent`() {
            // 90 km a week for 26 weeks: seven runs of 90/7 km (under 13 km) a week, except the last 10 weeks, which
            // have a 25 km run and six of 65/6 km instead.
            val easyWeeks = (10 until 26).flatMap { week -> (0L until 7L).map { run(7L * week + it, 90.0 / 7) } }
            val longRunWeeks =
                (0 until 10).flatMap { week -> listOf(run(7L * week, 25.0)) + (1L..6L).map { run(7L * week + it, 65.0 / 6) } }
            val history = MarathonShapeCalculator.History(easyWeeks + longRunWeeks, daysAgo(181))

            // Each 25 km run scores ((25 − 13) / (24.309 − 13))² = 1.1260, at ages 0, 7, …, 63 weighted by
            // Σ(1 − age/70) = 5.5: 6.193 points, over the 5.5 needed.
            assertEquals(6.193, history.longRunPoints(today, 50.0), 0.001)
            val result = assertNotNull(history.shapeOn(today, 50.0))
            assertEquals(90.0, result.weeklyKm, 1e-9)
            assertEquals(1.0, result.volumeScore)
            assertEquals(1.0, result.longRunScore)
            assertEquals(100.0, result.pct, 1e-9)
        }
    }

    @Nested
    inner class `long runs` {
        @Test
        fun `a single run of the target length today scores one long run of the 5_5 needed`() {
            val result = assertNotNull(shape(listOf(run(0, MarathonShapeCalculator.targetLongRunKm(50.0)))))

            assertEquals(1 / 5.5, result.longRunScore, 1e-9)
        }

        @Test
        fun `a run under 13 km scores nothing`() {
            assertEquals(0.0, assertNotNull(shape(listOf(run(0, 12.9)))).longRunScore)
        }

        @Test
        fun `a 13 km run is a long run worth nothing, and a 60 km run is capped at twice a target long run`() {
            val history = MarathonShapeCalculator.History(listOf(run(0, 60.0)), daysAgo(0))

            // ((60 − 13) / 11.309)² = 17.27, capped at 2.
            assertEquals(2.0, history.longRunPoints(today, 50.0), 1e-9)
            assertEquals(0.0, MarathonShapeCalculator.History(listOf(run(0, 13.0)), daysAgo(0)).longRunPoints(today, 50.0))
        }

        @Test
        fun `a long run counts less with age and not at all after 70 days`() {
            val target = MarathonShapeCalculator.targetLongRunKm(50.0)

            // 35 days old: weight 1 − 35/70 = 0.5.
            assertEquals(0.5, MarathonShapeCalculator.History(listOf(run(35, target)), daysAgo(200)).longRunPoints(today, 50.0), 1e-9)
            assertEquals(0.0, MarathonShapeCalculator.History(listOf(run(70, target)), daysAgo(200)).longRunPoints(today, 50.0))
        }

        @Test
        fun `a run after the day does not count`() {
            val history = MarathonShapeCalculator.History(listOf(run(-1, 30.0)), daysAgo(200))

            assertEquals(0.0, assertNotNull(history.shapeOn(today, 50.0)).weeklyKm)
            assertEquals(0.0, history.longRunPoints(today, 50.0))
        }
    }

    @Nested
    inner class `weekly volume` {
        @Test
        fun `a new athlete with 10 days of history is averaged over 28 days`() {
            // 40 km in 10 days, over 4 weeks: 10 km a week.
            val result = assertNotNull(shape(listOf(run(9, 20.0), run(0, 20.0))))

            assertEquals(10.0, result.weeklyKm, 1e-9)
        }

        @Test
        fun `an athlete running for 10 weeks is averaged over those 70 days`() {
            // 700 km over 70 days: 70 km a week.
            val runs = (0L until 70L).map { run(it, 10.0) }

            assertEquals(70.0, assertNotNull(shape(runs)).weeklyKm, 1e-9)
        }

        @Test
        fun `only the 182 days ending on the day count, over 26 weeks`() {
            val runs = listOf(run(182, 500.0), run(181, 26.0))

            assertEquals(1.0, assertNotNull(shape(runs, firstRunDay = daysAgo(400))).weeklyKm, 1e-9)
        }

        @Test
        fun `volume over the target scores 1`() {
            val runs = (0L until 182L).map { run(it, 20.0) }

            assertEquals(1.0, assertNotNull(shape(runs)).volumeScore)
        }
    }

    @Test
    fun `without an Effective VO2max there is no Marathon Shape`() {
        assertNull(shape(listOf(run(0, 20.0)), vo2max = null))
    }

    @Test
    fun `the series from the history equals each day's shape`() {
        val runs = listOf(run(30, 25.0), run(10, 8.0), run(3, 15.0))
        val history = MarathonShapeCalculator.History(runs, daysAgo(30))

        val first = assertNotNull(history.shapeOn(daysAgo(5), 50.0))
        // 33 km in the 26 days from the first run, over 28 days; 48 km in 31 days.
        assertEquals(33.0 / 4, first.weeklyKm, 1e-9)
        assertEquals(48.0 / (31.0 / 7), assertNotNull(history.shapeOn(today, 50.0)).weeklyKm, 1e-9)
    }
}
