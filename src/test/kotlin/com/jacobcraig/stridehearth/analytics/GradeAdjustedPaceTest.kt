package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expected values are worked out by hand from Minetti's polynomial
 * `C(i) = 155.4 i^5 - 30.4 i^4 - 43.3 i^3 + 46.3 i^2 + 19.5 i + 3.6`, never read back from the calculator.
 * Reference ratios `C(i) / 3.6`: 0 -> 1.0000, +0.05 -> 1.3014, +0.10 -> 1.6578, -0.10 -> 0.5977,
 * +0.45 -> 5.3961, -0.45 -> 1.1201.
 */
class GradeAdjustedPaceTest {
    /** An outdoor run sampled once a second at [speed] m/s, heading north, with altitude a function of distance. */
    private fun outdoorRun(
        n: Int,
        speed: Float = 3.0f,
        altitudeAt: (distance: Double, index: Int) -> Float? = { _, _ -> 30.0f },
    ): CleanedTelemetry {
        val distance = Array<Float?>(n) { it * speed }
        val details =
            FitTelemetry(
                t = IntArray(n) { it },
                lons = DoubleArray(n) { -1.5 },
                lats = DoubleArray(n) { 53.0 + it * speed / METRES_PER_DEGREE_LATITUDE },
                routeIndex = Array(n) { it },
                speed = Array(n) { speed },
                distance = distance,
                altitude = Array(n) { altitudeAt(it * speed.toDouble(), it) },
            )
        return TelemetryCleaner.clean(details, ActivitySport.RUN)
    }

    private fun Array<Float?>.meanOfPresent(): Double = filterNotNull().map { it.toDouble() }.average()

    @Nested
    inner class `Minetti cost` {
        @Test
        fun `matches the published polynomial at reference grades`() {
            assertEquals(3.6, GradeAdjustedPace.minettiCost(0.0), 1e-12)
            assertEquals(1.3014, GradeAdjustedPace.minettiCost(0.05) / 3.6, 1e-4)
            assertEquals(1.6578, GradeAdjustedPace.minettiCost(0.10) / 3.6, 1e-4)
            assertEquals(0.5977, GradeAdjustedPace.minettiCost(-0.10) / 3.6, 1e-4)
            assertEquals(5.3961, GradeAdjustedPace.minettiCost(0.45) / 3.6, 1e-4)
            assertEquals(1.1201, GradeAdjustedPace.minettiCost(-0.45) / 3.6, 1e-4)
        }

        @Test
        fun `GAP sports are Run, Walk and Hike`() {
            assertEquals(setOf(ActivitySport.RUN, ActivitySport.WALK, ActivitySport.HIKE), GradeAdjustedPace.GAP_SPORTS)
        }
    }

    @Nested
    inner class `flat ground` {
        @Test
        fun `a run at constant altitude has GAP exactly equal to its pace`() {
            val cleaned = outdoorRun(600)

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            assertEquals(600, gap.size)
            gap.forEach { assertEquals(3.0f, it) }
        }

        @Test
        fun `a flat run with altitude noise of 0,3 m keeps mean GAP within 1 percent of mean pace`() {
            // Alternating +/-0.3 m and a slower +/-0.3 m wobble every 7 samples, as a barometer jitters.
            val cleaned =
                outdoorRun(1200) { _, i ->
                    (30.0 + (if (i % 2 == 0) 0.3 else -0.3) + (if ((i / 7) % 2 == 0) 0.3 else -0.3)).toFloat()
                }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))
            val meanSpeed = cleaned.speed.meanOfPresent()

            assertTrue(abs(gap.meanOfPresent() - meanSpeed) / meanSpeed < 0.01, "mean GAP ${gap.meanOfPresent()} vs $meanSpeed")
        }
    }

    @Nested
    inner class `uphill and downhill` {
        @Test
        fun `a constant 5 percent climb makes every interior GAP faster than pace, by C(0,05) over C(0)`() {
            val cleaned = outdoorRun(400) { d, _ -> (0.05 * d).toFloat() }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            // Interior: away from the first and last 15 m, where the altitude window and grade window are one-sided.
            for (k in 10 until 390) {
                val pace = cleaned.speed[k]!!
                assertTrue(gap[k]!! > pace, "GAP at $k")
                assertEquals(1.3014, gap[k]!! / pace.toDouble(), 0.01, "ratio at $k")
            }
        }

        @Test
        fun `a constant 10 percent descent slows GAP to C(-0,10) over C(0)`() {
            val cleaned = outdoorRun(400) { d, _ -> (200.0 - 0.10 * d).toFloat() }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            for (k in 10 until 390) assertEquals(0.5977, gap[k]!! / cleaned.speed[k]!!.toDouble(), 0.01, "ratio at $k")
        }

        @Test
        fun `a 60 percent wall is clamped to the 45 percent cost`() {
            val cleaned = outdoorRun(200, speed = 1.0f) { d, _ -> (0.60 * d).toFloat() }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            for (k in 20 until 180) assertEquals(5.3961, gap[k]!! / cleaned.speed[k]!!.toDouble(), 0.01, "ratio at $k")
        }

        @Test
        fun `grade is measured over 10 m either side of the sample, not between neighbouring samples`() {
            // Hand-built cleaned details, 1 m apart, so smoothing plays no part: flat, then a 2 m step at 50 m.
            val n = 101
            val cleaned =
                CleanedTelemetry(
                    t = IntArray(n) { it },
                    speed = Array(n) { 1.0f },
                    distance = Array(n) { it.toFloat() },
                    heartRate = arrayOfNulls(n),
                    altitude = Array(n) { if (it < 50) 0.0f else 2.0f },
                    hasGps = true,
                )

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            // At 45 m the window is [35, 55]: rise 2 m over 20 m = 10 percent.
            assertEquals(1.6578, gap[45]!!.toDouble(), 1e-3)
            // At 30 m the window is [20, 40]: flat.
            assertEquals(1.0, gap[30]!!.toDouble(), 1e-9)
            // At 0 m the window is one-sided, [0, 10]: flat.
            assertEquals(1.0, gap[0]!!.toDouble(), 1e-9)
        }
    }

    @Nested
    inner class `missing data` {
        @Test
        fun `without altitude GAP equals pace`() {
            val cleaned = outdoorRun(100) { _, _ -> null }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            gap.forEach { assertEquals(3.0f, it) }
        }

        @Test
        fun `an indoor run without GPS ignores a drifting barometer`() {
            val n = 300
            val details =
                FitTelemetry(
                    t = IntArray(n) { it },
                    lons = doubleArrayOf(),
                    lats = doubleArrayOf(),
                    routeIndex = arrayOfNulls(n),
                    speed = Array(n) { 2.5f },
                    distance = Array(n) { it * 2.5f },
                    // Climbs 0.5 m a second: a 20 percent "hill" that is only barometer drift.
                    altitude = Array(n) { 30.0f + it * 0.5f },
                )
            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            gap.forEach { assertEquals(2.5f, it) }
        }

        @Test
        fun `a sample without cleaned speed has no GAP, and other samples keep theirs`() {
            val n = 5
            val cleaned =
                CleanedTelemetry(
                    t = IntArray(n) { it },
                    speed = arrayOf(2.0f, null, 2.0f, 2.0f, 2.0f),
                    distance = Array(n) { it * 2.0f },
                    heartRate = arrayOfNulls(n),
                    altitude = Array(n) { 10.0f },
                    hasGps = true,
                )

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            assertEquals(listOf(2.0f, null, 2.0f, 2.0f, 2.0f), gap.toList())
        }

        @Test
        fun `a sample with altitude but no distance gets grade 0`() {
            val n = 30
            val cleaned =
                CleanedTelemetry(
                    t = IntArray(n) { it },
                    speed = Array(n) { 1.0f },
                    distance = Array(n) { if (it == 15) null else it.toFloat() },
                    heartRate = arrayOfNulls(n),
                    altitude = Array(n) { it * 0.05f },
                    hasGps = true,
                )

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            assertEquals(1.0f, gap[15])
            assertEquals(1.3014, gap[14]!!.toDouble(), 1e-3)
        }

        @Test
        fun `no GAP stream at all when the details have no speed`() {
            val n = 10
            val cleaned =
                CleanedTelemetry(
                    t = IntArray(n) { it },
                    speed = arrayOfNulls(n),
                    distance = arrayOfNulls(n),
                    heartRate = Array(n) { 140.toShort() },
                    altitude = arrayOfNulls(n),
                    hasGps = false,
                )

            assertNull(GradeAdjustedPace.gapSpeed(cleaned))
        }

        @Test
        fun `empty details have no GAP stream`() {
            val cleaned = CleanedTelemetry(IntArray(0), emptyArray(), emptyArray(), emptyArray(), emptyArray(), hasGps = false)

            assertNull(GradeAdjustedPace.gapSpeed(cleaned))
        }
    }

    private companion object {
        const val METRES_PER_DEGREE_LATITUDE = 111_195.0
    }
}
