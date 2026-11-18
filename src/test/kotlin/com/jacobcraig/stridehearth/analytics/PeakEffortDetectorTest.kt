package com.jacobcraig.stridehearth.analytics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Peak Effort** detection on hand-built streams. Every expected value below is worked out by hand from the
 * cumulative series `C` of the rate (`C[i] = C[i-1] + x[i] x dt`), not read back from the detector.
 */
class PeakEffortDetectorTest {
    private fun List<PeakEffort>.of(duration: BenchmarkDuration): PeakEffort? = singleOrNull { it.benchmarkDuration == duration }

    private fun List<PeakEffort>.valueOf(duration: BenchmarkDuration): Double? = of(duration)?.value

    /** 1 Hz from t = 0 to t = 600 (601 samples) at 3.0 m/s, except samples 101 to 160 at 5.0 m/s. */
    private val surge: Array<Float?> = Array(601) { if (it in 101..160) 5.0f else 3.0f }
    private val oneHertz601 = IntArray(601) { it }

    @Test
    fun `a 60 s surge inside a steady run gives the hand-computed peaks`() {
        val peaks = PeakEffortDetector.detect(oneHertz601, surge, PeakEffortMetric.GAP_SPEED)

        // Any 5 s or 60 s window inside samples 101..160 averages 5.0.
        assertEquals(5.0, peaks.valueOf(BenchmarkDuration.FIVE_SECONDS)!!, 1e-9)
        assertEquals(5.0, peaks.valueOf(BenchmarkDuration.ONE_MINUTE)!!, 1e-9)
        // (60 x 5 + 60 x 3) / 120
        assertEquals(4.0, peaks.valueOf(BenchmarkDuration.TWO_MINUTES)!!, 1e-9)
        // C[600] = 60 x 5 + 540 x 3 = 1920 m over 600 s.
        assertEquals(3.2, peaks.valueOf(BenchmarkDuration.TEN_MINUTES)!!, 1e-9)
        // The stream spans 600 s, so nothing longer has a window.
        assertNull(peaks.of(BenchmarkDuration.TWENTY_MINUTES))
        assertNull(peaks.of(BenchmarkDuration.SIXTY_MINUTES))
        assertTrue(peaks.all { it.metric == PeakEffortMetric.GAP_SPEED })
    }

    @Test
    fun `the 60 s peak's window is the surge itself`() {
        val peak = PeakEffortDetector.detect(oneHertz601, surge, PeakEffortMetric.GAP_SPEED).of(BenchmarkDuration.ONE_MINUTE)!!

        // The step into sample 101 starts at t = 100, so the window is (100, 160].
        assertEquals(100.0, peak.startTimeSeconds, 1e-9)
        assertEquals(160.0, peak.endTimeSeconds, 1e-9)
        assertEquals(100, peak.startIndex)
        assertEquals(160, peak.endIndex)
    }

    @Test
    fun `a window end between two samples is interpolated`() {
        // One 7 s step at 4 m/s: C(7) = 28, linear in between, so C(5) = 20 and the 5 s peak is 20 / 5.
        val peaks = PeakEffortDetector.detect(intArrayOf(0, 7), arrayOf(null, 4.0f), PeakEffortMetric.GAP_SPEED)

        assertEquals(4.0, peaks.valueOf(BenchmarkDuration.FIVE_SECONDS)!!, 1e-9)
        assertEquals(1, peaks.size)
    }

    @Test
    fun `a window start between two samples is interpolated`() {
        // Steps: (0,10] at 2 m/s, (10,12] at 6 m/s. Best 5 s window ends at 12: C(12) - C(7) = (20 + 12) - 14 = 18.
        val peaks = PeakEffortDetector.detect(intArrayOf(0, 10, 12), arrayOf(null, 2.0f, 6.0f), PeakEffortMetric.GAP_SPEED)

        val peak = peaks.of(BenchmarkDuration.FIVE_SECONDS)!!
        assertEquals(18.0 / 5, peak.value, 1e-9)
        assertEquals(7.0, peak.startTimeSeconds, 1e-9)
        assertEquals(12.0, peak.endTimeSeconds, 1e-9)
    }

    @Test
    fun `a recording pause counts as standing still`() {
        // 4 m/s from t = 0 to 100, a 60 s recording gap, then 4 m/s from t = 160 to 260.
        val t = IntArray(101) { it } + IntArray(101) { 160 + it }
        val rate = Array<Float?>(t.size) { 4.0f }

        val peaks = PeakEffortDetector.detect(t, rate, PeakEffortMetric.GAP_SPEED)

        assertEquals(4.0, peaks.valueOf(BenchmarkDuration.ONE_MINUTE)!!, 1e-9)
        // Every 120 s window holds at most 100 s of running: 400 m / 120 s.
        assertEquals(400.0 / 120, peaks.valueOf(BenchmarkDuration.TWO_MINUTES)!!, 1e-9)
        // A 180 s window holds 120 s of running wherever it straddles the gap ([0, 180] or [80, 260]): 480 m.
        assertEquals(480.0 / 180, peaks.valueOf(BenchmarkDuration.THREE_MINUTES)!!, 1e-9)
    }

    @Test
    fun `a step of exactly the pause limit still counts, one longer does not`() {
        // Steps of 10 s and 11 s at 3 m/s: the 10 s step adds 30 m, the 11 s step is a pause.
        val counted = PeakEffortDetector.detect(intArrayOf(0, 10), arrayOf(null, 3.0f), PeakEffortMetric.GAP_SPEED)
        val paused = PeakEffortDetector.detect(intArrayOf(0, 11), arrayOf(null, 3.0f), PeakEffortMetric.GAP_SPEED)

        assertEquals(3.0, counted.valueOf(BenchmarkDuration.FIVE_SECONDS)!!, 1e-9)
        assertTrue(paused.isEmpty(), "a window over a pause alone averages 0 and is not stored")
    }

    @Test
    fun `a null sample counts as zero`() {
        // 1 Hz t = 0..300 at 3 m/s with sample 150 missing: C[300] = 299 x 3.
        val rate = Array<Float?>(301) { if (it == 150) null else 3.0f }

        val peaks = PeakEffortDetector.detect(IntArray(301) { it }, rate, PeakEffortMetric.GAP_SPEED)

        assertEquals(897.0 / 300, peaks.valueOf(BenchmarkDuration.FIVE_MINUTES)!!, 1e-9)
    }

    @Test
    fun `ties go to the earliest window`() {
        val peak =
            PeakEffortDetector
                .detect(IntArray(61) { it }, Array(61) { 3.0f }, PeakEffortMetric.GAP_SPEED)
                .of(BenchmarkDuration.FIVE_SECONDS)!!

        assertEquals(0.0, peak.startTimeSeconds, 1e-9)
        assertEquals(5.0, peak.endTimeSeconds, 1e-9)
    }

    @Test
    fun `a power stream gives peaks in watts`() {
        // 1 Hz t = 0..120 at 200 W, with 30 s at 350 W (samples 31..60).
        val power = Array<Short?>(121) { if (it in 31..60) 350 else 200 }

        val peaks = PeakEffortDetector.detect(IntArray(121) { it }, power, PeakEffortMetric.POWER)

        assertEquals(350.0, peaks.valueOf(BenchmarkDuration.THIRTY_SECONDS)!!, 1e-9)
        // (30 x 350 + 30 x 200) / 60
        assertEquals(275.0, peaks.valueOf(BenchmarkDuration.ONE_MINUTE)!!, 1e-9)
        // C[120] = 30 x 350 + 90 x 200 = 28 500 J over 120 s.
        assertEquals(237.5, peaks.valueOf(BenchmarkDuration.TWO_MINUTES)!!, 1e-9)
        assertTrue(peaks.all { it.metric == PeakEffortMetric.POWER })
    }

    @Test
    fun `a stream with no positive value, or too short, has no peaks`() {
        assertTrue(PeakEffortDetector.detect(IntArray(61) { it }, arrayOfNulls<Float>(61), PeakEffortMetric.POWER).isEmpty())
        assertTrue(PeakEffortDetector.detect(intArrayOf(0, 4), arrayOf(3.0f, 3.0f), PeakEffortMetric.GAP_SPEED).isEmpty())
        assertTrue(PeakEffortDetector.detect(intArrayOf(), arrayOf<Float?>(), PeakEffortMetric.GAP_SPEED).isEmpty())
    }

    @Test
    fun `a rate stream of another length than the time stream has no peaks`() {
        assertTrue(PeakEffortDetector.detect(IntArray(61) { it }, arrayOf<Short?>(), PeakEffortMetric.POWER).isEmpty())
    }

    /** 1 Hz at a steady 4.0 m/s from t = 0 to t = 600, with [insert] spliced in after the sample at t = 300. */
    private fun steadyWith(insert: List<Int>): Pair<IntArray, Array<Float?>> {
        val t = (0..300).toList() + insert + (301..600).toList()
        return t.toIntArray() to Array<Float?>(t.size) { 4.0f }
    }

    @Test
    fun `a sample stepping back in time is skipped, so it cannot inflate a peak`() {
        val (t, speed) = steadyWith(listOf(299))
        val peaks = PeakEffortDetector.detect(t, speed, PeakEffortMetric.GAP_SPEED)

        // Without the backward sample the stream is a steady 4.0 m/s, so every peak is 4.0.
        assertEquals(8, peaks.size)
        peaks.forEach { assertEquals(4.0, it.value, 1e-9, "${it.benchmarkDuration}") }
    }

    @Test
    fun `a five second backward step is skipped, so it cannot inflate a peak`() {
        val (t, speed) = steadyWith(listOf(295))
        val peaks = PeakEffortDetector.detect(t, speed, PeakEffortMetric.GAP_SPEED)

        peaks.forEach { assertEquals(4.0, it.value, 1e-9, "${it.benchmarkDuration}") }
    }

    @Test
    fun `a duplicate timestamp is skipped, and indices still point into the original stream`() {
        val (t, speed) = steadyWith(listOf(300))
        speed[301] = 50.0f // the duplicate's value must be ignored, as it covers no time
        val peaks = PeakEffortDetector.detect(t, speed, PeakEffortMetric.GAP_SPEED)

        peaks.forEach { assertEquals(4.0, it.value, 1e-9, "${it.benchmarkDuration}") }
        val tenMinutes = peaks.single { it.benchmarkDuration == BenchmarkDuration.TEN_MINUTES }
        assertEquals(0, tenMinutes.startIndex)
        assertEquals(t.size - 1, tenMinutes.endIndex)
        val lateFive =
            PeakEffortDetector
                .detect(t, Array<Float?>(t.size) { if (it >= 400) 6.0f else 4.0f }, PeakEffortMetric.GAP_SPEED)
                .single { it.benchmarkDuration == BenchmarkDuration.FIVE_SECONDS }
        // The surge starts at original index 400 (t = 399); the step into it starts at t = 398, original index 399.
        assertEquals(399, lateFive.startIndex)
        assertEquals(404, lateFive.endIndex)
        assertEquals(t[lateFive.startIndex].toDouble(), lateFive.startTimeSeconds, 1e-9)
        assertEquals(t[lateFive.endIndex].toDouble(), lateFive.endTimeSeconds, 1e-9)
    }

    @Test
    fun `the Benchmark Durations are the eleven standard windows`() {
        assertEquals(
            listOf(5, 15, 30, 60, 120, 180, 300, 600, 1200, 1800, 3600),
            BenchmarkDuration.entries.map { it.seconds },
        )
    }
}
