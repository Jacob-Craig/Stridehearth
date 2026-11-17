package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every expectation here is worked out by hand from the fixture, never read back from the cleaner.
 * Fixtures are synthetic streams sampled once a second unless a test says otherwise.
 */
class TelemetryCleanerTest {
    /** Details with a GPS fix on every sample, heading north at roughly [metresPerSample] per sample. */
    private fun outdoor(
        n: Int,
        metresPerSample: Double = 3.0,
        t: IntArray = IntArray(n) { it },
        speed: Array<Float?> = emptyArray(),
        enhancedSpeed: Array<Float?> = emptyArray(),
        distance: Array<Float?> = emptyArray(),
        heartRate: Array<Short?> = emptyArray(),
        cadence: Array<Short?> = emptyArray(),
        fractionalCadence: Array<Float?> = emptyArray(),
        altitude: Array<Float?> = emptyArray(),
        enhancedAltitude: Array<Float?> = emptyArray(),
    ) = FitTelemetry(
        t = t,
        lons = DoubleArray(n) { -1.5 },
        lats = DoubleArray(n) { 53.0 + it * metresPerSample / METRES_PER_DEGREE_LATITUDE },
        routeIndex = Array(n) { it },
        speed = speed,
        enhancedSpeed = enhancedSpeed,
        distance = distance,
        heartRate = heartRate,
        cadence = cadence,
        fractionalCadence = fractionalCadence,
        altitude = altitude,
        enhancedAltitude = enhancedAltitude,
    )

    /** Details with no GPS fix at all, as a treadmill or indoor trainer records them. */
    private fun indoor(
        n: Int,
        speed: Array<Float?> = emptyArray(),
        enhancedSpeed: Array<Float?> = emptyArray(),
        distance: Array<Float?> = emptyArray(),
        heartRate: Array<Short?> = emptyArray(),
        altitude: Array<Float?> = emptyArray(),
        enhancedAltitude: Array<Float?> = emptyArray(),
    ) = FitTelemetry(
        t = IntArray(n) { it },
        lons = doubleArrayOf(),
        lats = doubleArrayOf(),
        routeIndex = arrayOfNulls(n),
        speed = speed,
        enhancedSpeed = enhancedSpeed,
        distance = distance,
        heartRate = heartRate,
        altitude = altitude,
        enhancedAltitude = enhancedAltitude,
    )

    private fun steadySpeed(
        n: Int,
        metresPerSecond: Float = 3.0f,
    ): Array<Float?> = Array(n) { metresPerSecond }

    private fun steadyDistance(
        n: Int,
        metresPerSecond: Float = 3.0f,
    ): Array<Float?> = Array(n) { it * metresPerSecond }

    private fun steadyHeartRate(
        n: Int,
        bpm: Int = 150,
    ): Array<Short?> = Array(n) { bpm.toShort() }

    private fun assertNonDecreasing(distance: Array<Float?>) {
        val present = distance.filterNotNull()
        for (i in 1 until present.size) {
            assertTrue(present[i] >= present[i - 1], "distance decreases at present sample $i")
        }
    }

    @Nested
    inner class GpsSpikes {
        /** A 3 m/s run whose distance teleports 500 m in the one second ending at t=60 (497 m too many). */
        private fun teleportDistance(n: Int): Array<Float?> = Array(n) { if (it < 60) it * 3.0f else it * 3.0f + 497.0f }

        @Test
        fun `a GPS teleport is rejected and speed is interpolated across it`() {
            val n = 120
            val speed = steadySpeed(n).also { it[60] = 40.0f }
            val details = outdoor(n, speed = speed, distance = teleportDistance(n))

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertTrue(cleaned.hasGps)
            // Neighbours are 3.0 m/s on both sides, so the rejected sample interpolates to 3.0.
            assertEquals(3.0f, cleaned.speed[60]!!, 1e-4f)
            for (i in 0 until n) if (i != 60) assertEquals(3.0f, cleaned.speed[i], "speed[$i]")
        }

        @Test
        fun `a GPS teleport removes only the excess distance`() {
            val n = 120
            val speed = steadySpeed(n).also { it[60] = 40.0f }
            val rawDistance = teleportDistance(n)
            val details = outdoor(n, speed = speed, distance = rawDistance)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            // Before the teleport nothing changes, bit for bit.
            for (i in 0 until 60) assertEquals(rawDistance[i], cleaned.distance[i], "distance[$i]")
            // The bad step is replaced by 1 s at the cleaned 3 m/s, so every later sample loses 497 m:
            // the cleaned series is the 3 m/s line the athlete actually ran.
            for (i in 60 until n) assertEquals(i * 3.0f, cleaned.distance[i]!!, 0.01f, "distance[$i]")
            assertEquals(rawDistance[n - 1]!! - 497.0f, cleaned.distance[n - 1]!!, 1.0f)
            assertNonDecreasing(cleaned.distance)
            for (i in 1 until n) {
                val rate = cleaned.distance[i]!! - cleaned.distance[i - 1]!!
                assertTrue(rate <= 8.5f, "step into $i implies $rate m/s")
            }
        }

        @Test
        fun `a teleport is repaired from neighbouring steps when no speed was recorded`() {
            val n = 120
            val details = outdoor(n, distance = teleportDistance(n))

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            // Speed is derived from distance (3 m per 1 s step); the 500 m/s step is rejected and interpolated.
            assertNull(cleaned.speed[0])
            for (i in 1 until n) assertEquals(3.0f, cleaned.speed[i]!!, 1e-3f, "speed[$i]")
            for (i in 0 until n) assertEquals(i * 3.0f, cleaned.distance[i]!!, 0.01f, "distance[$i]")
        }

        @Test
        fun `a backwards distance jump is repaired so distance never decreases`() {
            val n = 120
            // Distance drops 50 m at t=60: the step into it is 3 - 50 = -47 m.
            val rawDistance: Array<Float?> = Array(n) { if (it < 60) it * 3.0f else it * 3.0f - 50.0f }
            val details = outdoor(n, speed = steadySpeed(n), distance = rawDistance)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            // The bad step becomes 1 s at 3 m/s, so later samples gain 50 m and the line is 3 m/s again.
            for (i in 0 until n) assertEquals(i * 3.0f, cleaned.distance[i]!!, 0.01f, "distance[$i]")
            assertNonDecreasing(cleaned.distance)
        }

        @Test
        fun `clean outdoor distance and speed come back bit for bit`() {
            val n = 60
            val speed: Array<Float?> = Array(n) { 2.5f + (it % 7) * 0.1f }
            val distance: Array<Float?> = Array(n) { it * 2.9f }
            val details = outdoor(n, speed = speed, distance = distance)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertContentEquals(speed, cleaned.speed)
            assertContentEquals(distance, cleaned.distance)
        }

        @Test
        fun `speed gaps are interpolated only when their good neighbours are at most 10 s apart`() {
            val n = 60
            val speed = steadySpeed(n, 2.0f)
            // Leading gap: nothing before it, so no extrapolation.
            speed[0] = null
            speed[1] = null
            // 4-sample gap between 2.0 at t=10 and 4.0 at t=15 (5 s apart).
            for (i in 11..14) speed[i] = null
            speed[15] = 4.0f
            // 21-sample gap: good neighbours at t=29 and t=51 are 22 s apart.
            for (i in 30..50) speed[i] = null
            val details = outdoor(n, metresPerSample = 2.0, speed = speed, distance = steadyDistance(n, 2.0f))

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertNull(cleaned.speed[0])
            assertNull(cleaned.speed[1])
            assertEquals(2.4f, cleaned.speed[11]!!, 1e-4f)
            assertEquals(2.8f, cleaned.speed[12]!!, 1e-4f)
            assertEquals(3.2f, cleaned.speed[13]!!, 1e-4f)
            assertEquals(3.6f, cleaned.speed[14]!!, 1e-4f)
            for (i in 30..50) assertNull(cleaned.speed[i], "speed[$i]")
            assertEquals(2.0f, cleaned.speed[51])
        }

        @Test
        fun `enhanced speed is preferred sample by sample`() {
            val n = 20
            val speed = steadySpeed(n, 2.0f)
            val enhanced = steadySpeed(n, 3.0f).also { it[5] = null }
            val details = outdoor(n, speed = speed, enhancedSpeed = enhanced, distance = steadyDistance(n))

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertEquals(3.0f, cleaned.speed[4])
            assertEquals(2.0f, cleaned.speed[5])
        }

        @Test
        fun `the speed ceiling depends on the sport`() {
            val n = 40
            // 7 m/s is impossible on foot for a walk (5.0) but fine for a run (8.5) and a ride (30).
            val speed = steadySpeed(n).also { it[20] = 7.0f }
            val details = outdoor(n, speed = speed, distance = steadyDistance(n))

            assertEquals(3.0f, TelemetryCleaner.clean(details, ActivitySport.WALK).speed[20]!!, 1e-4f)
            assertEquals(3.0f, TelemetryCleaner.clean(details, ActivitySport.HIKE).speed[20]!!, 1e-4f)
            assertEquals(7.0f, TelemetryCleaner.clean(details, ActivitySport.RUN).speed[20])
            assertEquals(7.0f, TelemetryCleaner.clean(details, ActivitySport.RIDE).speed[20])
            // 3 m/s itself is at a swim's ceiling (3.0), not above it, so only the 7 m/s sample goes.
            assertEquals(3.0f, TelemetryCleaner.clean(details, ActivitySport.SWIM).speed[20]!!, 1e-4f)
        }

        @Test
        fun `a ride tolerates 25 metres per second but not 40`() {
            val n = 40
            val speed =
                steadySpeed(n, 10.0f).also {
                    it[10] = 25.0f
                    it[20] = 40.0f
                }
            val details = outdoor(n, metresPerSample = 10.0, speed = speed, distance = steadyDistance(n, 10.0f))

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RIDE)

            assertEquals(25.0f, cleaned.speed[10])
            assertEquals(10.0f, cleaned.speed[20]!!, 1e-4f)
        }

        @Test
        fun `a Workout has no speed ceiling`() {
            val n = 120
            val speed = steadySpeed(n).also { it[60] = 40.0f }
            val rawDistance: Array<Float?> = Array(n) { if (it < 60) it * 3.0f else it * 3.0f + 497.0f }
            val details = outdoor(n, speed = speed, distance = rawDistance)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.WORKOUT)

            assertContentEquals(speed, cleaned.speed)
            assertContentEquals(rawDistance, cleaned.distance)
        }
    }

    @Nested
    inner class HeartRate {
        @Test
        fun `a short zero dropout is interpolated`() {
            val n = 60
            val heartRate = steadyHeartRate(n)
            for (i in 20..24) heartRate[i] = 0
            val details = outdoor(n, speed = steadySpeed(n), distance = steadyDistance(n), heartRate = heartRate)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertContentEquals(steadyHeartRate(n), cleaned.heartRate)
        }

        @Test
        fun `a long zero dropout stays missing`() {
            val n = 60
            val heartRate = steadyHeartRate(n)
            for (i in 20..39) heartRate[i] = 0
            val details = outdoor(n, speed = steadySpeed(n), distance = steadyDistance(n), heartRate = heartRate)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            for (i in 0 until n) {
                val expected: Short? = if (i in 20..39) null else 150
                assertEquals(expected, cleaned.heartRate[i], "heartRate[$i]")
            }
        }

        @Test
        fun `a dropout is interpolated when its good neighbours are exactly 10 s apart, not 11`() {
            val n = 60
            val heartRate = steadyHeartRate(n)
            // 9 zeros: neighbours at t=9 and t=19, 10 s apart.
            for (i in 10..18) heartRate[i] = 0
            // 10 zeros: neighbours at t=29 and t=40, 11 s apart.
            for (i in 30..39) heartRate[i] = 0
            val details = outdoor(n, speed = steadySpeed(n), distance = steadyDistance(n), heartRate = heartRate)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            for (i in 10..18) assertEquals(150.toShort(), cleaned.heartRate[i], "heartRate[$i]")
            for (i in 30..39) assertNull(cleaned.heartRate[i], "heartRate[$i]")
        }

        @Test
        fun `interpolated heart rate is linear in time and rounded to whole beats`() {
            // Irregular sampling: 140 at t=0, dropout at t=1 and t=4, 150 at t=8.
            // Linear in time: 140 + 10 * 1/8 = 141.25 -> 141, 140 + 10 * 4/8 = 145.
            val details =
                FitTelemetry(
                    t = intArrayOf(0, 1, 4, 8),
                    lons = doubleArrayOf(),
                    lats = doubleArrayOf(),
                    routeIndex = arrayOfNulls(4),
                    heartRate = arrayOf(140, 0, null, 150),
                )

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertContentEquals(arrayOf<Short?>(140, 141, 145, 150), cleaned.heartRate)
        }

        @Test
        fun `a step of more than 30 bpm in one second is rejected and interpolated`() {
            val details =
                indoor(4, heartRate = arrayOf(150, 150, 200, 150))

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertContentEquals(arrayOf<Short?>(150, 150, 150, 150), cleaned.heartRate)
        }

        @Test
        fun `a step of exactly 30 bpm in one second is kept, and the limit scales with elapsed time`() {
            // 150 -> 180 in 1 s is at the limit. 180 -> 120 over 2 s is 60 bpm in 2 s: also at the limit.
            val details =
                FitTelemetry(
                    t = intArrayOf(0, 1, 3, 4),
                    lons = doubleArrayOf(),
                    lats = doubleArrayOf(),
                    routeIndex = arrayOfNulls(4),
                    heartRate = arrayOf(150, 180, 120, 121),
                )

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertContentEquals(arrayOf<Short?>(150, 180, 120, 121), cleaned.heartRate)
        }

        /** Cadence 85 strides/min is a step rate of 170; heart rate is 140 except where [locked] says 169..172. */
        private fun cadenceLockFixture(
            n: Int,
            locked: IntRange,
        ): FitTelemetry {
            val heartRate: Array<Short?> =
                Array(n) { if (it in locked) (169 + (it - locked.first) % 4).toShort() else 140 }
            return outdoor(
                n,
                speed = steadySpeed(n),
                distance = steadyDistance(n),
                heartRate = heartRate,
                cadence = Array(n) { 85 },
            )
        }

        @Test
        fun `heart rate that tracks step rate for 60 s or more is cadence-lock and goes missing`() {
            val n = 300
            // t=100..188: 89 samples spanning 88 s.
            val details = cadenceLockFixture(n, 100..188)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            for (i in 0 until n) {
                val expected: Short? = if (i in 100..188) null else 140
                assertEquals(expected, cleaned.heartRate[i], "heartRate[$i]")
            }
        }

        @Test
        fun `a cadence match spanning exactly 60 s is cadence-lock`() {
            val n = 300
            val details = cadenceLockFixture(n, 100..160)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            for (i in 100..160) assertNull(cleaned.heartRate[i], "heartRate[$i]")
            assertEquals(140.toShort(), cleaned.heartRate[99])
            assertEquals(140.toShort(), cleaned.heartRate[161])
        }

        @Test
        fun `a cadence match shorter than 60 s is kept`() {
            val n = 300
            // t=100..140 spans 40 s. Both edges step 29 bpm (140 <-> 169), inside the step limit.
            val details = cadenceLockFixture(n, 100..140)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertContentEquals(details.heartRate, cleaned.heartRate)
        }

        @Test
        fun `cadence-lock applies to walks and hikes but not to rides`() {
            val n = 300
            val details = cadenceLockFixture(n, 100..188)

            assertNull(TelemetryCleaner.clean(details, ActivitySport.WALK).heartRate[150])
            assertNull(TelemetryCleaner.clean(details, ActivitySport.HIKE).heartRate[150])
            assertContentEquals(details.heartRate, TelemetryCleaner.clean(details, ActivitySport.RIDE).heartRate)
        }

        @Test
        fun `step rate is twice the stored cadence including its fractional half-step`() {
            val n = 120
            // Stored cadence 84 with fractional 0.5 is 84.5 strides/min: a step rate of 169.
            // Heart rate 172 is within 3 of 169, but 4 away from 168 (the rate if the fraction were ignored).
            val heartRate = steadyHeartRate(n, 172)
            val cadence: Array<Short?> = Array(n) { 84 }
            val withFraction =
                outdoor(n, heartRate = heartRate, cadence = cadence, fractionalCadence = Array(n) { 0.5f })
            val withoutFraction = outdoor(n, heartRate = heartRate, cadence = cadence)

            assertTrue(TelemetryCleaner.clean(withFraction, ActivitySport.RUN).heartRate.all { it == null })
            assertContentEquals(heartRate, TelemetryCleaner.clean(withoutFraction, ActivitySport.RUN).heartRate)
        }

        @Test
        fun `a missing cadence sample or a recording gap over 10 s ends a cadence match`() {
            val n = 100
            // Heart rate 170 against step rate 170 throughout, but cadence drops out at t=50:
            // two matches of 49 s each, neither long enough.
            val heartRate = steadyHeartRate(n, 170)
            val cadence: Array<Short?> = Array(n) { if (it == 50) null else 85 }
            val brokenByCadence = outdoor(n, heartRate = heartRate, cadence = cadence)
            assertContentEquals(heartRate, TelemetryCleaner.clean(brokenByCadence, ActivitySport.RUN).heartRate)

            // Smart recording pauses 11 s between t=40 and t=51; each side spans 40 s or less.
            val t = IntArray(80) { if (it <= 40) it else it + 10 }
            val paused =
                FitTelemetry(
                    t = t,
                    lons = doubleArrayOf(),
                    lats = doubleArrayOf(),
                    routeIndex = arrayOfNulls(80),
                    heartRate = steadyHeartRate(80, 170),
                    cadence = Array(80) { 85 },
                )
            assertContentEquals(paused.heartRate, TelemetryCleaner.clean(paused, ActivitySport.RUN).heartRate)
        }

        @Test
        fun `heart rate rules apply without GPS too`() {
            val n = 60
            val heartRate = steadyHeartRate(n)
            for (i in 20..24) heartRate[i] = 0
            val details = indoor(n, speed = steadySpeed(n), distance = steadyDistance(n), heartRate = heartRate)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertContentEquals(steadyHeartRate(n), cleaned.heartRate)
        }
    }

    @Nested
    inner class Altitude {
        private val n = 200

        /** The true climb: 5% at 3 m per sample is 0.15 m per sample from 100 m. */
        private fun ramp(i: Int): Float = 100.0f + 0.15f * i

        /** Barometric noise alternating +1.5 m on even samples and -1.5 m on odd ones. */
        private fun noisy(i: Int): Float = ramp(i) + if (i % 2 == 0) 1.5f else -1.5f

        private fun totalVariation(series: List<Float>): Float = series.zipWithNext { a, b -> abs(b - a) }.sum()

        @Test
        fun `noisy barometric altitude is smoothed by a 15 m distance window`() {
            val raw: Array<Float?> = Array(n) { noisy(it) }
            val details = outdoor(n, speed = steadySpeed(n), distance = steadyDistance(n), altitude = raw)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            // Samples are 3 m apart, so the window (7.5 m each way) holds the 5 samples i-2..i+2.
            // The ramp averages to itself; the noise leaves 3 of one sign and 2 of the other: 1.5 / 5 = 0.3 m.
            for (i in 2 until n - 2) {
                val expected = ramp(i) + if (i % 2 == 0) 0.3f else -0.3f
                assertEquals(expected, cleaned.altitude[i]!!, 1e-3f, "altitude[$i]")
                assertTrue(abs(cleaned.altitude[i]!! - ramp(i)) < 0.5f, "altitude[$i] strays from the ramp")
            }
            // Raw steps alternate 2.85 m and 3.15 m; cleaned steps alternate 0.45 m and 0.75 m: a fifth.
            val interior = 2 until n - 2
            val rawVariation = totalVariation(interior.map { raw[it]!! })
            val cleanedVariation = totalVariation(interior.map { cleaned.altitude[it]!! })
            assertTrue(cleanedVariation < rawVariation / 4, "$cleanedVariation is not well below $rawVariation")
        }

        @Test
        fun `the window is truncated at the ends of the Activity`() {
            val raw: Array<Float?> = Array(n) { noisy(it) }
            val details = outdoor(n, speed = steadySpeed(n), distance = steadyDistance(n), altitude = raw)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            // First sample: samples 0..2 = 101.5, 98.65, 101.8 -> mean 100.65.
            assertEquals(100.65f, cleaned.altitude[0]!!, 1e-3f)
        }

        @Test
        fun `enhanced altitude is preferred and a missing altitude sample is filled from its window`() {
            val small = 20
            val altitude: Array<Float?> = Array(small) { 50.0f }
            val enhanced: Array<Float?> = Array(small) { 200.0f }
            enhanced[10] = null
            altitude[10] = null
            enhanced[15] = null
            val details =
                outdoor(
                    small,
                    speed = steadySpeed(small),
                    distance = steadyDistance(small),
                    altitude = altitude,
                    enhancedAltitude = enhanced,
                )

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertEquals(200.0f, cleaned.altitude[3]!!, 1e-3f)
            // Sample 10 has no altitude of either kind: the mean of samples 8, 9, 11, 12 fills it.
            assertEquals(200.0f, cleaned.altitude[10]!!, 1e-3f)
            // Sample 15 falls back to plain altitude (50): window 13..17 = (4 * 200 + 50) / 5 = 170.
            assertEquals(170.0f, cleaned.altitude[15]!!, 1e-3f)
        }

        @Test
        fun `altitude smoothing uses cleaned distance, so a teleport does not split the window`() {
            val small = 40
            // Distance teleports +497 m at t=20; cleaned distance is the 3 m/s line again.
            val distance: Array<Float?> = Array(small) { if (it < 20) it * 3.0f else it * 3.0f + 497.0f }
            val speed = steadySpeed(small).also { it[20] = 40.0f }
            // Altitude steps from 100 to 110 at the teleport.
            val altitude: Array<Float?> = Array(small) { if (it < 20) 100.0f else 110.0f }
            val details = outdoor(small, speed = speed, distance = distance, altitude = altitude)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            // Window of sample 20 is 18..22 on cleaned distance: (2 * 100 + 3 * 110) / 5 = 106.
            // On raw distance it would hold only 20..22 and stay at 110.
            assertEquals(106.0f, cleaned.altitude[20]!!, 1e-3f)
        }

        @Test
        fun `altitude is missing where distance is missing`() {
            val small = 20
            val distance = steadyDistance(small).also { it[10] = null }
            val altitude: Array<Float?> = Array(small) { 100.0f }
            val details = outdoor(small, speed = steadySpeed(small), distance = distance, altitude = altitude)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertNull(cleaned.altitude[10])
            assertEquals(100.0f, cleaned.altitude[9]!!, 1e-3f)
            assertEquals(100.0f, cleaned.altitude[11]!!, 1e-3f)
        }

        @Test
        fun `altitude passes through unsmoothed when there is no distance at all`() {
            val small = 20
            val raw: Array<Float?> = Array(small) { noisy(it) }
            // GPS fixes that never move and no speed: no distance can be recorded or synthesised.
            val details = outdoor(small, metresPerSample = 0.0, altitude = raw)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertTrue(cleaned.hasGps)
            assertTrue(cleaned.distance.all { it == null })
            assertContentEquals(raw, cleaned.altitude)
        }
    }

    @Nested
    inner class ParallelArrays {
        private fun assertParallel(
            name: String,
            details: FitTelemetry,
            sport: ActivitySport,
        ) {
            val cleaned = TelemetryCleaner.clean(details, sport)
            val n = details.t.size
            assertContentEquals(details.t, cleaned.t, "$name ($sport): t")
            assertEquals(n, cleaned.speed.size, "$name ($sport): speed")
            assertEquals(n, cleaned.distance.size, "$name ($sport): distance")
            assertEquals(n, cleaned.heartRate.size, "$name ($sport): heartRate")
            assertEquals(n, cleaned.altitude.size, "$name ($sport): altitude")
        }

        private fun allAnomalies(): FitTelemetry {
            val n = 400
            val speed =
                steadySpeed(n).also {
                    it[60] = 40.0f
                    it[61] = -1.0f
                    for (i in 200..230) it[i] = null
                }
            val distance: Array<Float?> =
                Array(n) {
                    when {
                        it < 60 -> it * 3.0f
                        it < 300 -> it * 3.0f + 497.0f
                        else -> it * 3.0f + 400.0f
                    }
                }
            distance[150] = null
            val heartRate: Array<Short?> =
                Array(n) {
                    when (it) {
                        in 10..14 -> 0
                        in 40..70 -> 0
                        90 -> 220
                        in 250..340 -> 170
                        else -> 140
                    }
                }
            return outdoor(
                n,
                speed = speed,
                distance = distance,
                heartRate = heartRate,
                cadence = Array(n) { 85 },
                altitude = Array(n) { if (it % 9 == 0) null else 100.0f + (it % 3) },
            )
        }

        private fun sparseSmartRecording(): FitTelemetry {
            // Smart recording: irregular gaps of 1 to 14 s, a repeated timestamp, and a GPS fix on some samples only.
            val t = intArrayOf(0, 1, 5, 12, 12, 26, 27, 35, 49, 50, 58, 70)
            val n = t.size
            return FitTelemetry(
                t = t,
                lons = DoubleArray(6) { -1.5 },
                lats = DoubleArray(6) { 53.0 + it * 0.0002 },
                routeIndex = arrayOf(0, null, 1, 2, null, null, 3, null, 4, null, 5, null),
                speed = arrayOf(null, 3.0f, 3.1f, null, 12.0f, 3.0f, null, null, 2.9f, 3.0f, null, 3.2f),
                distance = arrayOf(0.0f, 3.0f, null, 36.0f, 36.0f, 700.0f, 703.0f, null, 760.0f, 755.0f, 790.0f, null),
                heartRate = arrayOf(null, 120, 0, 125, 190, null, null, 130, 131, 0, 0, 135),
                cadence = arrayOf(80, null, 81, 82, 82, null, 83, 84, null, 85, 85, 86),
                altitude = arrayOf(10.0f, null, 11.0f, null, null, 14.0f, 13.5f, null, 15.0f, null, 15.5f, 16.0f),
            )
        }

        @Test
        fun `every output array has the length of t for every kind of input`() {
            val fixtures =
                mapOf(
                    "no samples" to FitTelemetry(intArrayOf(), doubleArrayOf(), doubleArrayOf(), emptyArray()),
                    "one sample" to outdoor(1, speed = arrayOf(3.0f), distance = arrayOf(0.0f), heartRate = arrayOf(140)),
                    "GPS but no sensor streams" to outdoor(50),
                    "no GPS and no sensor streams" to indoor(50),
                    "indoor with streams" to
                        indoor(50, speed = steadySpeed(50), distance = steadyDistance(50), heartRate = steadyHeartRate(50)),
                    "sparse smart recording" to sparseSmartRecording(),
                    "all anomalies" to allAnomalies(),
                )

            for ((name, details) in fixtures) {
                for (sport in ActivitySport.entries) assertParallel(name, details, sport)
            }
        }

        @Test
        fun `distance never decreases after cleaning messy outdoor streams`() {
            for (sport in ActivitySport.entries) {
                assertNonDecreasing(TelemetryCleaner.clean(allAnomalies(), sport).distance)
                assertNonDecreasing(TelemetryCleaner.clean(sparseSmartRecording(), sport).distance)
            }
        }
    }

    @Nested
    inner class IndoorActivities {
        @Test
        fun `an indoor Activity with no GPS passes through unchanged`() {
            val n = 120
            val speed: Array<Float?> = Array(n) { 2.8f + (it % 5) * 0.05f }
            val distance: Array<Float?> = Array(n) { it * 2.9f }
            val heartRate: Array<Short?> = Array(n) { (130 + it % 10).toShort() }
            val altitude: Array<Float?> = Array(n) { if (it % 2 == 0) 21.5f else 18.5f }
            val details = indoor(n, speed = speed, distance = distance, heartRate = heartRate, altitude = altitude)

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertFalse(cleaned.hasGps)
            assertContentEquals(details.t, cleaned.t)
            assertContentEquals(speed, cleaned.speed)
            assertContentEquals(distance, cleaned.distance)
            assertContentEquals(heartRate, cleaned.heartRate)
            assertContentEquals(altitude, cleaned.altitude)
        }

        @Test
        fun `GPS rules do not apply indoors, so an implausible speed, a distance jump and gaps are left alone`() {
            val n = 120
            val speed =
                steadySpeed(n).also {
                    it[60] = 40.0f
                    it[80] = null
                }
            val distance: Array<Float?> = Array(n) { if (it < 60) it * 3.0f else it * 3.0f + 497.0f }
            val details = indoor(n, speed = speed, distance = distance, heartRate = steadyHeartRate(n))

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertContentEquals(speed, cleaned.speed)
            assertContentEquals(distance, cleaned.distance)
        }

        @Test
        fun `an indoor Activity that lacks distance gets the parser's synthesised distance`() {
            val n = 10
            // No distance recorded: the parser's synthesis accumulates speed x dt, here 3 m per second.
            val details = indoor(n, enhancedSpeed = steadySpeed(n))

            val cleaned = TelemetryCleaner.clean(details, ActivitySport.RUN)

            assertContentEquals(steadyDistance(n), cleaned.distance)
            assertContentEquals(steadySpeed(n), cleaned.speed)
        }

        @Test
        fun `absent streams come back as all-missing arrays`() {
            val cleaned = TelemetryCleaner.clean(indoor(5), ActivitySport.RUN)

            assertContentEquals(arrayOfNulls<Float>(5), cleaned.speed)
            assertContentEquals(arrayOfNulls<Float>(5), cleaned.distance)
            assertContentEquals(arrayOfNulls<Short>(5), cleaned.heartRate)
            assertContentEquals(arrayOfNulls<Float>(5), cleaned.altitude)
        }
    }

    @Nested
    inner class StoredActivityDetails {
        private fun assertUntouched(
            details: FitTelemetry,
            sport: ActivitySport,
        ) {
            val t = details.t.copyOf()
            val lons = details.lons.copyOf()
            val lats = details.lats.copyOf()
            val routeIndex = details.routeIndex.copyOf()
            val speed = details.speed.copyOf()
            val enhancedSpeed = details.enhancedSpeed.copyOf()
            val distance = details.distance.copyOf()
            val heartRate = details.heartRate.copyOf()
            val cadence = details.cadence.copyOf()
            val fractionalCadence = details.fractionalCadence.copyOf()
            val altitude = details.altitude.copyOf()
            val enhancedAltitude = details.enhancedAltitude.copyOf()

            val cleaned = TelemetryCleaner.clean(details, sport)

            assertContentEquals(t, details.t)
            assertContentEquals(lons, details.lons)
            assertContentEquals(lats, details.lats)
            assertContentEquals(routeIndex, details.routeIndex)
            assertContentEquals(speed, details.speed)
            assertContentEquals(enhancedSpeed, details.enhancedSpeed)
            assertContentEquals(distance, details.distance)
            assertContentEquals(heartRate, details.heartRate)
            assertContentEquals(cadence, details.cadence)
            assertContentEquals(fractionalCadence, details.fractionalCadence)
            assertContentEquals(altitude, details.altitude)
            assertContentEquals(enhancedAltitude, details.enhancedAltitude)
            // The output shares no array with the input, so a consumer cannot change the details through it.
            assertTrue(cleaned.t !== details.t)
            assertTrue(cleaned.speed !== details.speed && cleaned.speed !== details.enhancedSpeed)
            assertTrue(cleaned.distance !== details.distance)
            assertTrue(cleaned.heartRate !== details.heartRate)
            assertTrue(cleaned.altitude !== details.altitude && cleaned.altitude !== details.enhancedAltitude)
        }

        @Test
        fun `cleaning never modifies the Activity Details it reads`() {
            val n = 300
            val speed = steadySpeed(n).also { it[60] = 40.0f }
            val distance: Array<Float?> = Array(n) { if (it < 60) it * 3.0f else it * 3.0f + 497.0f }
            val heartRate: Array<Short?> =
                Array(n) {
                    when (it) {
                        in 10..14 -> 0
                        90 -> 220
                        in 150..250 -> 170
                        else -> 140
                    }
                }
            val anomalous =
                outdoor(
                    n,
                    speed = speed,
                    enhancedSpeed = speed.copyOf(),
                    distance = distance,
                    heartRate = heartRate,
                    cadence = Array(n) { 85 },
                    fractionalCadence = Array(n) { 0.0f },
                    altitude = Array(n) { 100.0f + it % 3 },
                    enhancedAltitude = Array(n) { 100.0f + it % 3 },
                )
            assertUntouched(anomalous, ActivitySport.RUN)

            // Details that lack distance: synthesis must happen on a copy, leaving the stored series all-missing.
            val lacksDistance = outdoor(n, speed = steadySpeed(n), distance = arrayOfNulls(n))
            assertUntouched(lacksDistance, ActivitySport.RUN)
            assertNotNull(TelemetryCleaner.clean(lacksDistance, ActivitySport.RUN).distance[n - 1])

            assertUntouched(indoor(n, speed = steadySpeed(n), distance = steadyDistance(n)), ActivitySport.RUN)
        }
    }

    private companion object {
        const val METRES_PER_DEGREE_LATITUDE = 111_195.0
    }
}
