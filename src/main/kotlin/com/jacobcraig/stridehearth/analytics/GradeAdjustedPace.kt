package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport

/**
 * **Grade-Adjusted Pace (GAP)**: the speed an athlete would have held on flat ground for the same effort,
 * per sample of the **Cleaned Activity Details** (ADR-0013).
 *
 * Each sample's speed is scaled by Minetti's metabolic cost of running at that sample's grade relative to the
 * cost on the flat: `gapSpeed = speed x C(clamp(grade, -MAX_GRADE, +MAX_GRADE)) / FLAT_COST`, where
 * `C(i) = 155.4 i^5 - 30.4 i^4 - 43.3 i^3 + 46.3 i^2 + 19.5 i + 3.6` J/(kg m) (Minetti et al. 2002) and `i`
 * is rise over run. It is used for Runs, Walks and Hikes alike.
 *
 * The grade of sample `k` is the altitude change over the samples between `d[k] - 10 m` and `d[k] + 10 m`
 * of cleaned distance (at least the neighbouring samples either side), read only from samples that have both
 * distance and altitude. It is 0 when sample `k` lacks either, when that span is shorter than
 * [MIN_GRADE_SPAN_METERS], and everywhere for an Activity without a GPS fix, whose barometer drift must not
 * make hills. Without altitude, GAP therefore equals the cleaned speed.
 *
 * A pure function with no persistence.
 */
object GradeAdjustedPace {
    /** Grades steeper than this, up or down, cost as much as this grade. */
    const val MAX_GRADE = 0.45

    /** Grade is measured over this much cleaned distance either side of the sample. */
    const val GRADE_HALF_WINDOW_METERS = 10.0

    /** A grade window covering less distance than this is treated as flat. */
    const val MIN_GRADE_SPAN_METERS = 1.0

    /** Minetti's cost of running on the flat, J/(kg m): `C(0)`. */
    const val FLAT_COST = 3.6

    /** The sports that carry a Grade-Adjusted Pace stream. */
    val GAP_SPORTS: Set<ActivitySport> = setOf(ActivitySport.RUN, ActivitySport.WALK, ActivitySport.HIKE)

    /** Minetti's metabolic cost of running at grade [i] (rise over run), in J/(kg m). */
    fun minettiCost(i: Double): Double = ((((155.4 * i - 30.4) * i - 43.3) * i + 46.3) * i + 19.5) * i + FLAT_COST

    /**
     * The GAP stream of [cleaned] in metres per second, parallel to its `t`, with `null` where cleaned speed is
     * missing; `null` as a whole when no sample has a cleaned speed.
     */
    fun gapSpeed(cleaned: CleanedTelemetry): Array<Float?>? {
        if (cleaned.speed.none { it != null }) return null
        val grade = grades(cleaned)
        return Array(cleaned.t.size) { k ->
            val speed = cleaned.speed.getOrNull(k) ?: return@Array null
            (speed * minettiCost(grade[k].coerceIn(-MAX_GRADE, MAX_GRADE)) / FLAT_COST).toFloat()
        }
    }

    private fun grades(cleaned: CleanedTelemetry): DoubleArray {
        val n = cleaned.t.size
        val grade = DoubleArray(n)
        if (!cleaned.hasGps) return grade

        // The samples with both distance and altitude, in order; cleaned distance never decreases with a GPS fix.
        val usable = (0 until n).filter { cleaned.distance.getOrNull(it) != null && cleaned.altitude.getOrNull(it) != null }
        val d = DoubleArray(usable.size) { cleaned.distance[usable[it]]!!.toDouble() }
        val alt = DoubleArray(usable.size) { cleaned.altitude[usable[it]]!!.toDouble() }

        var lo = 0
        var hi = 0
        for (p in usable.indices) {
            while (d[lo] < d[p] - GRADE_HALF_WINDOW_METERS) lo++
            if (hi < p) hi = p
            while (hi + 1 < usable.size && d[hi + 1] <= d[p] + GRADE_HALF_WINDOW_METERS) hi++
            val from = minOf(lo, (p - 1).coerceAtLeast(0))
            val to = maxOf(hi, (p + 1).coerceAtMost(usable.size - 1))
            val span = d[to] - d[from]
            grade[usable[p]] = if (span >= MIN_GRADE_SPAN_METERS) (alt[to] - alt[from]) / span else 0.0
        }
        return grade
    }
}
