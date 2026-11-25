package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The **Performance Management Chart** series (#36): Fitness (CTL), Fatigue (ATL), Form (TSB), the uncoupled
 * Acute:Chronic Workload Ratio, Monotony and Strain, checked against closed forms, hand-worked weeks and an
 * independent reference (`src/test/resources/pmc/reference-loads.csv`).
 */
class PmcCalculatorTest {
    private fun constant(
        load: Double,
        days: Int,
    ) = DoubleArray(days) { load }

    @Test
    fun `the smoothing constants are 1 - e to the minus 1 over 42 and over 7`() {
        assertEquals(0.0235283, PmcCalculator.CTL_ALPHA, 1e-7)
        assertEquals(0.1331221, PmcCalculator.ATL_ALPHA, 1e-7)
    }

    @Test
    fun `a constant load from zero approaches it as 1 - e to the minus n over tau`() {
        val series = PmcCalculator.series(constant(100.0, 60))

        assertEquals(2.353, series[0].ctl, 1e-3, "CTL after one day")
        assertEquals(13.312, series[0].atl, 1e-3, "ATL after one day")
        assertEquals(63.212, series[6].atl, 1e-3, "ATL after 7 days")
        assertEquals(63.212, series[41].ctl, 1e-3, "CTL after 42 days")
        assertEquals(100 * (1 - exp(-60.0 / 42)), series[59].ctl, 1e-9)
    }

    @Test
    fun `Form is yesterday's Fitness minus yesterday's Fatigue, and 0 on the first day`() {
        val series = PmcCalculator.series(doubleArrayOf(80.0, 0.0, 120.0, 40.0))

        assertEquals(0.0, series[0].tsb)
        for (day in 1..3) {
            assertEquals(series[day - 1].ctl - series[day - 1].atl, series[day].tsb, 1e-12, "TSB on day $day")
        }
    }

    @Test
    fun `ACWR is withheld until day 34 and is 1 under a constant load`() {
        val series = PmcCalculator.series(constant(100.0, 40))

        assertNull(series[33].acwr, "day 33 has only 27 chronic days")
        assertEquals(1.0, assertNotNull(series[34].acwr), 1e-12)
        assertEquals(1.0, assertNotNull(series[39].acwr), 1e-12)
    }

    @Test
    fun `ACWR is the last 7 days' mean over the mean of the 28 days before them`() {
        val loads = constant(100.0, 28) + constant(150.0, 7)

        assertEquals(1.5, assertNotNull(PmcCalculator.series(loads)[34].acwr), 1e-12)
    }

    @Test
    fun `ACWR is null when the chronic window holds no load`() {
        // Scored on day 0 only, then 33 rest days and one load: the chronic window of day 34 is days 0..27.
        val loads = DoubleArray(70)
        loads[0] = 50.0
        loads[69] = 80.0

        assertNotNull(PmcCalculator.series(loads)[34].acwr)
        assertNull(PmcCalculator.series(loads)[69].acwr, "days 35..62 are all rest")
    }

    @Test
    fun `Monotony is the week's mean over its sample standard deviation and Strain the week's sum times it`() {
        val series = PmcCalculator.series(doubleArrayOf(100.0, 0.0, 100.0, 0.0, 100.0, 0.0, 100.0))

        // Mean 57.143, sample SD 53.452.
        assertEquals(1.0690, assertNotNull(series[6].monotony), 1e-4)
        assertEquals(427.6, assertNotNull(series[6].strain), 0.05)
        assertNull(series[5].monotony, "withheld before day 6")
        assertNull(series[5].strain)
    }

    @Test
    fun `Monotony and Strain are null for a week of equal loads`() {
        val series = PmcCalculator.series(constant(87.3, 10))

        assertNull(series[9].monotony)
        assertNull(series[9].strain)
    }

    @Test
    fun `a week whose loads differ by a hair still has a finite Monotony, as Foster's formula gives`() {
        // Sample SD about 3.8e-8: real variation, far above the rounding noise of summing equal loads.
        val series = PmcCalculator.series(constant(100.0, 6) + doubleArrayOf(100.0000001))

        val monotony = assertNotNull(series[6].monotony)
        assertEquals(100.0 / 3.7796447e-8, monotony, monotony * 1e-6)
        assertNotNull(series[6].strain)
    }

    @Test
    fun `summing equal loads with rounding noise still counts as a week of equal loads`() {
        // 0.1 + 0.2 is 0.30000000000000004: a sum of two Activities against a single one of 0.3.
        val series = PmcCalculator.series(constant(0.3, 6) + doubleArrayOf(0.1 + 0.2))

        assertNull(series[6].monotony)
        assertNull(series[6].strain)
    }

    @Test
    fun `withholding counts from the first scored day, not from the start of the loads`() {
        val loads = DoubleArray(45)
        for (day in 10 until 45) loads[day] = 100.0

        val series = PmcCalculator.series(loads, firstDay = 10, from = 10)

        assertEquals(35, series.size)
        assertNull(series[33].acwr, "day 43 is 33 days after the first scored day")
        assertNotNull(series[34].acwr)
        assertNull(series[5].monotony)
        assertNull(series[6].monotony, "a week of equal loads")
    }

    @Test
    fun `continuing from a seeded day gives the same values as computing from the start`() {
        val loads = reference().map { it.load }.toDoubleArray()
        val whole = PmcCalculator.series(loads)
        val from = 40
        val seed = PmcSeed(whole[from - 1].ctl, whole[from - 1].atl)

        val continued = PmcCalculator.series(loads.copyOfRange(from - 34, loads.size), firstDay = -(from - 34), from = 34, seed = seed)

        assertEquals(whole.drop(from), continued)
    }

    @Test
    fun `the series matches the independent reference to 1e-6`() {
        val reference = reference()
        val series = PmcCalculator.series(reference.map { it.load }.toDoubleArray())

        assertEquals(reference.size, series.size)
        reference.zip(series).forEach { (expected, actual) ->
            val day = expected.day
            assertEquals(expected.ctl, actual.ctl, 1e-6, "CTL on day $day")
            assertEquals(expected.atl, actual.atl, 1e-6, "ATL on day $day")
            assertEquals(expected.tsb, actual.tsb, 1e-6, "TSB on day $day")
            assertNullableEquals(expected.acwr, actual.acwr, "ACWR on day $day")
            assertNullableEquals(expected.monotony, actual.monotony, "Monotony on day $day")
            assertNullableEquals(expected.strain, actual.strain, "Strain on day $day")
        }
    }

    private fun assertNullableEquals(
        expected: Double?,
        actual: Double?,
        message: String,
    ) {
        if (expected == null) {
            assertNull(actual, message)
        } else {
            assertEquals(expected, assertNotNull(actual, message), 1e-6, message)
        }
    }

    private data class ReferenceDay(
        val day: Int,
        val load: Double,
        val ctl: Double,
        val atl: Double,
        val tsb: Double,
        val acwr: Double?,
        val monotony: Double?,
        val strain: Double?,
    )

    /**
     * `reference-loads.csv`, written by `generate_reference_loads.py` (next to it), a Python implementation of the
     * published definitions kept separate from [PmcCalculator].
     */
    private fun reference(): List<ReferenceDay> {
        val text = checkNotNull(javaClass.getResource("/pmc/reference-loads.csv")).readText()
        return text.lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val cells = line.split(",")
            ReferenceDay(
                day = cells[0].toInt(),
                load = cells[1].toDouble(),
                ctl = cells[2].toDouble(),
                atl = cells[3].toDouble(),
                tsb = cells[4].toDouble(),
                acwr = cells[5].toDoubleOrNull(),
                monotony = cells[6].toDoubleOrNull(),
                strain = cells[7].toDoubleOrNull(),
            )
        }
    }
}
