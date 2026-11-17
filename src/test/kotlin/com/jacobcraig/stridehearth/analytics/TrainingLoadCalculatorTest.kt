package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.athlete.ThresholdsOn
import com.jacobcraig.stridehearth.system.ActivitySport
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Synthetic streams with every expected value worked out by hand from the formulas in the brief for #35, never read
 * back from the calculator. One hour at threshold is 100 for every method. A 1 Hz stream from t = 0 to t = 3600
 * (3601 samples) spans exactly 3600 recorded seconds, since the first sample contributes no time.
 *
 * - RSS = sum (dt / 3600) x 100 x (P10 / CP)^3.5, P10 the trailing 10 s mean power.
 * - rTSS = movingSeconds x (NGP / vT)^2 / 3600 x 100.
 * - HRSS = 100 x TRIMP / (60 x HRr_LT x 0.64 x e^(1.92 HRr_LT)), TRIMP = sum (dt / 60) x HRr x 0.64 x e^(1.92 HRr).
 */
class TrainingLoadCalculatorTest {
    private val rest = 50.0
    private val max = 190.0

    private val allThresholds =
        ThresholdsOn(maxHr = max, restingHr = rest, lthr = 170.0, thresholdSpeedMps = 4.0, criticalPowerWatts = 300.0)

    /** [seconds] of recording at 1 Hz: t = 0 to t = [seconds], so [seconds] recorded seconds. */
    private fun oneHz(seconds: Int) = IntArray(seconds + 1) { it }

    private fun compute(
        t: IntArray?,
        sport: ActivitySport = ActivitySport.RUN,
        power: (Int) -> Int? = { null },
        heartRate: (Int) -> Int? = { null },
        ngpMps: Double? = null,
        movingSeconds: Int? = null,
        thresholds: ThresholdsOn = allThresholds,
    ): TrainingLoadResult =
        TrainingLoadCalculator.compute(
            sport = sport,
            t = t,
            power = t?.let { times -> Array(times.size) { power(times[it])?.toShort() } } ?: emptyArray(),
            heartRate = t?.let { times -> Array(times.size) { heartRate(times[it])?.toShort() } } ?: emptyArray(),
            ngpMps = ngpMps,
            movingSeconds = movingSeconds,
            thresholds = thresholds,
        )

    private fun scored(
        result: TrainingLoadResult,
        method: TrainingLoadMethod,
    ): Double {
        val scored = assertIs<TrainingLoadResult.Scored>(result)
        assertEquals(method, scored.method)
        return scored.value
    }

    @Nested
    inner class Rss {
        @Test
        fun `an hour at critical power is 100`() {
            val load = compute(oneHz(3600), power = { 300 })

            assertEquals(100.0, scored(load, TrainingLoadMethod.RSS), 0.01)
        }

        @Test
        fun `half an hour at 110 percent of critical power is 50 x 1_1^3_5`() {
            val load = compute(oneHz(1800), power = { 330 })

            // 50 x 1.1^3.5 = 69.80
            assertEquals(50 * 1.1.pow(3.5), scored(load, TrainingLoadMethod.RSS), 0.01)
            assertEquals(69.80, scored(load, TrainingLoadMethod.RSS), 0.01)
        }

        @Test
        fun `power is smoothed over the trailing 10 seconds, not per sample`() {
            // Alternating 0 W and 600 W every second: each trailing 10 s window averages 300 W, so 100, whereas
            // unsmoothed power would give 0.5 x 2^3.5 x 100 = 565.7.
            val load = compute(oneHz(3600), power = { if (it % 2 == 0) 0 else 600 })

            assertEquals(100.0, scored(load, TrainingLoadMethod.RSS), 0.5)
        }

        @Test
        fun `the smoothing window is 10 seconds of time, whatever the sampling rate`() {
            // Every 5 s: the window (t - 10, t] holds two samples, alternating 0 W and 600 W, so 300 W throughout.
            val t = IntArray(721) { it * 5 }
            val load = compute(t, power = { if (it % 10 == 0) 0 else 600 })

            assertEquals(100.0, scored(load, TrainingLoadMethod.RSS), 0.5)
        }

        @Test
        fun `a recording pause adds nothing`() {
            // An hour at CP split by a 600 s pause: 0..1800 and 2400..4200, so 3600 recorded seconds.
            val t = IntArray(1801) { it } + IntArray(1801) { 2400 + it }
            val load = compute(t, power = { 300 })

            assertEquals(100.0, scored(load, TrainingLoadMethod.RSS), 0.01)
        }
    }

    @Nested
    inner class Rtss {
        private fun rtss(
            ngp: Double,
            movingSeconds: Int,
        ) = compute(oneHz(movingSeconds), ngpMps = ngp, movingSeconds = movingSeconds)

        @Test
        fun `an hour at threshold pace is 100`() {
            assertEquals(100.0, scored(rtss(4.0, 3600), TrainingLoadMethod.RTSS), 0.01)
        }

        @Test
        fun `an hour at 90 percent of threshold speed is 81`() {
            // 3600 x (3.6 / 4.0)^2 / 3600 x 100 = 81
            assertEquals(81.0, scored(rtss(3.6, 3600), TrainingLoadMethod.RTSS), 0.01)
        }

        @Test
        fun `half an hour at threshold pace is 50`() {
            assertEquals(50.0, scored(rtss(4.0, 1800), TrainingLoadMethod.RTSS), 0.01)
        }
    }

    @Nested
    inner class Hrss {
        private fun hrss(
            hr: Int,
            thresholds: ThresholdsOn = allThresholds.copy(criticalPowerWatts = null, thresholdSpeedMps = null),
            seconds: Int = 3600,
        ) = compute(oneHz(seconds), heartRate = { hr }, thresholds = thresholds)

        @Test
        fun `an hour at LTHR is 100`() {
            assertEquals(100.0, scored(hrss(170), TrainingLoadMethod.HRSS), 0.01)
        }

        @Test
        fun `an hour at 150 bpm is 63_3`() {
            // HRr = 100 / 140, HRr_LT = 120 / 140: 100 x (HRr e^(1.92 HRr)) / (HRr_LT e^(1.92 HRr_LT)) = 63.34
            val hrr = 100.0 / 140
            val hrrLt = 120.0 / 140
            val expected = 100 * (hrr * exp(1.92 * hrr)) / (hrrLt * exp(1.92 * hrrLt))

            assertEquals(expected, scored(hrss(150), TrainingLoadMethod.HRSS), 0.01)
            assertEquals(63.3, scored(hrss(150), TrainingLoadMethod.HRSS), 0.1)
        }

        @Test
        fun `without an LTHR entry, LTHR is resting plus 85 percent of the reserve`() {
            // LTHR = 50 + 0.85 x 140 = 169
            val load = hrss(169, thresholds = allThresholds.copy(lthr = null, criticalPowerWatts = null, thresholdSpeedMps = null))

            assertEquals(100.0, scored(load, TrainingLoadMethod.HRSS), 0.01)
        }

        @Test
        fun `heart rate below resting clamps to zero`() {
            assertEquals(0.0, scored(hrss(40), TrainingLoadMethod.HRSS), 1e-9)
        }

        @Test
        fun `null samples add nothing`() {
            // Two hours, every other sample null: a stream covering half the time is not usable, so use runs of 9 s
            // with heart rate and 1 s without (90 percent coverage): 0.9 x 100.
            val load =
                compute(
                    oneHz(3600),
                    heartRate = { if (it % 10 == 0) null else 170 },
                    thresholds = allThresholds.copy(criticalPowerWatts = null, thresholdSpeedMps = null),
                )

            assertEquals(90.0, scored(load, TrainingLoadMethod.HRSS), 0.01)
        }

        @Test
        fun `a 600 second gap in t adds nothing`() {
            val t = IntArray(1801) { it } + IntArray(1801) { 2400 + it }
            val load =
                compute(t, heartRate = { 170 }, thresholds = allThresholds.copy(criticalPowerWatts = null, thresholdSpeedMps = null))

            assertEquals(100.0, scored(load, TrainingLoadMethod.HRSS), 0.01)
        }
    }

    @Nested
    inner class Hierarchy {
        private fun everything(
            sport: ActivitySport = ActivitySport.RUN,
            thresholds: ThresholdsOn = allThresholds,
            power: (Int) -> Int? = { 300 },
        ) = compute(
            oneHz(3600),
            sport = sport,
            power = power,
            heartRate = { 160 },
            ngpMps = 3.6,
            movingSeconds = 3600,
            thresholds = thresholds,
        )

        private fun method(result: TrainingLoadResult) = assertIs<TrainingLoadResult.Scored>(result).method

        @Test
        fun `power, NGP and heart rate with every threshold give RSS`() {
            assertEquals(TrainingLoadMethod.RSS, method(everything()))
        }

        @Test
        fun `without critical power, rTSS`() {
            assertEquals(TrainingLoadMethod.RTSS, method(everything(thresholds = allThresholds.copy(criticalPowerWatts = null))))
        }

        @Test
        fun `without critical power or threshold pace, HRSS`() {
            val thresholds = allThresholds.copy(criticalPowerWatts = null, thresholdSpeedMps = null)

            assertEquals(TrainingLoadMethod.HRSS, method(everything(thresholds = thresholds)))
        }

        @Test
        fun `a ride with power and critical power gives HRSS, not RSS`() {
            assertEquals(TrainingLoadMethod.HRSS, method(everything(sport = ActivitySport.RIDE)))
        }

        @Test
        fun `power covering half the recording falls through to rTSS`() {
            val halfPower = everything(power = { if (it < 1800) 300 else null })

            assertEquals(TrainingLoadMethod.RTSS, method(halfPower))
        }

        @Test
        fun `a walk with heart rate but no power gives HRSS, not rTSS`() {
            assertEquals(TrainingLoadMethod.HRSS, method(everything(sport = ActivitySport.WALK, power = { null })))
        }

        @Test
        fun `a walk with power and critical power gives RSS`() {
            assertEquals(TrainingLoadMethod.RSS, method(everything(sport = ActivitySport.WALK)))
        }

        @Test
        fun `a recording shorter than 60 seconds has no usable stream`() {
            val short = compute(oneHz(59), heartRate = { 160 }, thresholds = allThresholds.copy(criticalPowerWatts = null))

            assertEquals(TrainingLoadUnavailableReason.NO_USABLE_STREAM, assertIs<TrainingLoadResult.Unavailable>(short).reason)
        }
    }

    @Nested
    inner class Unavailable {
        private fun reason(result: TrainingLoadResult) = assertIs<TrainingLoadResult.Unavailable>(result).reason

        @Test
        fun `no Activity Details give NO_ACTIVITY_DETAILS`() {
            assertEquals(TrainingLoadUnavailableReason.NO_ACTIVITY_DETAILS, reason(compute(t = null)))
        }

        @Test
        fun `heart rate without resting or max heart rate gives NO_THRESHOLDS`() {
            val load = compute(oneHz(3600), heartRate = { 150 }, thresholds = ThresholdsOn.NONE)

            assertEquals(TrainingLoadUnavailableReason.NO_THRESHOLDS, reason(load))
        }

        @Test
        fun `max heart rate not above resting gives NO_THRESHOLDS`() {
            val thresholds =
                ThresholdsOn(maxHr = 120.0, restingHr = 120.0, lthr = null, thresholdSpeedMps = null, criticalPowerWatts = null)
            val load = compute(oneHz(3600), heartRate = { 150 }, thresholds = thresholds)

            assertEquals(TrainingLoadUnavailableReason.NO_THRESHOLDS, reason(load))
        }

        @Test
        fun `a run with NGP but no threshold pace gives NO_THRESHOLDS`() {
            val load = compute(oneHz(3600), ngpMps = 3.6, movingSeconds = 3600, thresholds = ThresholdsOn.NONE)

            assertEquals(TrainingLoadUnavailableReason.NO_THRESHOLDS, reason(load))
        }

        @Test
        fun `a GPS-only ride gives NO_USABLE_STREAM`() {
            assertEquals(TrainingLoadUnavailableReason.NO_USABLE_STREAM, reason(compute(oneHz(3600), sport = ActivitySport.RIDE)))
        }
    }
}
