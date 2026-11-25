package com.jacobcraig.stridehearth.analytics

import kotlin.math.max

/**
 * One **Split** of a **Race Split Plan**: the stretch of the course from [startM] to [endM] metres and the time it
 * should take. [paceSecondsPerUnit] is per whole kilometre or mile even for a partial last split. The elevation
 * figures are null when the course has no altitude; [avgGradePct] is the net rise over the split's length.
 */
data class RaceSplit(
    val index: Int,
    val startM: Double,
    val endM: Double,
    val distanceM: Double,
    val targetSeconds: Double,
    val paceSecondsPerUnit: Double,
    val cumulativeSeconds: Double,
    val elevationGainM: Double?,
    val elevationLossM: Double?,
    val avgGradePct: Double?,
)

/** A point of a course's elevation profile: cleaned altitude [altitudeM] at [distanceM] from the start. */
data class ProfilePoint(
    val distanceM: Double,
    val altitudeM: Double,
)

/**
 * A **Race Split Plan** over a course [courseDistanceM] long for a **Goal Time** of [goalSeconds], cut into splits of
 * [unitMeters] with a **Split Bias** of [biasPct]. [elevationGainM] is the course's total climb, null without altitude.
 */
data class RaceSplitPlan(
    val courseDistanceM: Double,
    val goalSeconds: Double,
    val unitMeters: Double,
    val biasPct: Double,
    val elevationGainM: Double?,
    val splits: List<RaceSplit>,
    val profile: List<ProfilePoint>,
)

/**
 * Lays out a **Race Split Plan**: per-kilometre or per-mile target times over an Activity's route that hold
 * Grade-Adjusted effort even and sum to a **Goal Time**.
 *
 * Even Grade-Adjusted effort means time in proportion to distance times Minetti's cost ratio (the inverse of
 * Grade-Adjusted Pace). Between consecutive samples of the **Cleaned Activity Details** with distance, the
 * interval's weight is `w = Δd · f · m(x)`:
 * - `f = max(MIN_DOWNHILL_RATIO, mean of its two samples' cost ratios)`. Runners cannot turn a steep descent into the
 *   speed Minetti's minimum (about 0.47 at -20 %) would allow, so a descent is never planned more than 15 % faster
 *   than the flat; the cap binds on anything steeper than about -3 %.
 * - `m(x) = 1 + k (x / D - 0.5)` ramps effort evenly over the course, at the interval's midpoint `x` of the course's
 *   length `D`. With the **Split Bias** `β = biasPct / 100`, `k = 4β / (2 + β)`, so on even ground the second half
 *   takes exactly `biasPct` percent longer than the first (positive split) or shorter (negative split).
 *
 * Each interval takes `T · w / Σw`. Splits are cut at multiples of [unitMeters] from the start, an interval crossing a
 * boundary being shared in proportion to distance; the last split is partial, and its cumulative time is exactly `T`.
 *
 * A pure function with no persistence.
 */
object RaceSplitPlanner {
    /** The lowest cost ratio a descent is planned at: no more than about 15 % faster than the flat. */
    const val MIN_DOWNHILL_RATIO = 0.85

    /** The largest **Split Bias** either way, in percent. */
    const val MAX_BIAS_PCT = 5.0

    /** The most points an elevation profile has. */
    const val PROFILE_POINTS = 500

    const val KM_METERS = 1000.0

    const val MILE_METERS = 1609.344

    /** A boundary this close to the end of the course would leave an empty last split, so none is cut there. */
    private const val END_TOLERANCE_METERS = 1e-6

    /**
     * The plan for running [cleaned]'s route in [goalSeconds], with [costRatios] parallel to its `t` (see
     * [GradeAdjustedPace.costRatios]); null when the Activity has no GPS fix or covers no distance.
     */
    fun plan(
        cleaned: CleanedTelemetry,
        costRatios: DoubleArray,
        goalSeconds: Double,
        unitMeters: Double,
        biasPct: Double,
    ): RaceSplitPlan? {
        if (!cleaned.hasGps) return null
        val samples = cleaned.distance.indices.filter { cleaned.distance[it] != null }
        if (samples.size < 2) return null
        val start = cleaned.distance[samples.first()]!!.toDouble()
        // Cleaned distance never decreases with a GPS fix; the running max only guards against rounding.
        val x = DoubleArray(samples.size)
        for (j in samples.indices) x[j] = max(cleaned.distance[samples[j]]!!.toDouble() - start, if (j > 0) x[j - 1] else 0.0)
        val length = x.last()
        if (length <= 0.0) return null

        val cumulative = cumulativeTimes(x, DoubleArray(samples.size) { costRatios[samples[it]] }, goalSeconds, biasPct)
        val altitude = Altitude.of(samples.map { cleaned.altitude[it]?.toDouble() }, x)

        val boundaries = generateSequence(0.0) { it + unitMeters }.takeWhile { it < length - END_TOLERANCE_METERS }.toList() + length
        val splits =
            boundaries.zipWithNext().mapIndexed { k, (from, to) ->
                val startTime = interpolate(x, cumulative, from)
                val endTime = if (k == boundaries.size - 2) goalSeconds else interpolate(x, cumulative, to)
                val distance = to - from
                val elevation = altitude?.change(from, to)
                RaceSplit(
                    index = k + 1,
                    startM = from,
                    endM = to,
                    distanceM = distance,
                    targetSeconds = endTime - startTime,
                    paceSecondsPerUnit = (endTime - startTime) / distance * unitMeters,
                    cumulativeSeconds = endTime,
                    elevationGainM = elevation?.gain,
                    elevationLossM = elevation?.loss,
                    avgGradePct = altitude?.let { (it.at(to) - it.at(from)) / distance * 100 },
                )
            }

        return RaceSplitPlan(
            courseDistanceM = length,
            goalSeconds = goalSeconds,
            unitMeters = unitMeters,
            biasPct = biasPct,
            elevationGainM = altitude?.let { splits.sumOf { split -> split.elevationGainM!! } },
            splits = splits,
            profile = altitude?.profile() ?: emptyList(),
        )
    }

    /** The planned time at each sample, from 0 at the first to [goalSeconds] at the last. */
    private fun cumulativeTimes(
        x: DoubleArray,
        ratio: DoubleArray,
        goalSeconds: Double,
        biasPct: Double,
    ): DoubleArray {
        val length = x.last()
        val beta = biasPct / 100
        val k = 4 * beta / (2 + beta)
        val weight = DoubleArray(x.size)
        for (j in 1 until x.size) {
            val f = max(MIN_DOWNHILL_RATIO, (ratio[j - 1] + ratio[j]) / 2)
            val m = 1 + k * ((x[j - 1] + x[j]) / 2 / length - 0.5)
            weight[j] = weight[j - 1] + (x[j] - x[j - 1]) * f * m
        }
        val total = weight.last()
        return DoubleArray(x.size) { goalSeconds * weight[it] / total }
    }

    /** [y] at [at] along non-decreasing [x], linear between samples; [at] lies within `x.first()..x.last()`. */
    private fun interpolate(
        x: DoubleArray,
        y: DoubleArray,
        at: Double,
    ): Double {
        val after = firstAbove(x, at)
        if (after == 0) return y[0]
        if (after == x.size) return y.last()
        val before = after - 1
        val span = x[after] - x[before]
        return if (span <= 0.0) y[after] else y[before] + (y[after] - y[before]) * (at - x[before]) / span
    }

    /** The first index of sorted [x] whose value is above [at], or `x.size`. */
    private fun firstAbove(
        x: DoubleArray,
        at: Double,
    ): Int {
        var lo = 0
        var hi = x.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (x[mid] > at) hi = mid else lo = mid + 1
        }
        return lo
    }

    private class ElevationChange(
        val gain: Double,
        val loss: Double,
    )

    /** The course's cleaned altitude at the samples that have one, against distance from the start. */
    private class Altitude(
        private val x: DoubleArray,
        private val alt: DoubleArray,
    ) {
        fun at(distance: Double): Double = interpolate(x, alt, distance)

        /** The climb and descent between [from] and [to]: over the samples strictly between them and both ends. */
        fun change(
            from: Double,
            to: Double,
        ): ElevationChange {
            var gain = 0.0
            var loss = 0.0
            var previous = at(from)
            var j = firstAbove(x, from)
            while (j < x.size && x[j] < to) {
                val step = alt[j] - previous
                if (step > 0) gain += step else loss -= step
                previous = alt[j]
                j++
            }
            val step = at(to) - previous
            if (step > 0) gain += step else loss -= step
            return ElevationChange(gain, loss)
        }

        /** Every sample when there are few, else [PROFILE_POINTS] evenly spaced through them, ends included. */
        fun profile(): List<ProfilePoint> {
            if (x.size <= PROFILE_POINTS) return x.indices.map { ProfilePoint(x[it], alt[it]) }
            return (0 until PROFILE_POINTS).map {
                val j = Math.round(it.toDouble() * (x.size - 1) / (PROFILE_POINTS - 1)).toInt()
                ProfilePoint(x[j], alt[j])
            }
        }

        companion object {
            /** The samples of [altitude] (parallel to [x]) that have one, or null when none does. */
            fun of(
                altitude: List<Double?>,
                x: DoubleArray,
            ): Altitude? {
                val present = altitude.indices.filter { altitude[it] != null }
                if (present.isEmpty()) return null
                return Altitude(
                    DoubleArray(present.size) { x[present[it]] },
                    DoubleArray(present.size) { altitude[present[it]]!! },
                )
            }
        }
    }
}
