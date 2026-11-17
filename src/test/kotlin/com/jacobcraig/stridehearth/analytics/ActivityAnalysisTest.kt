package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @ParameterizedTest
    @EnumSource(ActivitySport::class, names = ["RIDE", "SWIM", "WORKOUT"])
    fun `a sport outside the GAP sports has cleaned details but no GAP stream`(sport: ActivitySport) {
        val analysis = ActivityAnalysis.analyse(climb, sport)

        assertEquals(TelemetryCleaner.clean(climb, sport), analysis.cleaned)
        assertNull(analysis.gapSpeed)
    }
}
