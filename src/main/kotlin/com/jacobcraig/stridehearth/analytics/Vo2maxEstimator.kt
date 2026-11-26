package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.athlete.ThresholdsOn
import io.swagger.v3.oas.annotations.media.Schema

/** Why a Run has no **VO2max Estimate**, in the order the reasons are checked. */
@Schema(enumAsRef = true)
enum class Vo2maxUnavailableReason {
    /** The Run has no Activity Details to estimate from, such as a manual Run. */
    NO_ACTIVITY_DETAILS,

    /** No Grade-Adjusted Pace, or no Moving Time. */
    NO_PACE_DATA,

    /** No Max HR or Resting HR Threshold in force on the Run's date, or less than 20 bpm between them. */
    NO_HEART_RATE_THRESHOLDS,

    /** Heart rate covers less than 80 % of the Moving Time. */
    NO_HEART_RATE,

    /** Less than 20 minutes of Moving Time. */
    TOO_SHORT,

    /** With GPS, more than 20 m of climb per km. */
    TOO_HILLY,

    /** The 30 s rolling Grade-Adjusted Pace varies by 0.12 or more over the steady part. */
    NOT_STEADY,

    /** Heart rate averages less than 50 % of heart rate reserve over the steady part. */
    INTENSITY_TOO_LOW,

    /** Fewer than 10 one-minute segments have heart rate and lie between 50 % and 100 % of heart rate reserve. */
    TOO_FEW_SEGMENTS,

    /** The estimate falls outside 20-90 ml/kg/min. */
    OUT_OF_RANGE,
}

/** A Run's **VO2max Estimate**, uncorrected, or why it has none. */
sealed interface Vo2maxResult {
    /** [raw] ml/kg/min, from [segments] one-minute segments of a Run with [movingSeconds] of Moving Time. */
    data class Estimate(
        val raw: Double,
        val segments: Int,
        val movingSeconds: Int,
    ) : Vo2maxResult

    data class Unavailable(
        val reason: Vo2maxUnavailableReason,
    ) : Vo2maxResult
}

/**
 * A Run's **VO2max Estimate** (#42): the oxygen cost of the Grade-Adjusted speed it would run at 100 % of heart rate
 * reserve, extrapolated from its steady state.
 *
 * Over the Run's moving series ([MovingSeries]) trimmed of warm-up and cool-down ([TrimmedRange]), each whole
 * [SEGMENT_SECONDS] block with heart rate on at least [RunMetrics.MIN_COVERAGE] of its seconds and a mean %HRR in
 * [[MIN_HRR], [MAX_HRR]] gives a point: x = its %HRR = (HR - rest) / (max - rest), taken as the share of VO2 reserve
 * (Swain & Leutholtz 1997), and y = the Daniels-Gilbert oxygen cost ([DanielsVdot.vo2Cost]) of its mean GAP,
 * multiplied by the **Heat Derating** of the Run's **Ambient Temperature** when known. The line through the resting
 * point (0, [VO2_REST]) is fitted by least squares, `raw = VO2_REST + Σx(y - VO2_REST) / Σx²`, its value at 100 % HRR,
 * then divided by the altitude factor of the Run's **Mean Altitude** when known ([HeatAltitude]). The same cost curve
 * as Daniels' tables makes the estimate a VDOT.
 *
 * The first failing check of [Vo2maxUnavailableReason], in order, is the reason there is none. Cleaned heart rate is
 * read like the Efficiency Factor's, so it shares its known bias (#52). No Spring and no I/O.
 */
object Vo2maxEstimator {
    const val MIN_MOVING_SECONDS = 1200
    const val SEGMENT_SECONDS = 60
    const val MIN_SEGMENTS = 10
    const val MIN_HRR = 0.5
    const val MAX_HRR = 1.0
    const val MAX_GAIN_PER_KM = 20.0
    const val MIN_HR_RESERVE = 20.0
    const val VO2_REST = 3.5
    val PLAUSIBLE = 20.0..90.0

    fun estimate(
        cleaned: CleanedTelemetry,
        gapSpeed: Array<Float?>?,
        thresholds: ThresholdsOn,
        conditions: ActivityConditions,
    ): Vo2maxResult {
        val series = gapSpeed?.let { MovingSeries.of(cleaned, it) } ?: MovingSeries.EMPTY
        val n = series.size
        if (n == 0) return unavailable(Vo2maxUnavailableReason.NO_PACE_DATA)
        val rest = thresholds.restingHr
        val max = thresholds.maxHr
        if (rest == null || max == null || max - rest < MIN_HR_RESERVE) return unavailable(Vo2maxUnavailableReason.NO_HEART_RATE_THRESHOLDS)
        if (!series.heartRateCovers(0, n)) return unavailable(Vo2maxUnavailableReason.NO_HEART_RATE)
        if (n < MIN_MOVING_SECONDS) return unavailable(Vo2maxUnavailableReason.TOO_SHORT)
        if (cleaned.hasGps && (gainPerKm(cleaned) ?: 0.0) > MAX_GAIN_PER_KM) return unavailable(Vo2maxUnavailableReason.TOO_HILLY)
        val trimmed = TrimmedRange(n)
        val cv = series.rollingCv(trimmed.from, trimmed.to)
        if (cv == null || cv >= RunMetrics.MAX_STEADY_CV) return unavailable(Vo2maxUnavailableReason.NOT_STEADY)

        fun hrr(bpm: Double) = (bpm - rest) / (max - rest)

        val meanHrr = (trimmed.from until trimmed.to).mapNotNull { series.heartRate[it] }.map(::hrr).average()
        if (meanHrr.isNaN() || meanHrr < MIN_HRR) return unavailable(Vo2maxUnavailableReason.INTENSITY_TOO_LOW)

        val heat = conditions.ambientTemperatureC?.let(HeatAltitude::heatPaceFactor) ?: 1.0
        var sumXy = 0.0
        var sumXx = 0.0
        var segments = 0
        var start = trimmed.from
        while (start + SEGMENT_SECONDS <= trimmed.to) {
            val end = start + SEGMENT_SECONDS
            if (series.heartRateCovers(start, end)) {
                val x = (start until end).mapNotNull { series.heartRate[it] }.map(::hrr).average()
                if (x in MIN_HRR..MAX_HRR) {
                    val gap = (start until end).sumOf { series.gap[it] } / SEGMENT_SECONDS
                    val y = DanielsVdot.vo2Cost(60 * gap * heat)
                    sumXy += x * (y - VO2_REST)
                    sumXx += x * x
                    segments++
                }
            }
            start = end
        }
        if (segments < MIN_SEGMENTS) return unavailable(Vo2maxUnavailableReason.TOO_FEW_SEGMENTS)

        val altitude = conditions.meanAltitudeM?.let(HeatAltitude::altitudeVo2maxFactor) ?: 1.0
        val raw = (VO2_REST + sumXy / sumXx) / altitude
        if (raw !in PLAUSIBLE) return unavailable(Vo2maxUnavailableReason.OUT_OF_RANGE)
        return Vo2maxResult.Estimate(raw, segments, n)
    }

    /** Metres of cleaned-altitude climb per cleaned kilometre, or null without a distance to divide by. */
    private fun gainPerKm(cleaned: CleanedTelemetry): Double? {
        var gain = 0.0
        var previous: Float? = null
        for (altitude in cleaned.altitude) {
            if (altitude == null) continue
            previous?.let { if (altitude > it) gain += altitude - it }
            previous = altitude
        }
        val distances = cleaned.distance.filterNotNull()
        val km = ((distances.maxOrNull() ?: return null) - distances.min()) / 1000.0
        return if (km > 0) gain / km else null
    }

    private fun unavailable(reason: Vo2maxUnavailableReason) = Vo2maxResult.Unavailable(reason)
}
