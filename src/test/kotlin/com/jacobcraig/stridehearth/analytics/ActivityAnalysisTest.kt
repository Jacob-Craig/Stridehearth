package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.athlete.ThresholdsOn
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityAnalysisTest {
    /** An outdoor 1 Hz Activity at 3 m/s climbing 5 percent. */
    private val climb =
        FitTelemetry(
            t = IntArray(120) { it },
            lons = DoubleArray(120) { -1.5 },
            lats = DoubleArray(120) { 53.0 + it * 3.0 / 111_195.0 },
            routeIndex = Array(120) { it },
            speed = Array(120) { 3.0f },
            distance = Array(120) { it * 3.0f },
            altitude = Array(120) { 100.0f + 0.05f * it * 3.0f },
        )

    @Test
    fun `cleans the Activity Details for the sport and computes GAP from the cleaned series`() {
        val analysis = ActivityAnalysis.analyse(climb, ActivitySport.RUN)

        val cleaned = TelemetryCleaner.clean(climb, ActivitySport.RUN)
        assertEquals(cleaned, analysis.cleaned)
        assertContentEquals(GradeAdjustedPace.gapSpeed(cleaned), assertNotNull(analysis.gapSpeed))
    }

    @Test
    fun `a Run gets Run metrics from its cleaned series and GAP stream`() {
        val analysis = ActivityAnalysis.analyse(climb, ActivitySport.RUN)

        assertEquals(RunMetrics.of(analysis.cleaned, analysis.gapSpeed), analysis.runMetrics)
        assertEquals(120, assertNotNull(analysis.runMetrics).movingSeconds)
    }

    @ParameterizedTest
    @EnumSource(ActivitySport::class, names = ["RUN"], mode = EnumSource.Mode.EXCLUDE)
    fun `only Runs get Run metrics`(sport: ActivitySport) {
        assertNull(ActivityAnalysis.analyse(climb, sport).runMetrics)
    }

    @ParameterizedTest
    @EnumSource(ActivitySport::class, names = ["RIDE", "SWIM", "WORKOUT"])
    fun `a sport outside the GAP sports has cleaned details but no GAP stream`(sport: ActivitySport) {
        val analysis = ActivityAnalysis.analyse(climb, sport)

        assertEquals(TelemetryCleaner.clean(climb, sport), analysis.cleaned)
        assertNull(analysis.gapSpeed)
    }

    /**
     * A flat 1 Hz run at 3 m/s whose GPS teleports 300 m in the one second ending at t = 60: distance jumps by 303 m
     * and the recorded speed reads 40 m/s there.
     */
    private fun teleportingRun(n: Int = 120): FitTelemetry {
        val distance = Array<Float?>(n) { if (it < 60) it * 3.0f else it * 3.0f + 300.0f }
        return FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + distance[it]!! / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { if (it == 60) 40.0f else 3.0f },
            distance = distance,
            altitude = Array(n) { 30.0f },
        )
    }

    @Test
    fun `a GPS spike cannot produce a Peak Effort, because peaks read the cleaned GAP stream`() {
        val details = teleportingRun()

        val peaks = ActivityAnalysis.analyse(details, ActivitySport.RUN).peakEfforts
        val fiveSeconds = peaks.single { it.metric == PeakEffortMetric.GAP_SPEED && it.benchmarkDuration == BenchmarkDuration.FIVE_SECONDS }

        assertEquals(3.0, fiveSeconds.value, 0.01)
        assertTrue(fiveSeconds.value < 8.5)
        // The same window over the raw speed would hold the spike: (4 x 3 + 40) / 5 = 10.4 m/s.
        val raw = PeakEffortDetector.detect(details.t, details.speed, PeakEffortMetric.GAP_SPEED)
        assertEquals(10.4, raw.single { it.benchmarkDuration == BenchmarkDuration.FIVE_SECONDS }.value, 1e-6)
    }

    @Test
    fun `a Run with power gets power Peak Efforts in watts from the recorded power`() {
        val withPower = climb.copy(power = Array(120) { 250.toShort() })

        val peaks = ActivityAnalysis.analyse(withPower, ActivitySport.RUN).peakEfforts

        val power = peaks.filter { it.metric == PeakEffortMetric.POWER }
        assertEquals(listOf(5, 15, 30, 60), power.map { it.benchmarkDuration.seconds })
        power.forEach { assertEquals(250.0, it.value, 1e-9) }
        assertTrue(peaks.any { it.metric == PeakEffortMetric.GAP_SPEED })
    }

    @Test
    fun `a power sample above the plausibility ceiling counts as missing, so a glitch cannot set a power peak`() {
        val glitch = Array<Short?>(120) { if (it == 60) 2000.toShort() else 300.toShort() }

        val power =
            ActivityAnalysis
                .analyse(climb.copy(power = glitch), ActivitySport.RUN)
                .peakEfforts
                .filter { it.metric == PeakEffortMetric.POWER }

        // As recorded, the 5 s window holding the glitch would average (2000 + 4 x 300) / 5 = 640 W. Read as
        // missing (zero), the glitch can only lower a window: the shorter peaks avoid it and stay at 300 W, and
        // every 60 s window of the 119 s stream holds it, so that peak is 59 x 300 / 60.
        assertTrue(ActivityAnalysis.MAX_RUN_POWER_WATTS < 2000)
        val byDuration = power.associate { it.benchmarkDuration to it.value }
        assertEquals(300.0, byDuration.getValue(BenchmarkDuration.FIVE_SECONDS), 1e-9)
        assertEquals(300.0, byDuration.getValue(BenchmarkDuration.FIFTEEN_SECONDS), 1e-9)
        assertEquals(300.0, byDuration.getValue(BenchmarkDuration.THIRTY_SECONDS), 1e-9)
        assertEquals(295.0, byDuration.getValue(BenchmarkDuration.ONE_MINUTE), 1e-9)
    }

    @Test
    fun `power at the plausibility ceiling is kept`() {
        val ceiling = ActivityAnalysis.MAX_RUN_POWER_WATTS.toShort()
        val power =
            ActivityAnalysis
                .analyse(climb.copy(power = Array(120) { ceiling }), ActivitySport.RUN)
                .peakEfforts
                .filter { it.metric == PeakEffortMetric.POWER }

        power.forEach { assertEquals(ActivityAnalysis.MAX_RUN_POWER_WATTS.toDouble(), it.value, 1e-9) }
    }

    @Test
    fun `a Run without power gets GAP Peak Efforts only`() {
        val peaks = ActivityAnalysis.analyse(climb.copy(power = arrayOfNulls(120)), ActivitySport.RUN).peakEfforts

        assertTrue(peaks.isNotEmpty())
        assertTrue(peaks.all { it.metric == PeakEffortMetric.GAP_SPEED })
    }

    @ParameterizedTest
    @EnumSource(ActivitySport::class, names = ["RUN"], mode = EnumSource.Mode.EXCLUDE)
    fun `only Runs get Peak Efforts`(sport: ActivitySport) {
        assertTrue(ActivityAnalysis.analyse(climb.copy(power = Array(120) { 250.toShort() }), sport).peakEfforts.isEmpty())
    }

    /** An hour's flat 1 Hz Run (3601 samples, so 3600 recorded seconds) at 300 W, with [power] overriding samples. */
    private fun hourAtCriticalPower(power: (Int) -> Short? = { 300 }): FitTelemetry =
        FitTelemetry(
            t = IntArray(3601) { it },
            lons = DoubleArray(3601) { -1.5 },
            lats = DoubleArray(3601) { 53.0 + it * 3.0 / 111_195.0 },
            routeIndex = Array(3601) { it },
            speed = Array(3601) { 3.0f },
            distance = Array(3601) { it * 3.0f },
            altitude = Array(3601) { 100.0f },
            power = Array(3601) { power(it) },
        )

    private fun rss(details: FitTelemetry): Double {
        val load =
            ActivityAnalysis
                .analyse(details, ActivitySport.RUN, ThresholdsOn.NONE.copy(criticalPowerWatts = 300.0))
                .trainingLoad
        val scored = assertIs<TrainingLoadResult.Scored>(load)
        assertEquals(TrainingLoadMethod.RSS, scored.method)
        return scored.value
    }

    @Test
    fun `a power glitch above the plausibility ceiling counts as missing in the Training Load too`() {
        // As recorded, a 5000 W sample lifts ten trailing 10 s means to 770 W, and (770 / 300)^3.5 = 27, so each of
        // those ten seconds scores 27 times its share: about 7 on the hour's 100. Read as missing, it only drops one
        // of the 3600 seconds.
        val load = rss(hourAtCriticalPower { if (it == 1800) 5000.toShort() else 300 })

        assertEquals(100.0, load, 0.1)
    }

    @Test
    fun `a power sample that wrapped negative counts as missing, so the Training Load stays finite`() {
        // A uint16 of 40000 W stored through toShort() reads as -25536 W: as recorded, every 10 s mean holding it is
        // negative and (negative)^3.5 is NaN.
        val load = rss(hourAtCriticalPower { if (it == 1800) 40000.toShort() else 300 })

        assertTrue(load.isFinite())
        assertEquals(100.0, load, 0.1)
    }
}
