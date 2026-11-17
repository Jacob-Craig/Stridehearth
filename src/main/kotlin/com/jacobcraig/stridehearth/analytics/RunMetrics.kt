package com.jacobcraig.stridehearth.analytics

import io.swagger.v3.oas.annotations.media.Schema
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** Why a Run has no **Aerobic Decoupling**, in the order the reasons are checked. */
@Schema(enumAsRef = true)
enum class DecouplingUnavailableReason {
    /** Fewer than [RunMetrics.ROLLING_WINDOW_SECONDS] seconds of Moving Time with Grade-Adjusted Pace. */
    NO_PACE_DATA,

    /** Heart rate covers less than [RunMetrics.MIN_COVERAGE] of the Moving Time, or of either half. */
    NO_HEART_RATE,

    /** Less than [RunMetrics.MIN_STEADY_SECONDS] of Moving Time. */
    TOO_SHORT,

    /** The 30 s rolling Grade-Adjusted Pace varies by [RunMetrics.MAX_STEADY_CV] or more. */
    NOT_STEADY,
}

/**
 * The summary metrics of one Run, derived from its **Cleaned Activity Details** and **Grade-Adjusted Pace**:
 *
 * - [movingSeconds]: **Moving Time**, the length N of the moving series (see [of]).
 * - [ngpMps]: **Normalized Graded Pace** in m/s, `(mean r^4)^(1/4)` of the 30 s trailing rolling means `r` of GAP.
 *   It needs no heart rate. Null below [ROLLING_WINDOW_SECONDS] of Moving Time.
 * - [efficiencyFactor]: **Efficiency Factor**, `60 x NGP / avgHR` in metres per minute per bpm. Null without NGP or
 *   when heart rate covers less than [MIN_COVERAGE] of the Moving Time.
 * - [aerobicDecouplingPct]: **Aerobic Decoupling**, `(EF1 - EF2) / EF1 x 100` over the two halves of the Moving
 *   Time after trimming the first [WARM_UP_FRACTION] and last [COOL_DOWN_FRACTION]. Positive when heart rate rose
 *   relative to pace. Reported only for a steady state: at least [MIN_STEADY_SECONDS] of Moving Time and a
 *   coefficient of variation of the rolling GAP means over the trimmed range below [MAX_STEADY_CV].
 * - [decouplingUnavailableReason]: null exactly when [aerobicDecouplingPct] is present.
 *
 * No Spring and no I/O.
 */
data class RunMetrics(
    val movingSeconds: Int,
    val ngpMps: Double?,
    val efficiencyFactor: Double?,
    val aerobicDecouplingPct: Double?,
    val decouplingUnavailableReason: DecouplingUnavailableReason?,
) {
    companion object {
        /** Length of the trailing rolling window NGP and the steady-state guard average over. */
        const val ROLLING_WINDOW_SECONDS = 30

        /** A second slower than this (cleaned speed, m/s) is standing still and not Moving Time. */
        const val MOVING_SPEED_MPS = 0.5

        /** A step between samples longer than this is a recording pause and contributes no Moving Time. */
        const val RECORDING_PAUSE_SECONDS = TelemetryCleaner.MAX_INTERPOLATION_GAP_SECONDS

        /** The share of Moving Time at the start that Aerobic Decoupling ignores as warm-up. */
        const val WARM_UP_FRACTION = 0.10

        /** The share of Moving Time at the end that Aerobic Decoupling ignores as cool-down. */
        const val COOL_DOWN_FRACTION = 0.05

        /** The least Moving Time for which Aerobic Decoupling is reported. */
        const val MIN_STEADY_SECONDS = 1800

        /** The rolling-GAP coefficient of variation at or above which a Run is not steady. */
        const val MAX_STEADY_CV = 0.12

        /** The least share of seconds with heart rate for Efficiency Factor and each Decoupling half. */
        const val MIN_COVERAGE = 0.80

        /**
         * Computes the Run metrics of [cleaned] with its Grade-Adjusted Pace [gapSpeed] (parallel to `cleaned.t`).
         *
         * The **moving series** is a 1 Hz series: within each stretch of samples whose consecutive steps are at most
         * [RECORDING_PAUSE_SECONDS] apart, every whole second from the stretch's first to its last sample, with GAP,
         * cleaned speed and heart rate interpolated linearly in time (a value next to a missing one stays missing).
         * Seconds without GAP or with cleaned speed below [MOVING_SPEED_MPS] are dropped and the rest concatenated.
         */
        fun of(
            cleaned: CleanedTelemetry,
            gapSpeed: Array<Float?>?,
        ): RunMetrics {
            val series = gapSpeed?.let { movingSeries(cleaned, it) } ?: MovingSeries(DoubleArray(0), arrayOfNulls(0))
            val n = series.gap.size
            val ngp =
                normalized(series.gap, 0, n)
                    ?: return RunMetrics(n, null, null, null, DecouplingUnavailableReason.NO_PACE_DATA)
            val efficiencyFactor = efficiencyFactor(ngp, series.heartRate, 0, n)
            val (decoupling, reason) =
                when {
                    efficiencyFactor == null -> null to DecouplingUnavailableReason.NO_HEART_RATE
                    n < MIN_STEADY_SECONDS -> null to DecouplingUnavailableReason.TOO_SHORT
                    else -> decoupling(series)
                }
            return RunMetrics(n, ngp, efficiencyFactor, decoupling, reason)
        }

        private fun decoupling(series: MovingSeries): Pair<Double?, DecouplingUnavailableReason?> {
            val n = series.gap.size
            val from = ceil(WARM_UP_FRACTION * n).toInt()
            val to = floor((1 - COOL_DOWN_FRACTION) * n).toInt()
            val rolling = rollingMeans(series.gap, from, to)
            val mean = rolling.average()
            val sd = sqrt(rolling.sumOf { (it - mean) * (it - mean) } / rolling.size)
            if (sd / mean >= MAX_STEADY_CV) return null to DecouplingUnavailableReason.NOT_STEADY
            val mid = from + (to - from) / 2
            val first = normalized(series.gap, from, mid)?.let { efficiencyFactor(it, series.heartRate, from, mid) }
            val second = normalized(series.gap, mid, to)?.let { efficiencyFactor(it, series.heartRate, mid, to) }
            if (first == null || second == null) return null to DecouplingUnavailableReason.NO_HEART_RATE
            return (first - second) / first * 100 to null
        }

        /** `(mean r^4)^(1/4)` over the rolling windows lying wholly in [from, to), or null when there are none. */
        private fun normalized(
            gap: DoubleArray,
            from: Int,
            to: Int,
        ): Double? {
            val rolling = rollingMeans(gap, from, to)
            if (rolling.isEmpty()) return null
            return rolling.map { it.pow(4) }.average().pow(0.25)
        }

        /** The trailing [ROLLING_WINDOW_SECONDS] means of [gap] whose windows lie wholly in [from, to). */
        private fun rollingMeans(
            gap: DoubleArray,
            from: Int,
            to: Int,
        ): List<Double> {
            if (to - from < ROLLING_WINDOW_SECONDS) return emptyList()
            val means = ArrayList<Double>(to - from - ROLLING_WINDOW_SECONDS + 1)
            var sum = 0.0
            for (k in from until to) {
                sum += gap[k]
                if (k - from >= ROLLING_WINDOW_SECONDS) sum -= gap[k - ROLLING_WINDOW_SECONDS]
                if (k - from >= ROLLING_WINDOW_SECONDS - 1) means += sum / ROLLING_WINDOW_SECONDS
            }
            return means
        }

        /** `60 x ngp / avgHR` over [from, to), or null when heart rate covers less than [MIN_COVERAGE] of it. */
        private fun efficiencyFactor(
            ngp: Double,
            heartRate: Array<Double?>,
            from: Int,
            to: Int,
        ): Double? {
            val present = (from until to).mapNotNull { heartRate[it] }
            if (present.isEmpty() || present.size < MIN_COVERAGE * (to - from)) return null
            return 60 * ngp / present.average()
        }

        private class MovingSeries(
            val gap: DoubleArray,
            val heartRate: Array<Double?>,
        )

        private fun movingSeries(
            cleaned: CleanedTelemetry,
            gapSpeed: Array<Float?>,
        ): MovingSeries {
            val t = cleaned.t
            val gap = ArrayList<Double>(t.size)
            val heartRate = ArrayList<Double?>(t.size)

            fun keep(
                gapValue: Double?,
                speedValue: Double?,
                heartRateValue: Double?,
            ) {
                if (gapValue == null || speedValue == null || speedValue < MOVING_SPEED_MPS) return
                gap += gapValue
                heartRate += heartRateValue
            }

            for (i in t.indices) {
                if (i > 0 && t[i] <= t[i - 1]) continue
                keep(gapSpeed.at(i), cleaned.speed.at(i), cleaned.heartRate.getOrNull(i)?.toDouble())
                val next = i + 1
                if (next >= t.size) break
                val dt = t[next] - t[i]
                if (dt <= 1 || dt > RECORDING_PAUSE_SECONDS) continue
                for (s in 1 until dt) {
                    val fraction = s.toDouble() / dt
                    keep(
                        lerp(gapSpeed.at(i), gapSpeed.at(next), fraction),
                        lerp(cleaned.speed.at(i), cleaned.speed.at(next), fraction),
                        lerp(cleaned.heartRate.getOrNull(i)?.toDouble(), cleaned.heartRate.getOrNull(next)?.toDouble(), fraction),
                    )
                }
            }
            return MovingSeries(gap.toDoubleArray(), heartRate.toTypedArray())
        }

        private fun Array<Float?>.at(i: Int): Double? = getOrNull(i)?.toDouble()

        private fun lerp(
            a: Double?,
            b: Double?,
            fraction: Double,
        ): Double? = if (a == null || b == null) null else a + (b - a) * fraction
    }
}
