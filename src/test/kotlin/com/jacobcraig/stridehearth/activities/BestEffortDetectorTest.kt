package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BestEffortDetectorTest {
    private val detector = BestEffortDetector()

    @Test
    fun `returns empty list for a sport without Benchmark Distances`() {
        val telemetry =
            FitTelemetry(
                t = intArrayOf(0, 10, 20),
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOfNulls(3),
                distance = arrayOf(0.0f, 500.0f, 1000.0f),
            )

        val efforts = detector.detectBestEfforts(telemetry, ActivitySport.RIDE)
        assertTrue(efforts.isEmpty())
    }

    @Test
    fun `returns empty list when distance is insufficient for any benchmark`() {
        val telemetry =
            FitTelemetry(
                t = intArrayOf(0, 10, 20),
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOfNulls(3),
                distance = arrayOf(0.0f, 150.0f, 300.0f), // 300m < 400m
            )

        val efforts = detector.detectBestEfforts(telemetry, ActivitySport.RUN)
        assertTrue(efforts.isEmpty())
    }

    @Test
    fun `detects best efforts at exact sample boundaries for qualifying benchmarks`() {
        // 201 samples: 0 to 200 seconds, 0m to 1000m (5 m/s)
        val t = IntArray(201) { it }
        val dist = Array<Float?>(201) { (it * 5.0f) }
        val telemetry =
            FitTelemetry(
                t = t,
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOfNulls(201),
                distance = dist,
            )

        val efforts = detector.detectBestEfforts(telemetry, ActivitySport.RUN)
        assertEquals(2, efforts.size)

        val effort400m = efforts.find { it.benchmarkDistance == BenchmarkDistance.FOUR_HUNDRED_METERS }
        kotlin.test.assertNotNull(effort400m)
        assertEquals(80.0, effort400m.elapsedTimeSeconds)
        assertEquals(0, effort400m.startIndex)
        assertEquals(80, effort400m.endIndex)
        assertEquals(0.0, effort400m.startTimeSeconds)
        assertEquals(80.0, effort400m.endTimeSeconds)
        assertEquals(0.0, effort400m.startDistanceMeters)
        assertEquals(400.0, effort400m.endDistanceMeters)

        val effort1k = efforts.find { it.benchmarkDistance == BenchmarkDistance.ONE_KILOMETER }
        kotlin.test.assertNotNull(effort1k)
        assertEquals(200.0, effort1k.elapsedTimeSeconds)
        assertEquals(0, effort1k.startIndex)
        assertEquals(200, effort1k.endIndex)
        assertEquals(0.0, effort1k.startTimeSeconds)
        assertEquals(200.0, effort1k.endTimeSeconds)
        assertEquals(0.0, effort1k.startDistanceMeters)
        assertEquals(1000.0, effort1k.endDistanceMeters)
    }

    @Test
    fun `calculates exact sub-second fractional linear boundary interpolation`() {
        // Runner runs at constant 8 m/s with 3-second smart recording intervals
        // t = [0, 3, 6, 9, 12, ..., 150]
        // d = [0, 24, 48, 72, ..., 1200]
        val times = IntArray(51) { it * 3 }
        val distances = Array<Float?>(51) { (it * 24.0f) }

        val telemetry =
            FitTelemetry(
                t = times,
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOfNulls(51),
                distance = distances,
            )

        val efforts = detector.detectBestEfforts(telemetry, ActivitySport.RUN)
        val effort400m = efforts.find { it.benchmarkDistance == BenchmarkDistance.FOUR_HUNDRED_METERS }
        kotlin.test.assertNotNull(effort400m)

        // At 8 m/s, 400m takes exactly 400.0 / 8.0 = 50.0 seconds
        assertEquals(50.0, effort400m.elapsedTimeSeconds, 0.001)
        assertEquals(400.0, effort400m.endDistanceMeters - effort400m.startDistanceMeters, 0.001)
        // 400m ends at 400m: 400 / 24 = 16.6667 (between sample 16 at 384m and sample 17 at 408m)
        assertEquals(0, effort400m.startIndex)
        assertEquals(17, effort400m.endIndex)
        assertEquals(0.0, effort400m.startTimeSeconds)
        assertEquals(50.0, effort400m.endTimeSeconds, 0.001)
    }

    @Test
    fun `counts stopped and paused wall-clock intervals against effort`() {
        // Run with a 30-second stop at 200m:
        // t=0..20: 0m to 200m (10 m/s)
        // t=20..50: 200m (stopped for 30s)
        // t=50..70: 200m to 400m (10 m/s)
        val times = mutableListOf<Int>()
        val distances = mutableListOf<Float?>()

        for (sec in 0..20) {
            times.add(sec)
            distances.add(sec * 10.0f)
        }
        for (sec in 21..50) {
            times.add(sec)
            distances.add(200.0f)
        }
        for (sec in 51..70) {
            times.add(sec)
            distances.add(200.0f + (sec - 50) * 10.0f)
        }

        val telemetry =
            FitTelemetry(
                t = times.toIntArray(),
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOfNulls(times.size),
                distance = distances.toTypedArray(),
            )

        val efforts = detector.detectBestEfforts(telemetry, ActivitySport.RUN)
        val effort400m = efforts.find { it.benchmarkDistance == BenchmarkDistance.FOUR_HUNDRED_METERS }
        kotlin.test.assertNotNull(effort400m)

        // Total wall clock time must be 70s, stopped time is NOT subtracted
        assertEquals(70.0, effort400m.elapsedTimeSeconds, 0.001)
        assertEquals(0.0, effort400m.startTimeSeconds)
        assertEquals(70.0, effort400m.endTimeSeconds, 0.001)
    }

    @Test
    fun `handles non-uniform sample frequencies and GPS gaps with null distance values`() {
        // Telemetry has 10 samples:
        // Index 0: t=0, dist=0m
        // Index 1: t=5, dist=null (GPS dropout)
        // Index 2: t=10, dist=null (GPS dropout)
        // Index 3: t=15, dist=150m (10 m/s average over the 15s)
        // Index 4: t=20, dist=200m
        // Index 5: t=30, dist=300m (10s gap)
        // Index 6: t=40, dist=400m
        // Index 7: t=41, dist=null (dropout)
        // Index 8: t=50, dist=500m
        val t = intArrayOf(0, 5, 10, 15, 20, 30, 40, 41, 50)
        val distance =
            arrayOf(
                0.0f,
                null,
                null,
                150.0f,
                200.0f,
                300.0f,
                400.0f,
                null,
                500.0f,
            )

        val telemetry =
            FitTelemetry(
                t = t,
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOfNulls(t.size),
                distance = distance,
            )

        val efforts = detector.detectBestEfforts(telemetry, ActivitySport.RUN)
        val effort400m = efforts.find { it.benchmarkDistance == BenchmarkDistance.FOUR_HUNDRED_METERS }
        kotlin.test.assertNotNull(effort400m)

        // 0 to 400m took exactly 40.0 seconds (from t=0 to t=40)
        assertEquals(40.0, effort400m.elapsedTimeSeconds, 0.001)
        // Must preserve original indices despite null dropouts!
        assertEquals(0, effort400m.startIndex)
        assertEquals(6, effort400m.endIndex) // Index 6 is t=40, dist=400m
    }

    @Test
    fun `awards all qualifying benchmarks and records single peak effort per benchmark`() {
        // 12,000m run in two halves:
        // First 6k (0..1500s): slow jog at 4 m/s (takes 1500s, 6000m)
        // Second 6k (1500..2500s): faster at 6 m/s (takes 1000s, 6000m)
        // Sampled every 10 seconds from t=0 to t=2500 (251 samples)
        val times = IntArray(251) { it * 10 }
        val distances =
            Array<Float?>(251) { idx ->
                val time = idx * 10
                if (time <= 1500) {
                    (time * 4.0).toFloat()
                } else {
                    (6000.0 + (time - 1500) * 6.0).toFloat()
                }
            }

        val telemetry =
            FitTelemetry(
                t = times,
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOfNulls(251),
                distance = distances,
            )

        val efforts = detector.detectBestEfforts(telemetry, ActivitySport.RUN)
        // 12k run qualifies for 400m, 1k, 1mi, 5k, 10k (5 benchmarks), but NOT Half-Marathon (21.1k)
        assertEquals(5, efforts.size)

        val benchmarkSet = efforts.map { it.benchmarkDistance }.toSet()
        assertEquals(
            setOf(
                BenchmarkDistance.FOUR_HUNDRED_METERS,
                BenchmarkDistance.ONE_KILOMETER,
                BenchmarkDistance.ONE_MILE,
                BenchmarkDistance.FIVE_KILOMETERS,
                BenchmarkDistance.TEN_KILOMETERS,
            ),
            benchmarkSet,
        )

        // Peak 5k must come from the faster second half (at 6 m/s, 5000 / 6 = 833.333s)
        val effort5k = efforts.first { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
        assertEquals(5000.0 / 6.0, effort5k.elapsedTimeSeconds, 0.01)
        assertTrue(effort5k.startTimeSeconds >= 1500.0, "5k best effort should be in the faster second half")
    }
}
