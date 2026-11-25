package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand-computed **Race Prognosis** (#45). The base time is Daniels' VDOT time for the Effective VO2max; the endurance
 * penalty is 1 + 0.20 · (d − 5000) / 37195 · (1 − Marathon Shape / 100); Course Derating multiplies by the Heat
 * Derating of the race-day temperature and by 1 + 4 · climb / distance.
 */
class RacePrognosisTest {
    @Nested
    inner class `predictions match the VDOT tables when Marathon Shape is 100 percent` {
        @ParameterizedTest(name = "VDOT {0} runs {1} m in {2} s")
        @CsvSource(
            // Daniels' Running Formula, VDOT 50: 19:57, 41:21, 1:31:35, 3:10:49.
            "50, FIVE_K, 1197",
            "50, TEN_K, 2481",
            "50, HALF_MARATHON, 5495",
            "50, MARATHON, 11449",
            // VDOT 40: 24:08, 50:03, 1:50:59, 3:49:45.
            "40, FIVE_K, 1448",
            "40, TEN_K, 3003",
            "40, HALF_MARATHON, 6659",
            "40, MARATHON, 13785",
        )
        fun `within 0_2 percent of the table`(
            vdot: Double,
            distance: RaceDistance,
            tableSeconds: Double,
        ) {
            val prediction = RacePrognosis.predict(vdot, 100.0, distance)

            assertEquals(tableSeconds, prediction.predictedSeconds, tableSeconds * 0.002)
            assertEquals(1.0, prediction.enduranceFactor)
            assertEquals(1.0, prediction.courseFactor)
            assertEquals(prediction.vdotSeconds, prediction.predictedSeconds)
        }

        @Test
        fun `the four distances are 5K, 10K, the Half and the Marathon in order`() {
            assertEquals(listOf(5000.0, 10000.0, 21097.5, 42195.0), RaceDistance.entries.map { it.meters })
            assertEquals(
                RaceDistance.entries,
                RacePrognosis.predictAll(50.0, 100.0).map { it.distance },
            )
        }
    }

    @Nested
    inner class `a low Marathon Shape lengthens the half and the marathon but not the 5K` {
        private val vdot = 50.0
        private val shape = 40.0

        @Test
        fun `the 5K is the VDOT time exactly`() {
            val prediction = RacePrognosis.predict(vdot, shape, RaceDistance.FIVE_K)

            assertEquals(1.0, prediction.enduranceFactor)
            assertEquals(DanielsVdot.timeFor(vdot, 5000.0), prediction.predictedSeconds)
        }

        @Test
        fun `the 10K gains 1_61 percent, the Half 5_19 percent and the Marathon 12 percent`() {
            // 0.20 · 5000 / 37195 · 0.6 = 0.016131; 0.20 · 16097.5 / 37195 · 0.6 = 0.051934.
            val expected =
                mapOf(
                    RaceDistance.TEN_K to 1.016131,
                    RaceDistance.HALF_MARATHON to 1.051934,
                    RaceDistance.MARATHON to 1.12,
                )
            expected.forEach { (distance, factor) ->
                val prediction = RacePrognosis.predict(vdot, shape, distance)
                assertEquals(factor, prediction.enduranceFactor, 1e-6, distance.name)
                assertEquals(DanielsVdot.timeFor(vdot, distance.meters) * factor, prediction.predictedSeconds, 0.01)
            }
        }

        @Test
        fun `VDOT 50 at Marathon Shape 24 runs the marathon in 3_39_39`() {
            // The equations give 3:10:40 (11439.7 s); × (1 + 0.20 · 0.76) = 13178.6 s.
            assertEquals(13178.6, RacePrognosis.predict(vdot, 24.0, RaceDistance.MARATHON).predictedSeconds, 0.5)
        }

        @Test
        fun `the penalty grows with distance and falls as Marathon Shape rises`() {
            val byDistance = RacePrognosis.predictAll(vdot, shape).map { it.enduranceFactor }
            assertEquals(byDistance.sorted(), byDistance)
            assertTrue(byDistance.zipWithNext().drop(1).all { (shorter, longer) -> longer > shorter })

            val byShape = listOf(0.0, 25.0, 50.0, 75.0, 100.0).map { RacePrognosis.enduranceFactor(it, RaceDistance.MARATHON) }
            assertEquals(listOf(1.2, 1.15, 1.1, 1.05, 1.0), byShape.map { Math.round(it * 1000) / 1000.0 })
        }
    }

    @Nested
    inner class `Course Derating` {
        @Test
        fun `25 degrees slows every distance by 1_5 percent`() {
            RaceDistance.entries.forEach {
                val prediction = RacePrognosis.predict(50.0, 100.0, it, RaceConditions(temperatureC = 25.0))
                assertEquals(1.015, prediction.courseFactor, 1e-9)
                assertEquals(prediction.vdotSeconds * 1.015, prediction.predictedSeconds, 1e-6)
            }
        }

        @Test
        fun `200 m of climb on a 10K slows it by 8 percent, on a marathon by 1_9 percent`() {
            val climb = RaceConditions(elevationGainM = 200.0)

            assertEquals(1.08, RacePrognosis.predict(50.0, 100.0, RaceDistance.TEN_K, climb).courseFactor, 1e-9)
            assertEquals(1 + 800 / 42195.0, RacePrognosis.predict(50.0, 100.0, RaceDistance.MARATHON, climb).courseFactor, 1e-9)
        }

        @Test
        fun `heat and climb multiply, on top of the endurance penalty`() {
            val prediction =
                RacePrognosis.predict(50.0, 40.0, RaceDistance.TEN_K, RaceConditions(temperatureC = 25.0, elevationGainM = 200.0))

            assertEquals(1.015 * 1.08, prediction.courseFactor, 1e-9)
            assertEquals(prediction.vdotSeconds * 1.016131 * 1.015 * 1.08, prediction.predictedSeconds, 0.01)
        }

        @Test
        fun `cool weather and a flat course cost nothing`() {
            val prediction =
                RacePrognosis.predict(50.0, 100.0, RaceDistance.HALF_MARATHON, RaceConditions(temperatureC = 10.0, elevationGainM = 0.0))

            assertEquals(1.0, prediction.courseFactor)
        }
    }
}
