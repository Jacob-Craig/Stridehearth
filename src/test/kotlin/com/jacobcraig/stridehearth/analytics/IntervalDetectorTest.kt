package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.api.Test
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Detected Interval** detection on hand-built 1 Hz runs. The expected reps come from how each fixture was built (the
 * second a rep starts and ends), not from the detector.
 */
class IntervalDetectorTest {
    /** A 1 Hz run whose speed (m/s, also its GAP) and optional power and heart rate are given by second. */
    private fun detect(
        seconds: Int,
        speed: (Int) -> Double?,
        power: ((Int) -> Int?)? = null,
        hr: (Int) -> Int? = { 150 },
    ): List<DetectedInterval> {
        var travelled = 0.0
        val distance = Array<Float?>(seconds) { s -> travelled.toFloat().also { travelled += speed(s) ?: 0.0 } }
        val cleaned =
            CleanedTelemetry(
                t = IntArray(seconds) { it },
                speed = Array(seconds) { speed(it)?.toFloat() },
                distance = distance,
                heartRate = Array(seconds) { hr(it)?.toShort() },
                altitude = arrayOfNulls(seconds),
                hasGps = true,
            )
        val gap = Array(seconds) { speed(it)?.toFloat() }
        val watts = Array(seconds) { s -> power?.invoke(s)?.toShort() }
        return IntervalDetector.detect(cleaned, gap, watts)
    }

    private val List<DetectedInterval>.work get() = filter { it.isWork }

    /** 10 min warm-up at 3.0 m/s, six 800 m reps (180 s at 4.45 m/s) with 120 s jogs at 2.4 m/s, 10 min cool-down. */
    private fun repsSpeed(s: Int): Double {
        val into = s - 600
        if (into < 0 || into >= 6 * 300) return 3.0
        return if (into % 300 < 180) 4.45 else 2.4
    }

    private val repsSeconds = 600 + 6 * 300 + 600

    @Test
    fun `six 800 m reps with jog recoveries give six work intervals at the built times`() {
        val work = detect(repsSeconds, ::repsSpeed).work

        assertEquals(6, work.size)
        work.forEachIndexed { i, rep ->
            val builtStart = 600.0 + i * 300
            assertEquals(builtStart, rep.startTimeSeconds, 4.0, "rep ${i + 1} start")
            assertEquals(builtStart + 180, rep.endTimeSeconds, 4.0, "rep ${i + 1} end")
            assertEquals(4.45, rep.avgGap, 0.05)
            assertEquals(150.0, assertNotNull(rep.avgHr), 1e-9)
            assertNull(rep.avgPower)
            // 800 m is 180 s at 4.45 m/s: 801 m.
            assertEquals(801.0, assertNotNull(rep.distanceMeters), 40.0)
        }
    }

    @Test
    fun `the intervals tile the run in order, alternating work and recovery`() {
        val intervals = detect(repsSeconds, ::repsSpeed)

        assertTrue(intervals.zipWithNext().all { (a, b) -> a.isWork != b.isWork }, "work and recovery alternate")
        assertTrue(intervals.zipWithNext().all { (a, b) -> a.endTimeSeconds == b.startTimeSeconds })
        assertTrue(intervals.zipWithNext().all { (a, b) -> a.endIndex <= b.startIndex })
        assertFalse(intervals.first().isWork, "the warm-up is a recovery")
        assertEquals(0, intervals.first().startIndex)
        // Warm-up, five jogs and the cool-down (the sixth jog is the cool-down's start) around six reps.
        assertEquals(7, intervals.count { !it.isWork })
    }

    @Test
    fun `a steady run has no Detected Intervals`() {
        // Gentle drift and noise, well under the contrast between work and recovery.
        val intervals = detect(3600, { s -> 3.0 + 0.1 * sin(s / 40.0) + if (s % 7 == 0) 0.05 else 0.0 })

        assertTrue(intervals.isEmpty())
    }

    @Test
    fun `a steady run with a slow warm-up is not split into intervals`() {
        // 5 min at 2.8 m/s then 3.2 m/s: a 12% step, below the contrast a work interval needs.
        assertTrue(detect(2400, { if (it < 300) 2.8 else 3.2 }).isEmpty())
    }

    @Test
    fun `a fartlek of unequal surges gives one work interval per surge`() {
        // 8 min easy, then surges of 60, 120 and 45 s at 4.3 m/s separated by 150 s at 2.9 m/s, then 8 min easy.
        val surges = listOf(480 to 540, 690 to 810, 960 to 1005)

        val work = detect(1005 + 480, { s -> if (surges.any { s >= it.first && s < it.second }) 4.3 else 2.9 }).work

        assertEquals(3, work.size)
        surges.zip(work).forEach { (built, found) ->
            assertEquals(built.first.toDouble(), found.startTimeSeconds, 4.0)
            assertEquals(built.second.toDouble(), found.endTimeSeconds, 4.0)
        }
    }

    @Test
    fun `traffic stops in a steady run do not create intervals`() {
        // Four 40 s stops at 0 m/s, each braked into and accelerated out of over 5 s.
        val stops = listOf(300, 800, 1300, 1800)

        val intervals =
            detect(2400, { s ->
                val stop = stops.firstOrNull { s >= it - 5 && s < it + 45 }
                when {
                    stop == null -> 3.0
                    s < stop -> 3.0 * (stop - s) / 5.0
                    s < stop + 40 -> 0.0
                    else -> 3.0 * (s - (stop + 40)) / 5.0
                }
            })

        assertTrue(intervals.isEmpty())
    }

    @Test
    fun `a traffic stop inside a rep does not split the rep or add a recovery`() {
        // The first rep of the fixture gets a 30 s stop in its middle; the stop is not Moving Time.
        val stopStart = 600 + 90

        val intervals =
            detect(repsSeconds + 30, { s ->
                when {
                    s < stopStart -> repsSpeed(s)
                    s < stopStart + 30 -> 0.0
                    else -> repsSpeed(s - 30)
                }
            })

        assertEquals(6, intervals.work.size)
        assertEquals(7, intervals.count { !it.isWork })
        val firstRep = intervals.work.first()
        assertEquals(180.0, firstRep.movingSeconds.toDouble(), 4.0)
        assertEquals(210.0, firstRep.endTimeSeconds - firstRep.startTimeSeconds, 4.0, "its span includes the stop")
    }

    @Test
    fun `power drives detection when most of the run has it, and each interval reports its average`() {
        // Speed is flat, so only power can see the reps: 300 W for 180 s, 150 W jogging, 200 W warm-up and cool-down.
        val intervals =
            detect(repsSeconds, { 3.0 }, power = { s ->
                val into = s - 600
                when {
                    into < 0 || into >= 6 * 300 -> 200
                    into % 300 < 180 -> 300
                    else -> 150
                }
            })

        assertEquals(6, intervals.work.size)
        intervals.work.forEach { assertEquals(300.0, assertNotNull(it.avgPower), 1e-9) }
    }

    @Test
    fun `a power meter that is mostly missing leaves detection to GAP speed`() {
        val intervals = detect(repsSeconds, ::repsSpeed, power = { s -> if (s < 100) 200 else null })

        assertEquals(6, intervals.work.size)
    }

    @Test
    fun `a work interval whose heart rate climbs at the same pace has positive decoupling`() {
        // Each rep's heart rate climbs from 160 to 180: the second half has the higher heart rate for the same pace.
        val intervals =
            detect(repsSeconds, ::repsSpeed, hr = { s ->
                val into = s - 600
                if (into in 0 until 6 * 300 && into % 300 < 180) 160 + 20 * (into % 300) / 180 else 130
            })

        assertTrue(assertNotNull(intervals.work.first().decouplingPct) > 5.0)
        assertNull(intervals.first { !it.isWork }.decouplingPct, "only work intervals report decoupling")
    }

    @Test
    fun `a run too short or too still to analyse has no intervals`() {
        assertTrue(detect(120, { if (it < 60) 2.0 else 4.5 }).isEmpty())
        assertTrue(detect(1800, { 0.0 }).isEmpty())
    }

    @Test
    fun `heart rate that is missing leaves avgHr null`() {
        val intervals = detect(repsSeconds, ::repsSpeed, hr = { null })

        assertEquals(6, intervals.work.size)
        intervals.forEach { assertNull(it.avgHr) }
    }

    @Test
    fun `a stop right after a rep does not blur the rep's end`() {
        // 12 min at 3.0 m/s, a 6 min rep at 4.5 m/s, a 30 s stop, then 12 min of jogging at 2.4 m/s.
        val repEnd = 720 + 360
        val intervals =
            detect(repEnd + 30 + 720, { s ->
                when {
                    s < 720 -> 3.0
                    s < repEnd -> 4.5
                    s < repEnd + 30 -> 0.0
                    else -> 2.4
                }
            })

        val rep = intervals.work.single()
        assertEquals(720.0, rep.startTimeSeconds, 1.0)
        // The smoothing stops at the stop, so the edge is not pulled up to 7 s into the jog (#102).
        assertEquals(repEnd.toDouble(), rep.endTimeSeconds, 1.0)
    }

    @Test
    fun `a progression run with a large pace gap has at most one work interval`() {
        // 25 min easy at 2.8 m/s, then a 25 min fast finish at 4.2 m/s: a 33% gap, but one step, not repeated reps (#102).
        val intervals = detect(3000, { if (it < 1500) 2.8 else 4.2 })

        assertTrue(intervals.work.size <= 1)
    }

    @Test
    fun `a negative-split run whose second half is faster has at most one work interval`() {
        val intervals = detect(3600, { if (it < 1800) 3.0 else 4.0 })

        assertTrue(intervals.work.size <= 1)
    }
}
