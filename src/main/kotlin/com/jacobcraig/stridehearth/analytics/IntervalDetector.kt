package com.jacobcraig.stridehearth.analytics

/**
 * One **Detected Interval** of a Run: a stretch of its Moving Time the [IntervalDetector] judged to be hard (work) or
 * easy (recovery), found without any lap press.
 *
 * - [startIndex], [endIndex]: the first and last sample of the Activity Details inside the interval, so a chart can
 *   highlight it as it does a Best Effort.
 * - [startTimeSeconds], [endTimeSeconds]: elapsed seconds of its first and last Moving Time second (end exclusive), so
 *   consecutive intervals meet. A traffic stop inside an interval is inside its span, but not in [movingSeconds].
 * - [movingSeconds]: the seconds of Moving Time it holds.
 * - [distanceMeters]: the cleaned distance covered between its first and last sample; null without distance.
 * - [avgGap]: mean **Grade-Adjusted Pace** speed in m/s; [avgHr]: mean heart rate in bpm and [avgPower]: mean power in W,
 *   each null when it covers less than [RunMetrics.MIN_COVERAGE] of the seconds.
 * - [decouplingPct]: the interval's own **Aerobic Decoupling**, `(EF1 - EF2) / EF1 x 100` over its two halves (null for
 *   a recovery, an interval shorter than [IntervalDetector.MIN_DECOUPLING_SECONDS], or heart rate missing in a half).
 */
data class DetectedInterval(
    val startIndex: Int,
    val endIndex: Int,
    val startTimeSeconds: Double,
    val endTimeSeconds: Double,
    val movingSeconds: Int,
    val isWork: Boolean,
    val distanceMeters: Double?,
    val avgGap: Double,
    val avgHr: Double?,
    val avgPower: Double?,
    val decouplingPct: Double?,
)

/**
 * Deterministic **Detected Interval** detection (#47): a step-change and hysteresis detector over the smoothed
 * Grade-Adjusted Pace speed of a Run's Moving Time, or over its power when most of it has power. No lap presses and
 * no model.
 *
 * 1. The signal is the Run's moving series ([MovingSeries]): seconds standing still (cleaned speed below
 *    [RunMetrics.MOVING_SPEED_MPS], a traffic stop) or without GAP are dropped, so a stop never makes a recovery.
 * 2. It is smoothed with a centred mean of [SMOOTHING_SECONDS], each stretch between two dropped stops on its own, so a
 *    stop never blurs the edge next to it.
 * 3. The two levels (hard and easy) are the classes of the best two-class split of the smoothed values (Otsu's
 *    method). When the class means differ by less than [MIN_CONTRAST] of the harder one the Run is steady and has no
 *    Detected Intervals, so a warm-up or drift is not an interval.
 * 4. A hysteresis comparator walks the signal: it turns to work when the signal rises [HYSTERESIS_FRACTION] of the gap
 *    between the levels above their midpoint, and back to recovery when it falls as far below it.
 * 5. Segments shorter than [MIN_SEGMENT_SECONDS] are absorbed by their neighbours, so the short dips of braking into a
 *    stop and accelerating out of it, and a single stride, do not count.
 *
 * A Run without a work segment has no Detected Intervals at all. No Spring and no I/O.
 */
object IntervalDetector {
    /** The Moving Time below which a Run is too short to hold a work and a recovery interval. */
    const val MIN_MOVING_SECONDS = 300

    /** Length of the centred mean the signal is smoothed with. */
    const val SMOOTHING_SECONDS = 15

    /** The least relative gap between the hard and easy level, `(hard - easy) / hard`, for a Run to have intervals. */
    const val MIN_CONTRAST = 0.20

    /** How far past the midpoint, as a fraction of the gap between the levels, the signal must go to switch. */
    const val HYSTERESIS_FRACTION = 0.10

    /** The shortest work or recovery interval, in Moving Time seconds. */
    const val MIN_SEGMENT_SECONDS = 20

    /** The shortest work interval that reports **Aerobic Decoupling**: heart rate lags too much in a shorter one. */
    const val MIN_DECOUPLING_SECONDS = 120

    /**
     * The **Detected Intervals** of a Run from its [cleaned] details, its Grade-Adjusted Pace [gapSpeed] and its
     * (plausible) [power], both parallel to `cleaned.t`, in order and tiling its Moving Time; empty when it has none.
     */
    fun detect(
        cleaned: CleanedTelemetry,
        gapSpeed: Array<Float?>,
        power: Array<Short?>,
    ): List<DetectedInterval> {
        val series = MovingSeries.of(cleaned, gapSpeed, power)
        val n = series.size
        if (n < MIN_MOVING_SECONDS) return emptyList()
        val signal = if (series.powerCovers(0, n)) series.power.map { it ?: 0.0 }.toDoubleArray() else series.gap
        val smoothed = smooth(signal, series.time)
        val (easy, hard) = levels(smoothed) ?: return emptyList()
        if ((hard - easy) / hard < MIN_CONTRAST) return emptyList()
        val segments = absorbShort(comparator(smoothed, easy, hard))
        if (segments.none { it.isWork } || segments.size < 2) return emptyList()
        return segments.map { build(it, series, cleaned) }
    }

    /** A run of moving seconds `[from, to)` of one kind. */
    private data class Segment(
        val from: Int,
        val to: Int,
        val isWork: Boolean,
    ) {
        val length get() = to - from
    }

    /** [signal] smoothed within each stretch of consecutive elapsed seconds in [time]: a jump in [time] is a dropped stop. */
    private fun smooth(
        signal: DoubleArray,
        time: IntArray,
    ): DoubleArray {
        val half = SMOOTHING_SECONDS / 2
        val prefix = DoubleArray(signal.size + 1)
        for (i in signal.indices) prefix[i + 1] = prefix[i] + signal[i]
        val stretchStart = IntArray(signal.size)
        val stretchEnd = IntArray(signal.size)
        var start = 0
        for (i in signal.indices) {
            if (i > 0 && time[i] - time[i - 1] > 1) start = i
            stretchStart[i] = start
        }
        var end = signal.size
        for (i in signal.indices.reversed()) {
            if (i < signal.lastIndex && time[i + 1] - time[i] > 1) end = i + 1
            stretchEnd[i] = end
        }
        return DoubleArray(signal.size) { i ->
            val from = maxOf(stretchStart[i], i - half)
            val to = minOf(stretchEnd[i], i + half + 1)
            (prefix[to] - prefix[from]) / (to - from)
        }
    }

    /** The mean of the easy and of the hard class of the best two-class split of [values] (Otsu), or null if all equal. */
    private fun levels(values: DoubleArray): Pair<Double, Double>? {
        val sorted = values.sortedArray()
        val total = sorted.sum()
        var below = 0.0
        var bestVariance = 0.0
        var bestSplit = -1
        for (k in 1 until sorted.size) {
            below += sorted[k - 1]
            if (sorted[k] == sorted[k - 1]) continue
            val meanBelow = below / k
            val meanAbove = (total - below) / (sorted.size - k)
            val variance = k.toDouble() * (sorted.size - k) * (meanAbove - meanBelow) * (meanAbove - meanBelow)
            if (variance > bestVariance) {
                bestVariance = variance
                bestSplit = k
            }
        }
        if (bestSplit < 0) return null
        val easy = sorted.take(bestSplit).average()
        val hard = sorted.drop(bestSplit).average()
        return if (hard > 0) easy to hard else null
    }

    private fun comparator(
        smoothed: DoubleArray,
        easy: Double,
        hard: Double,
    ): List<Segment> {
        val middle = (easy + hard) / 2
        val band = HYSTERESIS_FRACTION * (hard - easy)
        var work = smoothed[0] > middle
        var from = 0
        val segments = ArrayList<Segment>()
        for (i in 1 until smoothed.size) {
            val switch = if (work) smoothed[i] < middle - band else smoothed[i] > middle + band
            if (!switch) continue
            segments += Segment(from, i, work)
            from = i
            work = !work
        }
        segments += Segment(from, smoothed.size, work)
        return segments
    }

    /**
     * Repeatedly turns the shortest segment under [MIN_SEGMENT_SECONDS] into its neighbours' kind and merges them
     * (a segment at the edge joins its one neighbour), until none is left.
     */
    private fun absorbShort(segments: List<Segment>): List<Segment> {
        val list = segments.toMutableList()
        while (list.size > 1) {
            val shortest = list.indices.minBy { list[it].length }
            if (list[shortest].length >= MIN_SEGMENT_SECONDS) break
            val first = maxOf(shortest - 1, 0)
            val last = minOf(shortest + 1, list.lastIndex)
            // The kind of the neighbour(s); at an edge, the one neighbour's.
            val isWork = list[if (shortest == 0) 1 else shortest - 1].isWork
            val merged = Segment(list[first].from, list[last].to, isWork)
            repeat(last - first + 1) { list.removeAt(first) }
            list.add(first, merged)
        }
        return list
    }

    private fun build(
        segment: Segment,
        series: MovingSeries,
        cleaned: CleanedTelemetry,
    ): DetectedInterval {
        val t = cleaned.t
        val startTime = series.time[segment.from]
        val endTime = series.time[segment.to - 1] + 1
        // The first sample at or after the start, and the last sample before the end (or the start's, if none).
        val startIndex = t.indexOfFirst { it >= startTime }.coerceAtLeast(0)
        val endIndex = maxOf(startIndex, t.indexOfLast { it < endTime })
        val startDistance = cleaned.distance.getOrNull(startIndex)
        val endDistance = cleaned.distance.getOrNull(endIndex)
        return DetectedInterval(
            startIndex = startIndex,
            endIndex = endIndex,
            startTimeSeconds = startTime.toDouble(),
            endTimeSeconds = endTime.toDouble(),
            movingSeconds = segment.length,
            isWork = segment.isWork,
            distanceMeters = if (startDistance != null && endDistance != null) (endDistance - startDistance).toDouble() else null,
            avgGap = mean(series.gap, segment.from, segment.to),
            avgHr = series.heartRate.takeIf { series.heartRateCovers(segment.from, segment.to) }?.let { meanOfPresent(it, segment) },
            avgPower = series.power.takeIf { series.powerCovers(segment.from, segment.to) }?.let { meanOfPresent(it, segment) },
            decouplingPct = if (segment.isWork) decoupling(series, segment) else null,
        )
    }

    private fun decoupling(
        series: MovingSeries,
        segment: Segment,
    ): Double? {
        if (segment.length < MIN_DECOUPLING_SECONDS) return null
        val mid = segment.from + segment.length / 2
        if (!series.heartRateCovers(segment.from, mid) || !series.heartRateCovers(mid, segment.to)) return null
        val first = efficiency(series, segment.from, mid)
        val second = efficiency(series, mid, segment.to)
        return (first - second) / first * 100
    }

    /** Mean GAP speed over mean heart rate in [from, to). */
    private fun efficiency(
        series: MovingSeries,
        from: Int,
        to: Int,
    ): Double = mean(series.gap, from, to) / (from until to).mapNotNull { series.heartRate[it] }.average()

    private fun mean(
        values: DoubleArray,
        from: Int,
        to: Int,
    ): Double = (from until to).sumOf { values[it] } / (to - from)

    private fun meanOfPresent(
        values: Array<Double?>,
        segment: Segment,
    ): Double = (segment.from until segment.to).mapNotNull { values[it] }.average()
}
