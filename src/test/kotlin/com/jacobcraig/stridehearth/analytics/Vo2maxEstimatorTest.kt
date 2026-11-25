package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.athlete.ThresholdsOn
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Synthetic 1 Hz runs whose answer is worked out by hand from the #42 method, never read back from the estimator:
 * - `VO2(v) = -4.60 + 0.182258 v + 0.000104 v²` with v in m/min (Daniels-Gilbert);
 * - x = %HRR = (HR - rest) / (max - rest), y = VO2(60 x GAP x heat factor), per 60 s segment of the trimmed range
 *   `[ceil(0.10 N), floor(0.95 N))`;
 * - the line through (0, 3.5): `raw = 3.5 + Σx(y - 3.5) / Σx²`, then divided by the altitude factor.
 *
 * Thresholds are rest 50 and max 190 bpm (a 140 bpm reserve) unless a test says otherwise.
 */
class Vo2maxEstimatorTest {
    private val thresholds = ThresholdsOn(maxHr = 190.0, restingHr = 50.0, lthr = null, thresholdSpeedMps = null, criticalPowerWatts = null)
    private val noConditions = ActivityConditions(null, null, null)

    /** A flat 1 Hz run of [seconds] at [speed] m/s (GAP equal to it) and [hr] bpm, with GPS and [altitude] m. */
    private fun run(
        seconds: Int,
        speed: (Int) -> Double = { 4.0 },
        hr: (Int) -> Int? = { 162 },
        altitude: (Double) -> Double = { 0.0 },
    ): Pair<CleanedTelemetry, Array<Float?>> {
        val distance = DoubleArray(seconds)
        for (i in 1 until seconds) distance[i] = distance[i - 1] + speed(i)
        val cleaned =
            CleanedTelemetry(
                t = IntArray(seconds) { it },
                speed = Array(seconds) { speed(it).toFloat() },
                distance = Array(seconds) { distance[it].toFloat() },
                heartRate = Array(seconds) { hr(it)?.toShort() },
                altitude = Array(seconds) { altitude(distance[it]).toFloat() },
                hasGps = true,
            )
        return cleaned to Array(seconds) { speed(it).toFloat() }
    }

    private fun estimate(
        input: Pair<CleanedTelemetry, Array<Float?>?>,
        on: ThresholdsOn = thresholds,
        conditions: ActivityConditions = noConditions,
    ): Vo2maxResult = Vo2maxEstimator.estimate(input.first, input.second, on, conditions)

    private fun raw(result: Vo2maxResult): Double = assertIs<Vo2maxResult.Estimate>(result).raw

    private fun reason(result: Vo2maxResult): Vo2maxUnavailableReason = assertIs<Vo2maxResult.Unavailable>(result).reason

    @Nested
    inner class `synthetic steady runs with a known answer` {
        @Test
        fun `40 min at 4,0 m per s and 162 bpm is 55,54`() {
            // %HRR = 112 / 140 = 0.8; VO2(240) = 45.132; 3.5 + (45.132 - 3.5) / 0.8 = 55.54.
            val result = assertIs<Vo2maxResult.Estimate>(estimate(run(2400)))

            assertEquals(55.54, result.raw, 0.05)
            // Trimmed [240, 2280): 34 whole segments.
            assertEquals(34, result.segments)
            assertEquals(2400, result.movingSeconds)
        }

        @Test
        fun `3,5 m per s at 148 then 4,2 m per s at 170 regresses through rest to 54,47`() {
            // The step sits at the middle of the trimmed range [240, 2280), on a segment boundary: 17 segments each.
            // x = 0.7 and 0.857, y = VO2(210) = 38.261 and VO2(252) = 47.933:
            // k = (0.7 x 34.761 + 0.857 x 44.433) / (0.49 + 0.735) = 50.97; 3.5 + 50.97 = 54.47.
            val stepped = run(2400, speed = { if (it < 1260) 3.5 else 4.2 }, hr = { if (it < 1260) 148 else 170 })

            assertEquals(54.47, raw(estimate(stepped)), 0.05)
        }

        @Test
        fun `30 degrees of Ambient Temperature costs the speed of 1,03 times the pace and gives 57,64`() {
            // VO2(240 x 1.03 = 247.2) = 46.809; 3.5 + (46.809 - 3.5) / 0.8 = 57.64.
            val hot = ActivityConditions(deviceTemperatureC = 36.0, ambientTemperatureC = 30.0, meanAltitudeM = null)

            assertEquals(57.64, raw(estimate(run(2400), conditions = hot)), 0.05)
        }

        @Test
        fun `2000 m of Mean Altitude divides by 0,937 and gives 59,27`() {
            // 55.54 / (1 - 6.3e-5 x 1000) = 55.54 / 0.937 = 59.27.
            val high = ActivityConditions(deviceTemperatureC = null, ambientTemperatureC = null, meanAltitudeM = 2000.0)

            assertEquals(59.27, raw(estimate(run(2400), conditions = high)), 0.05)
        }

        @Test
        fun `15 m of climb per km is not too hilly`() {
            // 150 m over 2400 s x 4 m/s = 9.6 km: 15.6 m/km.
            assertEquals(55.54, raw(estimate(run(2400, altitude = { it * 0.015 }))), 0.05)
        }
    }

    @Nested
    inner class `non-qualifying runs return a reason` {
        @Test
        fun `no Grade-Adjusted Pace`() {
            val (cleaned, _) = run(2400)

            assertEquals(Vo2maxUnavailableReason.NO_PACE_DATA, reason(estimate(cleaned to null)))
        }

        @Test
        fun `no Resting HR Threshold`() {
            assertEquals(
                Vo2maxUnavailableReason.NO_HEART_RATE_THRESHOLDS,
                reason(estimate(run(2400), on = thresholds.copy(restingHr = null))),
            )
        }

        @Test
        fun `a heart rate reserve of 15 bpm`() {
            assertEquals(
                Vo2maxUnavailableReason.NO_HEART_RATE_THRESHOLDS,
                reason(estimate(run(2400), on = thresholds.copy(restingHr = 175.0))),
            )
        }

        @Test
        fun `heart rate on 70 percent of the Moving Time`() {
            assertEquals(Vo2maxUnavailableReason.NO_HEART_RATE, reason(estimate(run(2400, hr = { if (it % 10 < 7) 162 else null }))))
        }

        @Test
        fun `19 minutes of Moving Time`() {
            assertEquals(Vo2maxUnavailableReason.TOO_SHORT, reason(estimate(run(1140))))
        }

        @Test
        fun `300 m of climb over 10 km`() {
            // 2500 s at 4 m/s is 10 km; 300 m of gain is 30 m per km, over the 20 m limit.
            assertEquals(Vo2maxUnavailableReason.TOO_HILLY, reason(estimate(run(2500, altitude = { it * 0.03 }))))
        }

        @Test
        fun `alternating 3 and 5 m per s every 2 minutes`() {
            // Rolling 30 s means swing between 3 and 5 around 4: a coefficient of variation near 0.22.
            val intervals = run(2400, speed = { if ((it / 120) % 2 == 0) 3.0 else 5.0 })

            assertEquals(Vo2maxUnavailableReason.NOT_STEADY, reason(estimate(intervals)))
        }

        @Test
        fun `45 percent of heart rate reserve`() {
            // 50 + 0.45 x 140 = 113 bpm.
            assertEquals(Vo2maxUnavailableReason.INTENSITY_TOO_LOW, reason(estimate(run(2400, hr = { 113 }))))
        }

        @Test
        fun `heart rate gaps leave 9 qualifying segments`() {
            // 1200 s: trimmed [120, 1140) holds 17 segments. The first 8 miss 13 s of heart rate each (78 % coverage),
            // leaving 9; the run as a whole still has heart rate on 91 % of its Moving Time.
            val gappy = run(1200, hr = { if (it >= 120 && (it - 120) / 60 < 8 && (it - 120) % 60 < 13) null else 162 })

            assertEquals(Vo2maxUnavailableReason.TOO_FEW_SEGMENTS, reason(estimate(gappy)))
        }

        @Test
        fun `an implausible result below 20`() {
            // 2 m/s at max heart rate: VO2(120) = 18.77, so raw = 18.77.
            assertEquals(Vo2maxUnavailableReason.OUT_OF_RANGE, reason(estimate(run(2400, speed = { 2.0 }, hr = { 190 }))))
        }

        @Test
        fun `an implausible result above 90`() {
            // 5 m/s at 55 % HRR (127 bpm): VO2(300) = 59.44; 3.5 + 55.94 / 0.55 = 105.2.
            assertEquals(Vo2maxUnavailableReason.OUT_OF_RANGE, reason(estimate(run(2400, speed = { 5.0 }, hr = { 127 }))))
        }
    }
}
