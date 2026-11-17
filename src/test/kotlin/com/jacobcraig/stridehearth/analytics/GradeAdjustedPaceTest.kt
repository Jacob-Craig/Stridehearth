package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.math.abs
import kotlin.math.sqrt
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

    /** The trailing [seconds]-sample rolling means of a 1 Hz series without gaps. */
    private fun Array<Float?>.rolling(seconds: Int): List<Double> = map { it!!.toDouble() }.windowed(seconds) { it.average() }

    /** Asserts every 5 s rolling mean of [gap] is within 1 percent of the 5 s rolling mean of the cleaned pace. */
    private fun assertWithinOnePercentOverFiveSeconds(
        cleaned: CleanedTelemetry,
        gap: Array<Float?>,
    ) {
        val rollingGap = gap.rolling(5)
        val rollingPace = cleaned.speed.rolling(5)
        rollingGap.indices.forEach { k ->
            val ratio = rollingGap[k] / rollingPace[k]
            assertTrue(abs(ratio - 1) < 0.01, "5 s rolling GAP / pace at ${k + 4} is $ratio")
        }
    }

    /**
     * Altitude as a watch without a barometer derives it from GPS: [base] plus AR(1) noise with standard deviation
     * [sigma] metres and lag-one correlation [rho] per sample (a fixed pseudo-random sequence).
     */
    private fun gpsAltitude(
        n: Int,
        sigma: Double,
        rho: Double,
        seed: Long,
        base: Double = 50.0,
    ): FloatArray {
        val random = java.util.Random(seed)
        var noise = sigma * random.nextGaussian()
        return FloatArray(n) {
            if (it > 0) noise = rho * noise + sqrt(1 - rho * rho) * sigma * random.nextGaussian()
            (base + noise).toFloat()
        }
    }

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

        @Test
        fun `a flat run whose FIT altitude flickers and drifts in 0,2 m steps keeps mean GAP within 1 percent of mean pace`() {
            // As a FIT barometer records it: a level reading, quantised to 0.2 m, that flickers one step and wanders
            // up and down by a few steps over the hour (a pseudo-random but fixed sequence).
            var level = 0
            val random = java.util.Random(32)
            val altitude =
                FloatArray(3600) { i ->
                    if (i % 90 == 0) level = (level + random.nextInt(3) - 1).coerceIn(-3, 3)
                    val flicker = if (random.nextInt(4) == 0) 1 else 0
                    (100.0 + 0.2 * (level + flicker)).toFloat()
                }
            val cleaned = outdoorRun(3600) { _, i -> altitude[i] }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))
            val meanSpeed = cleaned.speed.meanOfPresent()

            assertTrue(abs(gap.meanOfPresent() - meanSpeed) / meanSpeed < 0.01, "mean GAP ${gap.meanOfPresent()} vs $meanSpeed")
            assertWithinOnePercentOverFiveSeconds(cleaned, gap)
        }

        @Test
        fun `a flat run whose FIT altitude settles one 0,2 m step higher keeps GAP within 1 percent of pace over 5 s`() {
            // Across the 180 m grade baseline the step reads as at most 0.2 / 180 = 0.11 % grade: GAP 0.6 % above pace.
            val cleaned = outdoorRun(1200) { d, _ -> if (d < 1800.0) 100.0f else 100.2f }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))
            val meanSpeed = cleaned.speed.meanOfPresent()

            assertTrue(abs(gap.meanOfPresent() - meanSpeed) / meanSpeed < 0.01, "mean GAP ${gap.meanOfPresent()} vs $meanSpeed")
            assertWithinOnePercentOverFiveSeconds(cleaned, gap)
        }

        @Test
        fun `a flat run whose FIT altitude flickers between two 0,2 m steps keeps GAP within 1 percent of pace over 5 s`() {
            // The worst case #32's ±10 m grade read as about ±10 %: every other sample one step up.
            val cleaned = outdoorRun(1200) { _, i -> if (i % 2 == 0) 100.0f else 100.2f }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            assertWithinOnePercentOverFiveSeconds(cleaned, gap)
        }

        @Test
        fun `a flat run with the 0,3 m altitude noise keeps GAP within 1 percent of pace over 5 s`() {
            val cleaned =
                outdoorRun(1200) { _, i ->
                    (30.0 + (if (i % 2 == 0) 0.3 else -0.3) + (if ((i / 7) % 2 == 0) 0.3 else -0.3)).toFloat()
                }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            assertWithinOnePercentOverFiveSeconds(cleaned, gap)
        }

        @ParameterizedTest(name = "sigma {0} m, rho {1}")
        @CsvSource("1.0, 0.9", "2.0, 0.9")
        fun `a flat run with GPS-derived altitude keeps mean GAP and NGP within 1 percent of pace`(
            sigma: Double,
            rho: Double,
        ) {
            // A watch without a barometer: 1 to 2 m of correlated altitude noise on a perfectly flat route. Minetti's
            // convex cost turns zero-mean grade noise into an upward bias (#51); the long grade baseline keeps it small.
            val altitude = gpsAltitude(3600, sigma, rho, seed = 51)
            val cleaned = outdoorRun(3600) { _, i -> altitude[i] }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))
            val ngp = assertNotNull(RunMetrics.of(cleaned, gap).ngpMps)

            assertTrue(abs(gap.meanOfPresent() / 3.0 - 1) < 0.01, "mean GAP ${gap.meanOfPresent()} vs 3.0")
            assertTrue(abs(ngp / 3.0 - 1) < 0.01, "NGP $ngp vs 3.0")
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
        fun `a short 200 m climb at 5 percent still reads C(0,05) over C(0) at its middle`() {
            // Flat for 500 m, 200 m at 5 %, then flat at 10 m higher.
            val cleaned =
                outdoorRun(400) { d, _ ->
                    (
                        if (d < 500.0) {
                            0.0
                        } else if (d < 700.0) {
                            0.05 * (d - 500.0)
                        } else {
                            10.0
                        }
                    ).toFloat()
                }

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            // Sample 200 is at 600 m, the middle of the climb. Its anchors, [450, 510] and [630, 690], reach just past
            // the climb's foot, so it reads within 0.02 of Minetti's ratio for 5 % rather than exactly.
            assertEquals(1.3014, gap[200]!! / cleaned.speed[200]!!.toDouble(), 0.02)
            // Every sample on the climb reads uphill.
            for (k in 167..233) assertTrue(gap[k]!! > cleaned.speed[k]!!, "GAP at $k")
            // The flat well away from the climb is untouched.
            assertEquals(1.0, gap[50]!! / cleaned.speed[50]!!.toDouble(), 1e-6)
            assertEquals(1.0, gap[350]!! / cleaned.speed[350]!!.toDouble(), 1e-6)
        }

        @Test
        fun `grade is the slope between 60 m altitude means 90 m behind and 90 m ahead of the sample`() {
            // Hand-built cleaned details, 1 m apart, so cleaning plays no part: flat, then a 2 m step at 200 m.
            val n = 601
            val cleaned =
                CleanedTelemetry(
                    t = IntArray(n) { it },
                    speed = Array(n) { 1.0f },
                    distance = Array(n) { it.toFloat() },
                    heartRate = arrayOfNulls(n),
                    altitude = Array(n) { if (it < 200) 0.0f else 2.0f },
                    hasGps = true,
                )

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            // At 200 m the anchors are [80, 140] (0 m at 110 m) and [260, 320] (2 m at 290 m): 2 / 180 grade.
            assertEquals(1.061756, gap[200]!!.toDouble(), 1e-5)
            // At 130 m they are [10, 70] (0 m at 40 m) and [190, 250], whose 61 samples include 51 at 2 m
            // (102 / 61 m at 220 m): (102 / 61) / 180 grade.
            assertEquals(1.051419, gap[130]!!.toDouble(), 1e-5)
            // Up to 120 m the baseline slides inwards to the first 240 m: [0, 60] (0 m at 30 m) and [180, 240], with
            // 41 of 61 samples at 2 m (82 / 61 m at 210 m): (82 / 61) / 180 grade.
            assertEquals(1.041165, gap[0]!!.toDouble(), 1e-5)
            assertEquals(1.041165, gap[120]!!.toDouble(), 1e-5)
            // At 300 m they are [180, 240] (82 / 61 m at 210 m) and [360, 420] (2 m at 390 m): (2 - 82 / 61) / 180.
            assertEquals(1.019903, gap[300]!!.toDouble(), 1e-5)
            // From 480 m the baseline slides inwards to the last 240 m, [360, 600], all at 2 m: flat.
            assertEquals(1.0, gap[480]!!.toDouble(), 1e-9)
            assertEquals(1.0, gap[600]!!.toDouble(), 1e-9)
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
        fun `an anchor window with no sample, across a gap in the recorded distance, takes the nearest sample`() {
            // 1 m apart from 0 to 100 m and from 300 to 500 m, on a steady 5 % climb.
            val distances = (0..100) + (300..500)
            val n = distances.size
            val cleaned =
                CleanedTelemetry(
                    t = IntArray(n) { it },
                    speed = Array(n) { 1.0f },
                    distance = Array(n) { distances[it].toFloat() },
                    heartRate = arrayOfNulls(n),
                    altitude = Array(n) { 0.05f * distances[it] },
                    hasGps = true,
                )

            val gap = assertNotNull(GradeAdjustedPace.gapSpeed(cleaned))

            // Sample 80 (80 m) anchors ahead on [140, 200]: empty, so the sample at 100 m stands in, still on the line.
            gap.forEachIndexed { k, it -> assertEquals(1.3014, it!!.toDouble(), 1e-3, "GAP at $k") }
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
