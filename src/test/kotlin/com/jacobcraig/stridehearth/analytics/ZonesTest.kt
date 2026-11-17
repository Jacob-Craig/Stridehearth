package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Zones worked out by hand from Friel's running tables (the brief for #34), never read back from [Zones]:
 * - heart rate: lower bounds `roundHalfUp(LTHR x {0.85, 0.90, 0.95, 1.00})`, Z5 capped by MAX_HR when known;
 * - pace: as time, Z1 slower than 129 % of threshold pace, then 114 %, 106 % and 99 %;
 * - Seiler's three zones group Friel's five as 1, 1, 2, 2, 3.
 */
class ZonesTest {
    @Nested
    inner class HeartRate {
        @Test
        fun `LTHR 170 gives lower bounds 145, 153, 162 and 170, rounding the halves up`() {
            // 170 x 0.85 = 144.5 -> 145; 170 x 0.90 = 153; 170 x 0.95 = 161.5 -> 162; 170 x 1.00 = 170.
            val zones = Zones.heartRate(lthr = 170.0, maxHr = null)

            assertEquals(listOf(1, 2, 3, 4, 5), zones.map { it.zone })
            assertEquals(listOf(null, 145, 153, 162, 170), zones.map { it.lowerBpm })
            assertEquals(listOf(145, 153, 162, 170, null), zones.map { it.upperBpm })
        }

        @Test
        fun `LTHR 160 gives lower bounds 136, 144, 152 and 160`() {
            val zones = Zones.heartRate(lthr = 160.0, maxHr = null)

            assertEquals(listOf(null, 136, 144, 152, 160), zones.map { it.lowerBpm })
        }

        @Test
        fun `Z5 ends at MAX_HR when it is known`() {
            val zones = Zones.heartRate(lthr = 170.0, maxHr = 192.0)

            assertEquals(192, zones.last().upperBpm)
        }

        @Test
        fun `the five zones map to Seiler's 1, 1, 2, 2, 3`() {
            assertEquals(listOf(1, 1, 2, 2, 3), Zones.heartRate(lthr = 170.0, maxHr = null).map { it.seilerZone })
        }
    }

    @Nested
    inner class Pace {
        // 4:00 /km is 240 s/km, a threshold speed of 1000 / 240 = 4.1667 m/s.
        private val thresholdSpeed = 1000.0 / 240.0

        /** A speed bound in m/s as a pace in s/km. */
        private fun secondsPerKm(speedMps: Double?) = speedMps?.let { 1000.0 / it }

        @Test
        fun `4 00 per km gives boundaries of 309·6, 273·6, 254·4 and 237·6 s per km`() {
            // 240 x 1.29 = 309.6; 240 x 1.14 = 273.6; 240 x 1.06 = 254.4; 240 x 0.99 = 237.6.
            val zones = Zones.pace(thresholdSpeed)

            assertEquals(listOf(1, 2, 3, 4, 5), zones.map { it.zone })
            assertNull(zones.first().lowerSpeedMps)
            assertNull(zones.last().upperSpeedMps)
            val expected = listOf(309.6, 273.6, 254.4, 237.6)
            zones.drop(1).map { secondsPerKm(it.lowerSpeedMps)!! }.zip(expected).forEach { (actual, want) ->
                assertEquals(want, actual, 1e-9)
            }
            zones.dropLast(1).map { secondsPerKm(it.upperSpeedMps)!! }.zip(expected).forEach { (actual, want) ->
                assertEquals(want, actual, 1e-9)
            }
        }

        @Test
        fun `the speed bounds are 4·1667 m per s divided by 1·29, 1·14, 1·06 and 0·99`() {
            val zones = Zones.pace(thresholdSpeed)

            assertEquals(3.2300, zones[1].lowerSpeedMps!!, 1e-4)
            assertEquals(3.6550, zones[2].lowerSpeedMps!!, 1e-4)
            assertEquals(3.9308, zones[3].lowerSpeedMps!!, 1e-4)
            assertEquals(4.2088, zones[4].lowerSpeedMps!!, 1e-4)
        }

        @Test
        fun `the five pace zones map to Seiler's 1, 1, 2, 2, 3`() {
            assertEquals(listOf(1, 1, 2, 2, 3), Zones.pace(thresholdSpeed).map { it.seilerZone })
        }
    }
}
