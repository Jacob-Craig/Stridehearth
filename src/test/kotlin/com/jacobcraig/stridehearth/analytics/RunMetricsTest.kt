package com.jacobcraig.stridehearth.analytics

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic Cleaned Activity Details with a Grade-Adjusted Pace stream supplied directly. Every expected value is
 * worked out by hand from the formulas in the brief for #33, never read back from the calculator:
 * - moving series: 1 Hz, linear in time across steps of at most 10 s, without seconds below 0.5 m/s;
 * - `r[k] = mean(gap[k-29..k])`, `NGP = (mean r^4)^(1/4)`;
 * - `EF = 60 NGP / avgHR`;
 * - trimmed range `[ceil(0.10 N), floor(0.95 N))`, halves by count, `Decoupling = (EF1 - EF2) / EF1 x 100`.
 */
class RunMetricsTest {
    /** A run sampled at [t] with cleaned [speed] (m/s), [gap] (m/s, defaults to the speed) and [hr] (bpm). */
    private fun run(
        t: IntArray,
        speed: (Int) -> Double?,
        gap: (Int) -> Double? = speed,
        hr: (Int) -> Int? = { 150 },
    ): Pair<CleanedTelemetry, Array<Float?>> {
        val n = t.size
        val cleaned =
            CleanedTelemetry(
                t = t,
                speed = Array(n) { speed(t[it])?.toFloat() },
                distance = arrayOfNulls(n),
                heartRate = Array(n) { hr(t[it])?.toShort() },
                altitude = arrayOfNulls(n),
                hasGps = true,
            )
        return cleaned to Array(n) { gap(t[it])?.toFloat() }
    }

    /** A 1 Hz run of [seconds] samples, t = 0 until [seconds]. */
    private fun oneHz(
        seconds: Int,
        speed: (Int) -> Double?,
        gap: (Int) -> Double? = speed,
        hr: (Int) -> Int? = { 150 },
    ) = run(IntArray(seconds) { it }, speed, gap, hr)

    private fun metrics(input: Pair<CleanedTelemetry, Array<Float?>>): RunMetrics = RunMetrics.of(input.first, input.second)

    @Nested
    inner class `steady run` {
        @Test
        fun `3 m per s flat for an hour at 150 bpm has NGP 3, EF 1,2 and no decoupling`() {
            val result = metrics(oneHz(3600, { 3.0 }))

            assertEquals(3600, result.movingSeconds)
            assertEquals(3.0, assertNotNull(result.ngpMps), 1e-6)
            // 60 x 3.0 / 150
            assertEquals(1.2, assertNotNull(result.efficiencyFactor), 1e-6)
            assertEquals(0.0, assertNotNull(result.aerobicDecouplingPct), 1e-6)
            assertNull(result.decouplingUnavailableReason)
        }

        @Test
        fun `HR rising from 140 to 154 across the trimmed halves decouples by 9,09 percent`() {
            // N = 3600: trimmed [360, 3420), M = 3060, halves [360, 1890) and [1890, 3420).
            // EF1 = 180 / 140, EF2 = 180 / 154: (1 - 140 / 154) x 100 = 9.0909.
            val result = metrics(oneHz(3600, { 3.0 }, hr = { if (it < 1890) 140 else 154 }))

            assertEquals(9.0909, assertNotNull(result.aerobicDecouplingPct), 0.1)
            assertNull(result.decouplingUnavailableReason)
        }

        @Test
        fun `HR falling across the halves gives negative decoupling`() {
            // (1 - 154 / 140) x 100 = -10.0
            val result = metrics(oneHz(3600, { 3.0 }, hr = { if (it < 1890) 154 else 140 }))

            assertEquals(-10.0, assertNotNull(result.aerobicDecouplingPct), 0.1)
        }

        @Test
        fun `a low-HR warm-up in the first 8 percent is excluded from decoupling`() {
            // 8 % of 3600 s is 288 s, inside the first 10 % (360 s) that is trimmed away.
            val result = metrics(oneHz(3600, { 3.0 }, hr = { if (it < 288) 100 else 150 }))

            assertEquals(0.0, assertNotNull(result.aerobicDecouplingPct), 0.1)
        }

        @Test
        fun `a hilly run at constant GAP is steady even though its speed varies with grade`() {
            // Speed alternates 2.0 m/s uphill and 4.5 m/s downhill every 120 s; GAP stays 3.0.
            val result = metrics(oneHz(3600, speed = { if ((it / 120) % 2 == 0) 2.0 else 4.5 }, gap = { 3.0 }))

            assertEquals(3.0, assertNotNull(result.ngpMps), 1e-6)
            assertEquals(0.0, assertNotNull(result.aerobicDecouplingPct), 1e-6)
            assertNull(result.decouplingUnavailableReason)
        }
    }

    @Nested
    inner class `Normalized Graded Pace` {
        @Test
        fun `is the fourth-power mean of the 30 s rolling means`() {
            // 30 s at 2.0 then one second at 5.0: r[29] = 2.0, r[30] = (29 x 2 + 5) / 30 = 2.1.
            // NGP = ((2^4 + 2.1^4) / 2)^(1/4) = (35.4481 / 2)^(1/4) = 2.05183.
            val result = metrics(oneHz(31, { if (it < 30) 2.0 else 5.0 }))

            assertEquals(2.05183, assertNotNull(result.ngpMps), 1e-4)
        }

        @Test
        fun `weights fast work so alternating 2 and 4 m per s normalises above the 3 m per s mean`() {
            val result = metrics(oneHz(600, { if ((it / 60) % 2 == 0) 2.0 else 4.0 }))

            val ngp = assertNotNull(result.ngpMps)
            assertTrue(ngp > 3.0, "NGP $ngp should exceed the 3.0 m/s mean")
            assertTrue(ngp < 4.0)
        }

        @Test
        fun `does not need heart rate`() {
            val result = metrics(oneHz(3600, { 3.0 }, hr = { null }))

            assertEquals(3.0, assertNotNull(result.ngpMps), 1e-6)
        }

        @Test
        fun `fewer than 30 moving seconds leave every metric null with NO_PACE_DATA`() {
            val result = metrics(oneHz(29, { 3.0 }))

            assertEquals(29, result.movingSeconds)
            assertNull(result.ngpMps)
            assertNull(result.efficiencyFactor)
            assertNull(result.aerobicDecouplingPct)
            assertEquals(DecouplingUnavailableReason.NO_PACE_DATA, result.decouplingUnavailableReason)
        }

        @Test
        fun `a run without a GAP stream has no moving time and NO_PACE_DATA`() {
            val (cleaned, _) = oneHz(3600, { 3.0 })

            val result = RunMetrics.of(cleaned, gapSpeed = null)

            assertEquals(0, result.movingSeconds)
            assertNull(result.ngpMps)
            assertEquals(DecouplingUnavailableReason.NO_PACE_DATA, result.decouplingUnavailableReason)
        }
    }

    @Nested
    inner class `moving series` {
        @Test
        fun `a 300 s recording pause and 120 s standing still are excluded from moving time`() {
            // 0..999 running (1000 s), pause of 301 s, 1300..1419 standing (120 s, dropped), 1420..2419 running.
            val t = IntArray(1000) { it } + IntArray(1120) { 1300 + it }
            val result = metrics(run(t, { if (it in 1300..1419) 0.0 else 3.0 }))

            assertEquals(2000, result.movingSeconds)
            assertEquals(3.0, assertNotNull(result.ngpMps), 1e-6)
        }

        @Test
        fun `a duplicated timestamp does not stop the gap after it being filled`() {
            // t = 0..29, 30, 30, 35, 36..59: the duplicate at 30 is ignored and 31..34 are interpolated from it,
            // so every second from 0 to 59 is moving.
            val t = IntArray(31) { it } + intArrayOf(30) + IntArray(25) { 35 + it }
            val result = metrics(run(t, { 3.0 }))

            assertEquals(60, result.movingSeconds)
        }

        @Test
        fun `samples every 5 s are interpolated to 1 Hz`() {
            // t = 0, 5, ..., 3595: every second from 0 to 3595 is moving.
            val result = metrics(run(IntArray(720) { it * 5 }, { 3.0 }))

            assertEquals(3596, result.movingSeconds)
            assertEquals(3.0, assertNotNull(result.ngpMps), 1e-6)
        }

        @Test
        fun `GAP is interpolated linearly between samples`() {
            // Samples at t = 0 (2.0 m/s) and t = 10 (4.0 m/s) then 1 Hz at 4.0 to t = 39: 40 moving seconds.
            // gap[s] = 2.0 + 0.2 s for s <= 10, then 4.0. r[29] = (sum_{s=0..10}(2 + 0.2 s) + 19 x 4) / 30
            //   = (22 + 11 + 76) / 30 = 3.6333, and r rises towards 4.0 up to r[39], so NGP lies in [3.6333, 4.0).
            // Without interpolation (one second at 2.0, nine missing) N would be 31, not 40.
            val t = intArrayOf(0) + IntArray(30) { 10 + it }
            val result = metrics(run(t, { if (it == 0) 2.0 else 4.0 }))

            assertEquals(40, result.movingSeconds)
            val ngp = assertNotNull(result.ngpMps)
            assertTrue(ngp in 3.6333..4.0, "NGP $ngp")
        }
    }

    @Nested
    inner class `heart rate` {
        @Test
        fun `no heart rate leaves EF and decoupling null with NO_HEART_RATE but NGP present`() {
            val result = metrics(oneHz(3600, { 3.0 }, hr = { null }))

            assertEquals(3.0, assertNotNull(result.ngpMps), 1e-6)
            assertNull(result.efficiencyFactor)
            assertNull(result.aerobicDecouplingPct)
            assertEquals(DecouplingUnavailableReason.NO_HEART_RATE, result.decouplingUnavailableReason)
        }

        @Test
        fun `heart rate on half the moving seconds counts as no heart rate`() {
            val result = metrics(oneHz(3600, { 3.0 }, hr = { if (it % 2 == 0) 150 else null }))

            assertEquals(3.0, assertNotNull(result.ngpMps), 1e-6)
            assertNull(result.efficiencyFactor)
            assertNull(result.aerobicDecouplingPct)
            assertEquals(DecouplingUnavailableReason.NO_HEART_RATE, result.decouplingUnavailableReason)
        }

        @Test
        fun `EF averages heart rate over the moving seconds that have it`() {
            // HR on 90 % of seconds: 150 on most, null on every tenth. avgHR = 150, EF = 1.2.
            val result = metrics(oneHz(3600, { 3.0 }, hr = { if (it % 10 == 9) null else 150 }))

            assertEquals(1.2, assertNotNull(result.efficiencyFactor), 1e-6)
        }

        @Test
        fun `a half without enough heart rate gives NO_HEART_RATE for decoupling only`() {
            // HR missing for t in [2700, 3420): 720 of 3600 s, so overall coverage is exactly 80 % and EF stands,
            // but the second half [1890, 3420) has HR on only 810 of 1530 s (52.9 %).
            val result = metrics(oneHz(3600, { 3.0 }, hr = { if (it in 2700 until 3420) null else 150 }))

            assertEquals(1.2, assertNotNull(result.efficiencyFactor), 1e-6)
            assertNull(result.aerobicDecouplingPct)
            assertEquals(DecouplingUnavailableReason.NO_HEART_RATE, result.decouplingUnavailableReason)
        }
    }

    @Nested
    inner class `steady-state guard` {
        @Test
        fun `rejects an interval session as NOT_STEADY while keeping NGP and EF`() {
            // 10 min easy at 2.5 m/s, then 6 x (180 s at 5.0 + 120 s at 2.5): 2400 s in all.
            val result =
                metrics(
                    oneHz(2400, { s ->
                        if (s < 600) {
                            2.5
                        } else if ((s - 600) % 300 < 180) {
                            5.0
                        } else {
                            2.5
                        }
                    }),
                )

            assertEquals(2400, result.movingSeconds)
            assertNotNull(result.ngpMps)
            assertNotNull(result.efficiencyFactor)
            assertNull(result.aerobicDecouplingPct)
            assertEquals(DecouplingUnavailableReason.NOT_STEADY, result.decouplingUnavailableReason)
        }

        @Test
        fun `a 20 minute steady run is TOO_SHORT`() {
            val result = metrics(oneHz(1200, { 3.0 }))

            assertEquals(3.0, assertNotNull(result.ngpMps), 1e-6)
            assertEquals(1.2, assertNotNull(result.efficiencyFactor), 1e-6)
            assertNull(result.aerobicDecouplingPct)
            assertEquals(DecouplingUnavailableReason.TOO_SHORT, result.decouplingUnavailableReason)
        }

        @Test
        fun `exactly 30 minutes of moving time is long enough`() {
            val result = metrics(oneHz(1800, { 3.0 }))

            assertEquals(0.0, assertNotNull(result.aerobicDecouplingPct), 1e-6)
        }

        @Test
        fun `NO_HEART_RATE is reported before TOO_SHORT`() {
            val result = metrics(oneHz(1200, { 3.0 }, hr = { null }))

            assertEquals(DecouplingUnavailableReason.NO_HEART_RATE, result.decouplingUnavailableReason)
        }

        @Test
        fun `NO_HEART_RATE in one half is reported before NOT_STEADY`() {
            // The +/-14 % square wave alone is NOT_STEADY (see below). HR missing for t in [2700, 3420) keeps
            // overall coverage at 80 % so EF stands, but the second half [1890, 3420) has HR on only 52.9 %.
            val result =
                metrics(
                    oneHz(
                        3600,
                        { s -> if ((s / 300) % 2 == 0) 3.0 * 1.14 else 3.0 * 0.86 },
                        hr = { if (it in 2700 until 3420) null else 150 },
                    ),
                )

            assertNotNull(result.efficiencyFactor)
            assertNull(result.aerobicDecouplingPct)
            assertEquals(DecouplingUnavailableReason.NO_HEART_RATE, result.decouplingUnavailableReason)
        }

        @Test
        fun `NO_HEART_RATE in one half is reported before TOO_SHORT`() {
            // N = 1200: trimmed [120, 1140), halves [120, 630) and [630, 1140). HR missing for t in [900, 1140)
            // leaves 960 of 1200 s (80 %) overall but only 270 of 510 s (52.9 %) in the second half.
            val result = metrics(oneHz(1200, { 3.0 }, hr = { if (it in 900 until 1140) null else 150 }))

            assertNotNull(result.efficiencyFactor)
            assertEquals(DecouplingUnavailableReason.NO_HEART_RATE, result.decouplingUnavailableReason)
        }

        @Test
        fun `pace variation just under the CV limit is steady and just over it is not`() {
            // A 2-level square wave a +/- d with a 600 s period has rolling-mean CV a little below d / a
            // (the 30 s windows soften the edges): d / a = 0.11 is steady; d / a = 0.14 is not.
            fun wave(depth: Double) = oneHz(3600, { s -> if ((s / 300) % 2 == 0) 3.0 * (1 + depth) else 3.0 * (1 - depth) })

            assertNull(metrics(wave(0.11)).decouplingUnavailableReason)
            assertEquals(DecouplingUnavailableReason.NOT_STEADY, metrics(wave(0.14)).decouplingUnavailableReason)
        }
    }

    @Test
    fun `constants match the brief`() {
        assertEquals(30, RunMetrics.ROLLING_WINDOW_SECONDS)
        assertEquals(0.5, RunMetrics.MOVING_SPEED_MPS)
        assertEquals(0.10, RunMetrics.WARM_UP_FRACTION)
        assertEquals(0.05, RunMetrics.COOL_DOWN_FRACTION)
        assertEquals(1800, RunMetrics.MIN_STEADY_SECONDS)
        assertEquals(0.12, RunMetrics.MAX_STEADY_CV)
        assertEquals(0.80, RunMetrics.MIN_COVERAGE)
        assertEquals(TelemetryCleaner.MAX_INTERPOLATION_GAP_SECONDS, RunMetrics.RECORDING_PAUSE_SECONDS)
    }
}
