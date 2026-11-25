package com.jacobcraig.stridehearth.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcclimationCalculatorTest {
    private val hot = DayExposure(heat = true, altitude = false)
    private val high = DayExposure(heat = false, altitude = true)
    private val none = DayExposure(heat = false, altitude = false)

    private fun heat(exposures: List<DayExposure>) = AcclimationCalculator.series(exposures).map { it.heatPct }

    @Test
    fun `each exposed day builds 100 over 14 days`() {
        val series = heat(List(15) { hot })

        assertEquals(100.0 / 14, series[0], 1e-9)
        assertEquals(50.0, series[6], 1e-9)
        assertEquals(100.0, series[13], 1e-9)
        assertEquals(100.0, series[14], 1e-9, "clamped at 100")
    }

    @Test
    fun `each unexposed day decays 100 over 21 days`() {
        val series = heat(List(14) { hot } + List(22) { none })

        assertEquals(100.0 - 100.0 / 21, series[14], 1e-9)
        assertEquals(66.666_666_666_7, series[13 + 7], 1e-9)
        assertEquals(0.0, series[13 + 21], 0.0, "exactly 0 after 21 days")
        assertEquals(0.0, series[13 + 22], 0.0, "clamped at 0")
    }

    @Test
    fun `exposure every other day still rises`() {
        val series = heat(List(20) { if (it % 2 == 0) hot else none })

        // Each pair of days nets 100/14 - 100/21 = 100/42.
        assertEquals(100.0 / 14 - 100.0 / 21, series[1], 1e-9)
        assertEquals(10 * 100.0 / 42, series[19], 1e-9)
        assertTrue(series[19] > series[9])
    }

    @Test
    fun `heat and altitude are tracked independently`() {
        val series = AcclimationCalculator.series(listOf(hot, hot, high, none))

        val heatExpected = listOf(100.0 / 14, 200.0 / 14, 200.0 / 14 - 100.0 / 21, 200.0 / 14 - 200.0 / 21)
        val altitudeExpected = listOf(0.0, 0.0, 100.0 / 14, 100.0 / 14 - 100.0 / 21)
        heatExpected.zip(series).forEach { (expected, day) -> assertEquals(expected, day.heatPct, 1e-9) }
        altitudeExpected.zip(series).forEach { (expected, day) -> assertEquals(expected, day.altitudePct, 1e-9) }
        assertEquals(listOf(true, true, false, false), series.map { it.heatExposed })
        assertEquals(listOf(false, false, true, false), series.map { it.altitudeExposed })
    }

    @Test
    fun `seeding from a stored day continues the series`() {
        val whole = AcclimationCalculator.series(List(10) { hot } + List(5) { none } + List(3) { high })
        val stored = whole[11]

        val continued =
            AcclimationCalculator.series(
                List(3) { none } + List(3) { high },
                seed = AcclimationSeed(stored.heatPct, stored.altitudePct),
            )

        assertEquals(whole.drop(12), continued)
    }
}
