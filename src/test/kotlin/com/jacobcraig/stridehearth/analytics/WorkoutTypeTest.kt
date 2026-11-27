package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.athlete.ThresholdsOn
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **Workout Type** rules (#48): [WorkoutTypeClassifier] on hand-made evidence, and [WorkoutEvidence] on 1 Hz fixture
 * runs. Expected values come from how each fixture was built.
 */
class WorkoutTypeTest {
    private fun evidence(
        workIntervals: Int = 0,
        tempoSeconds: Int = 0,
        easyShare: Double? = 1.0,
        raceIntensity: Double? = 0.7,
        distanceMeters: Double? = 8_000.0,
    ) = WorkoutEvidence(workIntervals, tempoSeconds, easyShare, raceIntensity, distanceMeters)

    private fun classify(
        evidence: WorkoutEvidence,
        movingSeconds: Int = 2400,
        median: Double? = null,
        medianRuns: Int = 0,
    ) = WorkoutTypeClassifier.classify(evidence, movingSeconds, median, medianRuns)

    @Test
    fun `a run that meets no rule is Easy`() {
        assertEquals(WorkoutType.EASY, classify(evidence()))
    }

    @Test
    fun `three work intervals at threshold make Intervals and two do not`() {
        assertEquals(WorkoutType.INTERVALS, classify(evidence(workIntervals = 3)))
        assertEquals(WorkoutType.EASY, classify(evidence(workIntervals = 2)))
    }

    @Test
    fun `a progression run with one work interval is not Intervals`() {
        assertEquals(WorkoutType.EASY, classify(evidence(workIntervals = 1)))
    }

    @Test
    fun `fifteen minutes in Z3 to Z4 make a Tempo and one second less does not`() {
        assertEquals(WorkoutType.TEMPO, classify(evidence(tempoSeconds = 900, easyShare = 0.3)))
        assertEquals(WorkoutType.EASY, classify(evidence(tempoSeconds = 899, easyShare = 0.3)))
    }

    @Test
    fun `ninety minutes at mostly easy intensity make a Long Run and one second less does not`() {
        assertEquals(WorkoutType.LONG_RUN, classify(evidence(easyShare = 0.9), movingSeconds = 5400))
        assertEquals(WorkoutType.EASY, classify(evidence(easyShare = 0.9), movingSeconds = 5399))
    }

    @Test
    fun `one and a half times the 28-day median make a Long Run`() {
        assertEquals(WorkoutType.LONG_RUN, classify(evidence(), movingSeconds = 3600, median = 2400.0, medianRuns = 4))
        assertEquals(WorkoutType.EASY, classify(evidence(), movingSeconds = 3599, median = 2400.0, medianRuns = 4))
    }

    @Test
    fun `a median of too few runs does not make a Long Run`() {
        assertEquals(WorkoutType.EASY, classify(evidence(), movingSeconds = 3600, median = 2400.0, medianRuns = 2))
        assertEquals(WorkoutType.EASY, classify(evidence(), movingSeconds = 3600, median = null, medianRuns = 0))
    }

    @Test
    fun `a long run that is not mostly easy is not a Long Run`() {
        val hard = evidence(tempoSeconds = 3000, easyShare = 0.5)

        assertEquals(WorkoutType.TEMPO, classify(hard, movingSeconds = 6000))
    }

    @Test
    fun `a long run without zones counts as easy`() {
        assertEquals(WorkoutType.LONG_RUN, classify(evidence(easyShare = null), movingSeconds = 6000))
    }

    @Test
    fun `a 5K at threshold intensity or above is a Race`() {
        assertEquals(WorkoutType.RACE, classify(evidence(distanceMeters = 5_020.0, raceIntensity = 1.02)))
        assertEquals(WorkoutType.EASY, classify(evidence(distanceMeters = 5_020.0, raceIntensity = 0.9)))
    }

    @Test
    fun `each race distance has its own least intensity`() {
        assertEquals(WorkoutType.RACE, classify(evidence(distanceMeters = 10_000.0, raceIntensity = 0.97)))
        assertEquals(WorkoutType.EASY, classify(evidence(distanceMeters = 10_000.0, raceIntensity = 0.96)))
        assertEquals(WorkoutType.RACE, classify(evidence(distanceMeters = 21_300.0, raceIntensity = 0.93)))
        assertEquals(WorkoutType.RACE, classify(evidence(distanceMeters = 42_000.0, raceIntensity = 0.87), movingSeconds = 12_000))
    }

    @Test
    fun `a fast run that is not near a race distance is not a Race`() {
        assertEquals(WorkoutType.EASY, classify(evidence(distanceMeters = 7_000.0, raceIntensity = 1.1)))
        // 6% over 5K: outside the 5% tolerance.
        assertEquals(WorkoutType.EASY, classify(evidence(distanceMeters = 5_300.0, raceIntensity = 1.1)))
    }

    @Test
    fun `a run without a distance or an intensity is never a Race`() {
        assertEquals(WorkoutType.EASY, classify(evidence(distanceMeters = null, raceIntensity = 1.1)))
        assertEquals(WorkoutType.EASY, classify(evidence(distanceMeters = 5_000.0, raceIntensity = null)))
    }

    @Test
    fun `rules apply in order, Race then Intervals then Long Run then Tempo`() {
        val everything = evidence(workIntervals = 4, tempoSeconds = 1200, easyShare = 0.8, raceIntensity = 1.05, distanceMeters = 5_000.0)

        assertEquals(WorkoutType.RACE, classify(everything, movingSeconds = 6000))
        assertEquals(WorkoutType.INTERVALS, classify(everything.copy(distanceMeters = 6_500.0), movingSeconds = 6000))
        assertEquals(
            WorkoutType.LONG_RUN,
            classify(everything.copy(distanceMeters = 6_500.0, thresholdWorkIntervals = 2), movingSeconds = 6000),
        )
        assertEquals(
            WorkoutType.TEMPO,
            classify(everything.copy(distanceMeters = 6_500.0, thresholdWorkIntervals = 2), movingSeconds = 3000),
        )
    }

    @Test
    fun `a stored type name reads back, and an unknown one reads as none`() {
        assertEquals(WorkoutType.LONG_RUN, WorkoutType.fromStored("LONG_RUN"))
        assertNull(WorkoutType.fromStored("FARTLEK"))
        assertNull(WorkoutType.fromStored(null))
    }

    // Evidence from fixture runs.

    private fun cleaned(
        seconds: Int,
        speed: (Int) -> Double,
        hr: (Int) -> Int? = { null },
    ): Pair<CleanedTelemetry, Array<Float?>> {
        var travelled = 0.0
        val distance = Array<Float?>(seconds) { s -> travelled.toFloat().also { travelled += speed(s) } }
        val details =
            CleanedTelemetry(
                t = IntArray(seconds) { it },
                speed = Array(seconds) { speed(it).toFloat() },
                distance = distance,
                heartRate = Array(seconds) { hr(it)?.toShort() },
                altitude = arrayOfNulls(seconds),
                hasGps = true,
            )
        return details to Array(seconds) { speed(it).toFloat() }
    }

    private fun evidenceOf(
        seconds: Int,
        thresholds: ThresholdsOn,
        speed: (Int) -> Double,
        hr: (Int) -> Int? = { null },
    ): WorkoutEvidence {
        val (details, gap) = cleaned(seconds, speed, hr)
        val intervals = IntervalDetector.detect(details, gap, arrayOfNulls(seconds))
        return WorkoutEvidence.of(details, gap, arrayOfNulls(seconds), intervals, thresholds)
    }

    private val paceThresholds =
        ThresholdsOn(maxHr = null, restingHr = null, lthr = null, thresholdSpeedMps = 4.0, criticalPowerWatts = null)

    /** 10 min at 3.0 m/s, six 3 min reps at 4.45 m/s with 2 min jogs at 2.4 m/s, 10 min at 3.0 m/s. */
    private fun reps(s: Int): Double {
        val into = s - 600
        if (into < 0 || into >= 6 * 300) return 3.0
        return if (into % 300 < 180) 4.45 else 2.4
    }

    private val repsSeconds = 600 + 6 * 300 + 600

    @Test
    fun `reps at threshold pace count as work at threshold, and reps below it do not`() {
        assertEquals(6, evidenceOf(repsSeconds, paceThresholds, ::reps).thresholdWorkIntervals)
        // Against a threshold pace of 5 m/s the same reps are not at threshold: Z4 starts at 4.72 m/s.
        assertEquals(0, evidenceOf(repsSeconds, paceThresholds.copy(thresholdSpeedMps = 5.0), ::reps).thresholdWorkIntervals)
    }

    @Test
    fun `without any threshold no work interval counts and no zone is known`() {
        val evidence = evidenceOf(repsSeconds, ThresholdsOn.NONE, ::reps)

        assertEquals(0, evidence.thresholdWorkIntervals)
        assertEquals(0, evidence.tempoSeconds)
        assertNull(evidence.easyShare)
        assertNull(evidence.raceIntensity)
    }

    @Test
    fun `heart-rate threshold judges the reps when there is no threshold pace`() {
        val thresholds = ThresholdsOn(maxHr = 190.0, restingHr = null, lthr = 165.0, thresholdSpeedMps = null, criticalPowerWatts = null)
        val workHr = { s: Int -> if (s >= 600 && s < 600 + 6 * 300 && (s - 600) % 300 < 180) 165 else 130 }

        assertEquals(6, evidenceOf(repsSeconds, thresholds, ::reps, workHr).thresholdWorkIntervals)
    }

    @Test
    fun `twenty minutes in the threshold zone are tempo seconds, read from pace`() {
        // 3.9 m/s against a 4.0 m/s threshold is Friel Z4 (3.77 to 4.04 m/s): Seiler zone 2.
        val evidence = evidenceOf(1200, paceThresholds, { 3.9 })

        assertEquals(1200.0, evidence.tempoSeconds.toDouble(), 2.0)
        assertEquals(0.0, assertNotNull(evidence.easyShare), 1e-9)
    }

    @Test
    fun `an easy jog is all easy seconds with no tempo`() {
        val evidence = evidenceOf(1800, paceThresholds, { 2.8 })

        assertEquals(0, evidence.tempoSeconds)
        assertEquals(1.0, assertNotNull(evidence.easyShare), 1e-9)
    }

    @Test
    fun `heart rate is preferred to pace for the zones when it covers the run`() {
        val thresholds = ThresholdsOn(maxHr = 190.0, restingHr = null, lthr = 160.0, thresholdSpeedMps = 4.0, criticalPowerWatts = null)

        // Pace 2.8 m/s says easy, but 150 bpm is Z3 (144 to 152 bpm) of an LTHR of 160: tempo.
        val evidence = evidenceOf(1200, thresholds, { 2.8 }, { 150 })

        assertEquals(1200.0, evidence.tempoSeconds.toDouble(), 2.0)
    }

    @Test
    fun `race intensity is average pace over threshold pace and the distance is the cleaned distance`() {
        val evidence = evidenceOf(1500, paceThresholds, { 4.2 })

        assertEquals(1.05, assertNotNull(evidence.raceIntensity), 0.01)
        assertEquals(1499 * 4.2, assertNotNull(evidence.distanceMeters), 10.0)
    }

    @Test
    fun `a progression run is not Intervals even against a large pace gap`() {
        // 25 min at 2.8 m/s then 25 min at 4.2 m/s against a 4.0 m/s threshold.
        val evidence = evidenceOf(3000, paceThresholds, { if (it < 1500) 2.8 else 4.2 })

        assertTrue(evidence.thresholdWorkIntervals <= 1)
        assertNotEquals(WorkoutType.INTERVALS, WorkoutTypeClassifier.classify(evidence, 3000))
    }
}
