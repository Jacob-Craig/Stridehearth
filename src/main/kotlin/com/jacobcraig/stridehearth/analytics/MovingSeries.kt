package com.jacobcraig.stridehearth.analytics

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The **moving series** of a Run: a 1 Hz series of its Grade-Adjusted Pace ([gap], m/s) and cleaned heart rate
 * ([heartRate], bpm, null where missing) over its Moving Time only. Shared by [RunMetrics] and [Vo2maxEstimator], so
 * both read the same seconds. Its length is the Run's **Moving Time**.
 *
 * No Spring and no I/O.
 */
internal class MovingSeries(
    val gap: DoubleArray,
    val heartRate: Array<Double?>,
    /** The elapsed second (`t`) each moving second falls at: ascending, with a jump wherever a stop was dropped. */
    val time: IntArray = IntArray(gap.size),
    /** Power in W per moving second, null where missing; empty unless [of] was given a power stream. */
    val power: Array<Double?> = arrayOfNulls(gap.size),
) {
    val size: Int get() = gap.size

    /** Whether heart rate is present on at least [RunMetrics.MIN_COVERAGE] of the seconds in [from, to), and on any. */
    fun heartRateCovers(
        from: Int,
        to: Int,
    ): Boolean {
        val present = (from until to).count { heartRate[it] != null }
        return present > 0 && present >= RunMetrics.MIN_COVERAGE * (to - from)
    }

    /** Whether power is present on at least [RunMetrics.MIN_COVERAGE] of the seconds in [from, to), and on any. */
    fun powerCovers(
        from: Int,
        to: Int,
    ): Boolean {
        val present = (from until to).count { power[it] != null }
        return present > 0 && present >= RunMetrics.MIN_COVERAGE * (to - from)
    }

    /**
     * The coefficient of variation of the trailing [RunMetrics.ROLLING_WINDOW_SECONDS] means of [gap] over [from, to),
     * the steady-state guard; null when no window lies wholly in it.
     */
    fun rollingCv(
        from: Int,
        to: Int,
    ): Double? {
        val rolling = rollingMeans(gap, from, to)
        if (rolling.isEmpty()) return null
        val mean = rolling.average()
        val sd = sqrt(rolling.sumOf { (it - mean) * (it - mean) } / rolling.size)
        return sd / mean
    }

    companion object {
        val EMPTY = MovingSeries(DoubleArray(0), arrayOfNulls(0), IntArray(0), arrayOfNulls(0))

        /**
         * The moving series of [cleaned] with its Grade-Adjusted Pace [gapSpeed] (parallel to `cleaned.t`): within each
         * stretch of samples whose consecutive steps are at most [RunMetrics.RECORDING_PAUSE_SECONDS] apart, every whole
         * second from the stretch's first to its last sample, with GAP, cleaned speed and heart rate interpolated
         * linearly in time (a value next to a missing one stays missing). Seconds without GAP or with cleaned speed
         * below [RunMetrics.MOVING_SPEED_MPS] are dropped and the rest concatenated. [power], if given, is parallel to
         * `cleaned.t` and interpolated like the rest.
         */
        fun of(
            cleaned: CleanedTelemetry,
            gapSpeed: Array<Float?>,
            power: Array<Short?>? = null,
        ): MovingSeries {
            val t = cleaned.t
            val gap = ArrayList<Double>(t.size)
            val heartRate = ArrayList<Double?>(t.size)
            val time = ArrayList<Int>(t.size)
            val watts = ArrayList<Double?>(t.size)

            fun keep(
                second: Int,
                gapValue: Double?,
                speedValue: Double?,
                heartRateValue: Double?,
                powerValue: Double?,
            ) {
                if (gapValue == null || speedValue == null || speedValue < RunMetrics.MOVING_SPEED_MPS) return
                gap += gapValue
                heartRate += heartRateValue
                time += second
                watts += powerValue
            }

            var previous = -1
            for (i in t.indices) {
                if (previous >= 0) {
                    // A sample that does not advance time is skipped; the gap is filled from the last kept one.
                    if (t[i] <= t[previous]) continue
                    val dt = t[i] - t[previous]
                    if (dt <= RunMetrics.RECORDING_PAUSE_SECONDS) {
                        for (s in 1 until dt) {
                            val fraction = s.toDouble() / dt
                            keep(
                                t[previous] + s,
                                lerp(gapSpeed.at(previous), gapSpeed.at(i), fraction),
                                lerp(cleaned.speed.at(previous), cleaned.speed.at(i), fraction),
                                lerp(
                                    cleaned.heartRate.getOrNull(previous)?.toDouble(),
                                    cleaned.heartRate.getOrNull(i)?.toDouble(),
                                    fraction,
                                ),
                                lerp(power?.getOrNull(previous)?.toDouble(), power?.getOrNull(i)?.toDouble(), fraction),
                            )
                        }
                    }
                }
                keep(t[i], gapSpeed.at(i), cleaned.speed.at(i), cleaned.heartRate.getOrNull(i)?.toDouble(), power?.getOrNull(i)?.toDouble())
                previous = i
            }
            return MovingSeries(gap.toDoubleArray(), heartRate.toTypedArray(), time.toIntArray(), watts.toTypedArray())
        }

        /** The trailing [RunMetrics.ROLLING_WINDOW_SECONDS] means of [gap] whose windows lie wholly in [from, to). */
        fun rollingMeans(
            gap: DoubleArray,
            from: Int,
            to: Int,
        ): List<Double> {
            val window = RunMetrics.ROLLING_WINDOW_SECONDS
            if (to - from < window) return emptyList()
            val means = ArrayList<Double>(to - from - window + 1)
            var sum = 0.0
            for (k in from until to) {
                sum += gap[k]
                if (k - from >= window) sum -= gap[k - window]
                if (k - from >= window - 1) means += sum / window
            }
            return means
        }

        private fun Array<Float?>.at(i: Int): Double? = getOrNull(i)?.toDouble()

        private fun lerp(
            a: Double?,
            b: Double?,
            fraction: Double,
        ): Double? = if (a == null || b == null) null else a + (b - a) * fraction
    }
}

/**
 * The steady part of a moving series of [n] seconds: `[ceil(0.10 N), floor(0.95 N))`, without the first
 * [RunMetrics.WARM_UP_FRACTION] (warm-up) and the last [RunMetrics.COOL_DOWN_FRACTION] (cool-down), split by count at
 * [mid].
 */
internal class TrimmedRange(
    n: Int,
) {
    val from = ceil(RunMetrics.WARM_UP_FRACTION * n).toInt()
    val to = floor((1 - RunMetrics.COOL_DOWN_FRACTION) * n).toInt()
    val mid = from + (to - from) / 2
}
