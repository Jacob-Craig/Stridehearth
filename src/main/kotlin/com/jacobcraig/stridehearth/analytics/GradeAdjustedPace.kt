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
 * The grade of sample `k` is measured over a long baseline so that altitude noise barely makes hills (#51; see ADR-0013 for the residual GPS-noise bias): it is
 * the slope between two **anchors**, the mean (distance, altitude) of the samples within half an anchor window of
 * `d[k] - GRADE_HALF_BASELINE_METERS` and of `d[k] + GRADE_HALF_BASELINE_METERS` of cleaned distance:
 * `grade = (altAhead - altBehind) / (dAhead - dBehind)`. For an Activity whose samples with altitude span `L` metres,
 * the anchor window is `width = min(GRADE_ANCHOR_WINDOW_METERS, L / 2)` wide, and every anchor centre is kept within
 * `[d first + width / 2, d last - width / 2]` so that its window lies inside the recorded distance. Near the start
 * or end both centres slide inwards together, so the baseline between them stays
 * `min(2 x GRADE_HALF_BASELINE_METERS, L - width)`: 180 m when `L` is at least 240 m, `L - 60` m from 120 m to
 * 240 m, and `L / 2` below 120 m, where the centres are pinned to the two ends. An anchor window with no sample (a
 * gap in the recorded distance) takes the nearest sample outwards from `d[k]`: the last before the window behind,
 * the first after it ahead, so a gap never pulls the anchors closer together. Because each anchor pairs mean altitude
 * with mean distance, a steady climb reads its true grade everywhere, ends included.
 * Only samples with both distance and altitude are read. Grade is 0 when sample `k` lacks either, when the anchors
 * are less than [MIN_GRADE_SPAN_METERS] apart, and everywhere for an Activity without a GPS fix, whose barometer
 * drift must not make hills. Without altitude, GAP therefore equals the cleaned speed.
 *
 * The baseline trades resolution on short climbs for flat ground that stays flat. Each anchor's mean distance lies
 * within half a window of its centre (or beyond it, outwards), so the anchors are always at least the baseline minus
 * one window apart. In an Activity at least 240 m long a 0.2 m barometer step therefore reads as at most
 * 0.2 / 120 = 0.17 % grade (GAP 0.9 % above pace), and as 0.2 / 180 = 0.11 % (GAP 0.6 %) at an even pace, while the
 * middle of a 200 m climb at 5 % still reads within about 1 % of Minetti's ratio for 5 %. In a shorter Activity the
 * baseline is shorter, so the same step reads proportionally steeper (0.2 / 100 = 0.2 % grade at an even pace over
 * 160 m, whose baseline is 100 m).
 *
 * A pure function with no persistence.
 */
object GradeAdjustedPace {
    /** Grades steeper than this, up or down, cost as much as this grade. */
    const val MAX_GRADE = 0.45

    /** Grade is measured between anchors this much cleaned distance behind and ahead of the sample. */
    const val GRADE_HALF_BASELINE_METERS = 90.0

    /** Full width of the cleaned-distance window each grade anchor averages altitude over. */
    const val GRADE_ANCHOR_WINDOW_METERS = 60.0

    /** Grade anchors closer together than this are treated as flat. */
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
        if (usable.isEmpty()) return grade
        val d = DoubleArray(usable.size) { cleaned.distance[usable[it]]!!.toDouble() }
        val width = minOf(GRADE_ANCHOR_WINDOW_METERS, (d.last() - d.first()) / 2)
        val anchors = Anchors(d, DoubleArray(usable.size) { cleaned.altitude[usable[it]]!!.toDouble() }, width)

        // The lowest and highest anchor centres whose windows lie within the recorded distance.
        val first = d.first() + width / 2
        val last = d.last() - width / 2
        for (p in usable.indices) {
            // Near either end the baseline slides inwards whole rather than shrinking; it is cut to fit only when
            // the Activity is shorter than it.
            val shift =
                (first - (d[p] - GRADE_HALF_BASELINE_METERS)).coerceAtLeast(0.0) -
                    (d[p] + GRADE_HALF_BASELINE_METERS - last).coerceAtLeast(0.0)
            val (dBehind, altBehind) = anchors.behind((d[p] - GRADE_HALF_BASELINE_METERS + shift).coerceIn(first, last))
            val (dAhead, altAhead) = anchors.ahead((d[p] + GRADE_HALF_BASELINE_METERS + shift).coerceIn(first, last))
            val span = dAhead - dBehind
            grade[usable[p]] = if (span >= MIN_GRADE_SPAN_METERS) (altAhead - altBehind) / span else 0.0
        }
        return grade
    }

    /** Mean (distance, altitude) over [width]-metre windows of a non-decreasing distance series, from prefix sums. */
    private class Anchors(
        private val d: DoubleArray,
        alt: DoubleArray,
        private val width: Double,
    ) {
        private val sumD = DoubleArray(d.size + 1).also { for (i in d.indices) it[i + 1] = it[i] + d[i] }
        private val sumAlt = DoubleArray(d.size + 1).also { for (i in d.indices) it[i + 1] = it[i] + alt[i] }

        /**
         * The behind anchor centred on [centre]: the mean over the samples within half a window, else the last sample
         * before the window, so that a gap never pulls the anchor towards the sample whose grade it measures.
         */
        fun behind(centre: Double): Pair<Double, Double> = at(centre, outward = { from -> from - 1 })

        /** The ahead anchor centred on [centre]: as [behind], but an empty window takes the first sample past it. */
        fun ahead(centre: Double): Pair<Double, Double> = at(centre, outward = { from -> from })

        private inline fun at(
            centre: Double,
            outward: (Int) -> Int,
        ): Pair<Double, Double> {
            val half = width / 2
            var from = firstAtOrAbove(centre - half)
            var to = firstAbove(centre + half)
            if (from >= to) {
                // No sample in the window: from == to is the first sample past it, and from - 1 the last before it.
                // Both exist, because every anchor window lies within the first and last samples' distances; the
                // clamp only guards against rounding at those ends.
                from = outward(from).coerceIn(0, d.size - 1)
                to = from + 1
            }
            val count = to - from
            return (sumD[to] - sumD[from]) / count to (sumAlt[to] - sumAlt[from]) / count
        }

        private fun firstAtOrAbove(x: Double): Int = search { d[it] >= x }

        private fun firstAbove(x: Double): Int = search { d[it] > x }

        /** The first index at which [isPast] holds, which is monotone over the sorted distances; `d.size` if none. */
        private inline fun search(isPast: (Int) -> Boolean): Int {
            var lo = 0
            var hi = d.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (isPast(mid)) hi = mid else lo = mid + 1
            }
            return lo
        }
    }
}
