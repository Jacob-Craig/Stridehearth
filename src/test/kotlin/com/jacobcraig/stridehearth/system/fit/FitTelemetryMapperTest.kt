package com.jacobcraig.stridehearth.system.fit

import com.garmin.fit.DateTime
import com.garmin.fit.RecordMesg
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [FitTelemetryMapper]. Verifies pinned parser version, coordinate conversions,
 * timeline calculations, and sensor dropout handling.
 */
class FitTelemetryMapperTest {
    private val startInstant = Instant.parse("2024-06-01T06:00:00Z")
    private val sessionStart = DateTime(startInstant)

    // Semicircles for lat 51.51, lon -0.16
    // degrees = semicircles * (180.0 / 2^31)
    // semicircles = degrees * (2^31 / 180.0)
    private val latSemicircles = (51.51 * (2147483648.0 / 180.0)).toInt()
    private val lonSemicircles = (-0.16 * (2147483648.0 / 180.0)).toInt()

    @Test
    fun `empty records list returns null`() {
        val result = FitTelemetryMapper.map(sessionStart, emptyList())
        assertNull(result)
    }

    @Test
    fun `maps records to relative seconds t and coordinate arrays`() {
        val rec1 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant)
                positionLat = latSemicircles
                positionLong = lonSemicircles
                heartRate = 140.toShort()
                speed = 3.0f
                distance = 0.0f
            }
        val rec2 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(5))
                positionLat = (51.52 * (2147483648.0 / 180.0)).toInt()
                positionLong = (-0.17 * (2147483648.0 / 180.0)).toInt()
                heartRate = 145.toShort()
                speed = 3.2f
                distance = 16.0f
            }

        val telemetry = FitTelemetryMapper.map(sessionStart, listOf(rec1, rec2))
        checkNotNull(telemetry)

        assertEquals(listOf(0, 5), telemetry.t.toList())
        assertEquals(2, telemetry.lons.size)
        assertEquals(2, telemetry.lats.size)
        assertEquals(-0.16, telemetry.lons[0], 1e-4)
        assertEquals(51.51, telemetry.lats[0], 1e-4)
        assertEquals(-0.17, telemetry.lons[1], 1e-4)
        assertEquals(51.52, telemetry.lats[1], 1e-4)
        assertEquals(listOf(0, 1), telemetry.routeIndex.toList())
        assertEquals(listOf(140.toShort(), 145.toShort()), telemetry.heartRate.toList())
    }

    @Test
    fun `handles GPS dropouts with null routeIndex and omits from lons and lats`() {
        val rec1 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant)
                positionLat = latSemicircles
                positionLong = lonSemicircles
            }
        val recIndoor =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(1))
                // No position
                heartRate = 150.toShort()
            }
        val rec2 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(2))
                positionLat = (51.52 * (2147483648.0 / 180.0)).toInt()
                positionLong = (-0.17 * (2147483648.0 / 180.0)).toInt()
            }

        val telemetry = FitTelemetryMapper.map(sessionStart, listOf(rec1, recIndoor, rec2))
        checkNotNull(telemetry)

        assertEquals(listOf(0, 1, 2), telemetry.t.toList())
        assertEquals(2, telemetry.lons.size)
        assertEquals(2, telemetry.lats.size)
        assertEquals(listOf(0, null, 1), telemetry.routeIndex.toList())
        assertEquals(listOf(null, 150.toShort(), null), telemetry.heartRate.toList())
    }

    @Test
    fun `records with null sensors preserve null in metric arrays of same length as t`() {
        val rec1 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant)
                heartRate = 130.toShort()
                power = 200
                cadence = 80.toShort()
                temperature = 20.toByte()
            }
        val rec2 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(1))
                // Sensor dropout for HR and Power
                cadence = 82.toShort()
                temperature = 21.toByte()
            }

        val telemetry = FitTelemetryMapper.map(sessionStart, listOf(rec1, rec2))
        checkNotNull(telemetry)

        assertEquals(2, telemetry.t.size)
        assertEquals(listOf(130.toShort(), null), telemetry.heartRate.toList())
        assertEquals(listOf(200.toShort(), null), telemetry.power.toList())
        assertEquals(listOf(80.toShort(), 82.toShort()), telemetry.cadence.toList())
        assertEquals(listOf(20.toShort(), 21.toShort()), telemetry.temperature.toList())
        assertEquals(listOf(null, null), telemetry.distance.toList())
    }

    private fun detailsWithDistance(vararg distance: Float?) =
        FitTelemetry(
            t = IntArray(distance.size) { it },
            lons = DoubleArray(0),
            lats = DoubleArray(0),
            routeIndex = arrayOfNulls(distance.size),
            distance = arrayOf(*distance),
        )

    @Test
    fun `Activity Details have distance when at least one sample has a distance`() {
        assertTrue(detailsWithDistance(null, 12.5f, null).hasDistance)
        assertTrue(detailsWithDistance(0.0f).hasDistance)
    }

    @Test
    fun `Activity Details lack distance when every sample's distance is missing or there are no samples`() {
        assertFalse(detailsWithDistance(null, null, null).hasDistance)
        assertFalse(detailsWithDistance().hasDistance)
    }

    @Test
    fun `distance is not synthesized when the records already have a distance`() {
        val rec0 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant)
                enhancedSpeed = 4.0f
            }
        val rec1 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(10))
                enhancedSpeed = 4.0f
                distance = 33.0f
            }

        val telemetry = FitTelemetryMapper.map(sessionStart, listOf(rec0, rec1))
        checkNotNull(telemetry)

        assertEquals(listOf(null, 33.0f), telemetry.distance.toList())
    }

    @Test
    fun `synthesizes cumulative distance from GPS Haversine and speed fallback when record distance is omitted`() {
        val degToSemicircles = 2147483648.0 / 180.0
        val rec0 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant)
                positionLat = 0
                positionLong = 0
                enhancedSpeed = 4.0f
            }
        // GPS dropout at t=10s -> fallback uses speed (4.0 m/s * 10s = 40.0m)
        val rec1 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(10))
                enhancedSpeed = 4.0f
            }
        // GPS regained at t=20s -> previous sample lacked GPS, so step 1->2 uses speed (5.0 m/s * 10s = 50.0m; cumulative = 90.0m)
        val rec2 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(20))
                positionLat = 0
                positionLong = (0.001 * degToSemicircles).toInt()
                enhancedSpeed = 5.0f
            }
        // Consecutive GPS fix at t=50s (+0.001 deg lon along equator = ~111.195m; cumulative = ~201.195m)
        val rec3 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(50))
                positionLat = 0
                positionLong = (0.002 * degToSemicircles).toInt()
                enhancedSpeed = 3.7f
            }

        val telemetry = FitTelemetryMapper.map(sessionStart, listOf(rec0, rec1, rec2, rec3))
        checkNotNull(telemetry)

        assertEquals(0.0f, telemetry.distance[0]!!, 1e-3f)
        assertEquals(40.0f, telemetry.distance[1]!!, 1e-2f)
        assertEquals(90.0f, telemetry.distance[2]!!, 1e-2f)
        assertEquals(201.195f, telemetry.distance[3]!!, 0.5f)
    }
}
