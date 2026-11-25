package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityConditionsTest {
    private fun details(
        n: Int,
        temperature: Array<Short?> = emptyArray(),
        altitude: Array<Float?> = emptyArray(),
    ) = FitTelemetry(
        t = IntArray(n) { it },
        lons = DoubleArray(0),
        lats = DoubleArray(0),
        routeIndex = arrayOfNulls(n),
        temperature = temperature,
        altitude = altitude,
    )

    private fun cleaned(altitude: Array<Float?>) =
        CleanedTelemetry(
            t = IntArray(altitude.size) { it },
            speed = arrayOfNulls(altitude.size),
            distance = arrayOfNulls(altitude.size),
            heartRate = arrayOfNulls(altitude.size),
            altitude = altitude,
            hasGps = false,
        )

    private fun conditions(
        temperature: Array<Short?>,
        n: Int = temperature.size,
    ) = ActivityConditions.of(details(n, temperature), cleaned(arrayOfNulls(n)))

    @Test
    fun `the device temperature is the median sample and the ambient one is 6 degrees lower`() {
        val result = conditions(Array(120) { 26.toShort() })

        assertEquals(26.0, result.deviceTemperatureC)
        assertEquals(20.0, result.ambientTemperatureC)
        assertEquals(6.0, ActivityConditions.WRIST_HEAT_CORRECTION_C)
    }

    @Test
    fun `the median ignores a hot outlier and averages the two middle samples of an even count`() {
        // 30 samples at 20, 29 at 21 and one at 40: the 30th and 31st of 60 sorted samples are 20 and 21.
        val samples: Array<Short?> = (List(30) { 20 } + List(29) { 21 } + listOf(40)).map { it.toShort() }.toTypedArray<Short?>()

        assertEquals(20.5, conditions(samples).deviceTemperatureC)
        assertEquals(14.5, conditions(samples).ambientTemperatureC)
    }

    @Test
    fun `fewer than 60 temperature samples give no temperature`() {
        val result = conditions(Array(59) { 26.toShort() })

        assertNull(result.deviceTemperatureC)
        assertNull(result.ambientTemperatureC)
    }

    @Test
    fun `temperature samples covering under half the Activity give no temperature`() {
        // 60 of 121 samples: just under half.
        val samples = Array<Short?>(121) { if (it < 60) 26.toShort() else null }

        assertNull(conditions(samples).deviceTemperatureC)
        assertEquals(26.0, conditions(Array<Short?>(120) { if (it < 60) 26.toShort() else null }).deviceTemperatureC, "exactly half counts")
    }

    @Test
    fun `no temperature stream gives no temperature`() {
        assertNull(conditions(emptyArray(), n = 120).deviceTemperatureC)
    }

    @Test
    fun `the mean altitude is the mean of the cleaned altitude, ignoring missing samples`() {
        val cleanedAltitude =
            Array<Float?>(120) {
                if (it == 0) {
                    null
                } else if (it % 2 == 0) {
                    300f
                } else {
                    324f
                }
            }
        // 59 samples at 300 (even 2..118) and 60 at 324 (odd 1..119).
        val result = ActivityConditions.of(details(120, altitude = Array(120) { 9_999f }), cleaned(cleanedAltitude))

        assertEquals((59 * 300.0 + 60 * 324.0) / 119, result.meanAltitudeM!!, 1e-9)
    }

    @Test
    fun `fewer than 60 cleaned altitude samples give no mean altitude`() {
        val result = ActivityConditions.of(details(59), cleaned(Array(59) { 300f }))

        assertNull(result.meanAltitudeM)
    }

    @Test
    fun `without GPS the mean altitude is that of the recorded altitude, which the cleaner leaves as it is`() {
        val indoor = details(120, altitude = Array(120) { if (it < 60) 1000f else 1100f })

        val result = ActivityConditions.of(indoor, TelemetryCleaner.clean(indoor, ActivitySport.RUN))

        assertEquals(1050.0, result.meanAltitudeM!!, 1e-9)
    }
}
