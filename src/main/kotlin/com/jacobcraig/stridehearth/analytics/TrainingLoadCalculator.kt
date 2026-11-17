package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.athlete.ThresholdsOn
import com.jacobcraig.stridehearth.system.ActivitySport
import io.swagger.v3.oas.annotations.media.Schema
import kotlin.math.exp
import kotlin.math.pow

/** How a **Training Load** was computed, in the order the hierarchy tries them. */
@Schema(enumAsRef = true)
enum class TrainingLoadMethod {
    /** Running Stress Score, from power and Critical Power (foot sports only). */
    RSS,

    /** Running Training Stress Score, from Normalized Graded Pace and threshold pace (Runs only). */
    RTSS,

    /** Heart Rate Stress Score, Banister TRIMP normalised to one hour at LTHR (any sport). */
    HRSS,
}

/** Why an Activity has no **Training Load**. */
@Schema(enumAsRef = true)
enum class TrainingLoadUnavailableReason {
    /** No Activity Details are stored, such as for a manually logged Activity. */
    NO_ACTIVITY_DETAILS,

    /** A method had the data it needs, but the athlete has not recorded the Threshold it needs. */
    NO_THRESHOLDS,

    /** No power or heart-rate stream covers enough of the recording, and there is no Normalized Graded Pace. */
    NO_USABLE_STREAM,
}

/** One Activity's **Training Load**, or why it has none. */
sealed interface TrainingLoadResult {
    data class Scored(
        val value: Double,
        val method: TrainingLoadMethod,
    ) : TrainingLoadResult

    data class Unavailable(
        val reason: TrainingLoadUnavailableReason,
    ) : TrainingLoadResult
}

/**
 * **Training Load**: one number per Activity for the physiological stress it imposed, scaled so one hour at threshold
 * is 100. It takes the first **Training Load Method** whose data and Threshold are present:
 *
 * 1. **RSS** (RUN, WALK, HIKE, with a usable power stream and Critical Power):
 *    `sum (dt / 3600) x 100 x (P10 / CP)^3.5`, where `P10` is the mean of the non-null power samples in the trailing
 *    [POWER_SMOOTHING_SECONDS] of time `(t - 10, t]`. A Ride's power against a running Critical Power is meaningless,
 *    so other sports fall through.
 * 2. **rTSS** (RUN, with Normalized Graded Pace and threshold pace): `movingSeconds x (NGP / vT)^2 / 3600 x 100`.
 * 3. **HRSS** (any sport, with a usable heart-rate stream, resting and max heart rate, max above resting):
 *    `HRr = clamp((HR - rest) / (max - rest), 0, 1)`, `TRIMP = sum (dt / 60) x HRr x 0.64 x e^(1.92 HRr)`, and
 *    `HRSS = 100 x TRIMP / (60 x HRr_LT x 0.64 x e^(1.92 HRr_LT))`, where LTHR is the athlete's or, without one,
 *    `rest + 0.85 x (max - rest)`.
 *
 * `dt` of a sample is `t[i] - t[i-1]` (0 for the first). A step longer than [RECORDING_PAUSE_SECONDS] is a recording
 * pause and adds nothing, as does a null sample. A stream is usable when its non-null samples cover at least
 * [MIN_COVERAGE] of the recorded time (the sum of the steps that are not pauses), and that time is at least
 * [MIN_RECORDED_SECONDS]: a sensor that died early would understate the load, so the next method is better.
 *
 * No Spring and no I/O.
 */
object TrainingLoadCalculator {
    /** Sports whose power is compared with the athlete's (running) Critical Power. */
    val RSS_SPORTS = setOf(ActivitySport.RUN, ActivitySport.WALK, ActivitySport.HIKE)

    const val RSS_EXPONENT = 3.5
    const val POWER_SMOOTHING_SECONDS = 10
    const val RECORDING_PAUSE_SECONDS = TelemetryCleaner.MAX_INTERPOLATION_GAP_SECONDS
    const val MIN_COVERAGE = RunMetrics.MIN_COVERAGE
    const val MIN_RECORDED_SECONDS = 60

    /** Banister's TRIMP weighting `0.64 e^(1.92 HRr)`; the 0.64 cancels in the normalisation. */
    private const val TRIMP_A = 0.64
    private const val TRIMP_B = 1.92

    /** The share of heart-rate reserve taken as LTHR when the athlete has not recorded one. */
    const val DEFAULT_LTHR_RESERVE_FRACTION = 0.85

    /**
     * The Training Load of an Activity of [sport]. [t] is its Activity Details' time series (seconds since the start),
     * or null when it has none; [power] (W) and [heartRate] (cleaned, bpm) are parallel to it or empty. [ngpMps] and
     * [movingSeconds] are its Normalized Graded Pace and Moving Time, and [thresholds] those in force on its date.
     */
    fun compute(
        sport: ActivitySport,
        t: IntArray?,
        power: Array<Short?>,
        heartRate: Array<Short?>,
        ngpMps: Double?,
        movingSeconds: Int?,
        thresholds: ThresholdsOn,
    ): TrainingLoadResult {
        if (t == null || t.isEmpty()) return TrainingLoadResult.Unavailable(TrainingLoadUnavailableReason.NO_ACTIVITY_DETAILS)
        val steps = Steps(t)
        var lackedThreshold = false

        if (sport in RSS_SPORTS && steps.usable(power)) {
            val cp = thresholds.criticalPowerWatts
            if (cp != null && cp > 0) return TrainingLoadResult.Scored(rss(t, steps, power, cp), TrainingLoadMethod.RSS)
            lackedThreshold = true
        }

        if (sport == ActivitySport.RUN && ngpMps != null && movingSeconds != null) {
            val vT = thresholds.thresholdSpeedMps
            if (vT != null && vT > 0) {
                return TrainingLoadResult.Scored(movingSeconds * (ngpMps / vT).pow(2) / 3600 * 100, TrainingLoadMethod.RTSS)
            }
            lackedThreshold = true
        }

        if (steps.usable(heartRate)) {
            val hrss = hrss(steps, heartRate, thresholds)
            if (hrss != null) return TrainingLoadResult.Scored(hrss, TrainingLoadMethod.HRSS)
            lackedThreshold = true
        }

        return TrainingLoadResult.Unavailable(
            if (lackedThreshold) TrainingLoadUnavailableReason.NO_THRESHOLDS else TrainingLoadUnavailableReason.NO_USABLE_STREAM,
        )
    }

    private fun rss(
        t: IntArray,
        steps: Steps,
        power: Array<Short?>,
        cp: Double,
    ): Double {
        // A window over time (t - 10, t]: the samples from windowStart to i, with a running sum of non-null power.
        var windowStart = 0
        var windowSum = 0.0
        var windowCount = 0
        var total = 0.0
        for (i in t.indices) {
            power.getOrNull(i)?.let {
                windowSum += it
                windowCount++
            }
            while (t[windowStart] <= t[i] - POWER_SMOOTHING_SECONDS) {
                power.getOrNull(windowStart)?.let {
                    windowSum -= it
                    windowCount--
                }
                windowStart++
            }
            val dt = steps.dt(i)
            if (dt == 0 || power.getOrNull(i) == null) continue
            val p10 = windowSum / windowCount
            total += dt / 3600.0 * 100 * (p10 / cp).pow(RSS_EXPONENT)
        }
        return total
    }

    /** HRSS, or null when resting or max heart rate is missing, max is not above resting, or LTHR is not above resting. */
    private fun hrss(
        steps: Steps,
        heartRate: Array<Short?>,
        thresholds: ThresholdsOn,
    ): Double? {
        val rest = thresholds.restingHr ?: return null
        val max = thresholds.maxHr ?: return null
        if (max <= rest) return null
        val reserve = max - rest
        val lthr = thresholds.lthr ?: (rest + DEFAULT_LTHR_RESERVE_FRACTION * reserve)
        val hrrLt = ((lthr - rest) / reserve).coerceIn(0.0, 1.0)
        if (hrrLt <= 0) return null
        var trimp = 0.0
        for (i in heartRate.indices) {
            val dt = steps.dt(i)
            val hr = heartRate[i] ?: continue
            if (dt == 0) continue
            val hrr = ((hr - rest) / reserve).coerceIn(0.0, 1.0)
            trimp += dt / 60.0 * weighted(hrr)
        }
        return 100 * trimp / (60 * weighted(hrrLt))
    }

    private fun weighted(hrr: Double) = hrr * TRIMP_A * exp(TRIMP_B * hrr)

    /** The step of each sample: `t[i] - t[i-1]`, or 0 for the first sample and for a recording pause. */
    private class Steps(
        private val t: IntArray,
    ) {
        private val recorded: Long = t.indices.sumOf { dt(it).toLong() }

        fun dt(i: Int): Int {
            if (i == 0 || i >= t.size) return 0
            val step = t[i] - t[i - 1]
            return if (step in 1..RECORDING_PAUSE_SECONDS) step else 0
        }

        /** Whether [stream]'s non-null samples cover at least [MIN_COVERAGE] of at least [MIN_RECORDED_SECONDS]. */
        fun usable(stream: Array<out Any?>): Boolean {
            if (recorded < MIN_RECORDED_SECONDS) return false
            val covered = stream.indices.sumOf { if (stream[it] != null) dt(it).toLong() else 0L }
            return covered >= MIN_COVERAGE * recorded
        }
    }
}
