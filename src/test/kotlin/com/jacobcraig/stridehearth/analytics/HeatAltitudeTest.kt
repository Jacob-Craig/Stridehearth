package com.jacobcraig.stridehearth.analytics

import kotlin.test.Test
import kotlin.test.assertEquals

class HeatAltitudeTest {
    @Test
    fun `the heat pace factor is 1 up to 20 degrees and rises 1,5 percent per 5 degrees above it`() {
        assertEquals(1.0, HeatAltitude.heatPaceFactor(15.0), 1e-12)
        assertEquals(1.0, HeatAltitude.heatPaceFactor(20.0), 1e-12)
        assertEquals(1.015, HeatAltitude.heatPaceFactor(25.0), 1e-12)
        assertEquals(1.03, HeatAltitude.heatPaceFactor(30.0), 1e-12)
    }

    @Test
    fun `the altitude VO2max factor is 1 up to 1000 m and loses 6,3 percent per 1000 m above it`() {
        assertEquals(1.0, HeatAltitude.altitudeVo2maxFactor(800.0), 1e-12)
        assertEquals(1.0, HeatAltitude.altitudeVo2maxFactor(1000.0), 1e-12)
        assertEquals(0.937, HeatAltitude.altitudeVo2maxFactor(2000.0), 1e-12)
    }
}
