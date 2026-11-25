package com.jacobcraig.stridehearth.analytics

import io.swagger.v3.oas.annotations.media.Schema
import kotlin.math.pow

/** Why a Run has no **Aerobic Decoupling**, in the order the reasons are checked. */
@Schema(enumAsRef = true)
enum class DecouplingUnavailableReason {
    /** Fewer than [RunMetrics.ROLLING_WINDOW_SECONDS] seconds of Moving Time with Grade-Adjusted Pace. */
    NO_PACE_DATA,

    /** Heart rate covers less than [RunMetrics.MIN_COVERAGE] of the Moving Time, so there is no Efficiency Factor either. */
    NO_HEART_RATE,

    /** Less than [RunMetrics.MIN_STEADY_SECONDS] of Moving Time. */
    TOO_SHORT,

    /**
     * Heart rate covers enough of the Moving Time for an Efficiency Factor, but less than [RunMetrics.MIN_COVERAGE]
     * of one of the two halves Aerobic Decoupling compares (a strap dropout, say).
     */
    HEART_RATE_GAP_IN_HALF,

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
            val series = gapSpeed?.let { MovingSeries.of(cleaned, it) } ?: MovingSeries.EMPTY
            val n = series.size
            val ngp =
                normalized(series.gap, 0, n)
                    ?: return RunMetrics(n, null, null, null, DecouplingUnavailableReason.NO_PACE_DATA)
            val efficiencyFactor = efficiencyFactor(ngp, series, 0, n)
            val halves = TrimmedRange(n)
            val (decoupling, reason) =
                when {
                    efficiencyFactor == null -> null to DecouplingUnavailableReason.NO_HEART_RATE
                    n < MIN_STEADY_SECONDS -> null to DecouplingUnavailableReason.TOO_SHORT
                    !series.heartRateCovers(halves.from, halves.mid) ||
                        !series.heartRateCovers(halves.mid, halves.to) ->
                        null to DecouplingUnavailableReason.HEART_RATE_GAP_IN_HALF
                    else -> decoupling(series, halves)
                }
            return RunMetrics(n, ngp, efficiencyFactor, decoupling, reason)
        }

        /** Called only with at least [MIN_STEADY_SECONDS] of Moving Time and heart rate covering both halves. */
        private fun decoupling(
            series: MovingSeries,
            halves: TrimmedRange,
        ): Pair<Double?, DecouplingUnavailableReason?> {
            val from = halves.from
            val mid = halves.mid
            val to = halves.to
            if (checkNotNull(series.rollingCv(from, to)) >= MAX_STEADY_CV) return null to DecouplingUnavailableReason.NOT_STEADY
            val first = efficiencyFactor(checkNotNull(normalized(series.gap, from, mid)), series, from, mid)
            val second = efficiencyFactor(checkNotNull(normalized(series.gap, mid, to)), series, mid, to)
            return (checkNotNull(first) - checkNotNull(second)) / first * 100 to null
        }

        /** `(mean r^4)^(1/4)` over the rolling windows lying wholly in [from, to), or null when there are none. */
        private fun normalized(
            gap: DoubleArray,
            from: Int,
            to: Int,
        ): Double? {
            val rolling = MovingSeries.rollingMeans(gap, from, to)
            if (rolling.isEmpty()) return null
            return rolling.map { it.pow(4) }.average().pow(0.25)
        }

        /** `60 x ngp / avgHR` over [from, to), or null when heart rate covers less than [MIN_COVERAGE] of it. */
        private fun efficiencyFactor(
            ngp: Double,
            series: MovingSeries,
            from: Int,
            to: Int,
        ): Double? {
            if (!series.heartRateCovers(from, to)) return null
            return 60 * ngp / (from until to).mapNotNull { series.heartRate[it] }.average()
        }
    }
}
