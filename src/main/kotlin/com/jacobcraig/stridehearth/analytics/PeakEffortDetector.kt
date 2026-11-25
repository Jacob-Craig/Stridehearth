package com.jacobcraig.stridehearth.analytics

/**
 * One **Peak Effort**: the highest average of [metric] over one continuous window of [benchmarkDuration] within an
 * Activity. [value] is in m/s for [PeakEffortMetric.GAP_SPEED] and W for [PeakEffortMetric.POWER]. The window runs
 * from [startTimeSeconds] to [endTimeSeconds] (seconds since the Activity's first sample, either end possibly
 * interpolated between samples); [startIndex] and [endIndex] are the samples at or before its start and at or
 * after its end.
 */
data class PeakEffort(
    val metric: PeakEffortMetric,
    val benchmarkDuration: BenchmarkDuration,
    val value: Double,
    val startIndex: Int,
    val endIndex: Int,
    val startTimeSeconds: Double,
    val endTimeSeconds: Double,
)

/**
 * Pure **Peak Effort** detection over one rate stream (ADR-0003's two-pointer sliding window, applied to time).
 *
 * The rate `x` (GAP speed or power) is integrated over wall-clock time into a cumulative series `C`: `C[0] = 0`,
 * and `C[i] = C[i-1] + (x[i] ?: 0) x dt` for the step `dt = t[i] - t[i-1]`, unless
 * `dt > RECORDING_PAUSE_SECONDS`, when `C[i] = C[i-1]`. A sample whose time does not advance past the last kept
 * sample is dropped first, so `t` is strictly increasing; reported indices point into the original stream. Pauses are not removed: a recording pause counts as
 * standing still, and a missing sample as zero, so neither can inflate a peak. `C` is linear between samples.
 *
 * For each [BenchmarkDuration] `D` the peak is the maximum of `(C(s + D) - C(s)) / D`. Because `C` is piecewise
 * linear, the maximum always has one end on a sample, so two passes find it in O(N): pass A anchors the start on
 * each sample and interpolates the end, pass B anchors the end and interpolates the start. The earliest window
 * wins a tie. There is no peak when the stream spans less than `D`, or when the best average is not positive.
 */
object PeakEffortDetector {
    /** A step longer than this is a recording pause (the longest gap Telemetry Cleaning interpolates). */
    const val RECORDING_PAUSE_SECONDS = RunMetrics.RECORDING_PAUSE_SECONDS

    private const val TIE_TOLERANCE = 1e-9

    /** The Peak Efforts of [rate] (parallel to [t]) for every Benchmark Duration it spans, labelled [metric]. */
    fun detect(
        t: IntArray,
        rate: Array<out Number?>,
        metric: PeakEffortMetric,
    ): List<PeakEffort> {
        if (t.size < 2 || rate.size != t.size) return emptyList()
        // Keep only the samples that advance time past the last kept one, as RunMetrics does: a sample stepping
        // back or repeating a time covers no time, and would break the passes' assumption that t increases.
        val kept = advancingSamples(t)
        val n = kept.size
        if (n < 2) return emptyList()
        val keptTime = IntArray(n) { t[kept[it]] }
        val cumulative = DoubleArray(n)
        for (i in 1 until n) {
            val dt = keptTime[i] - keptTime[i - 1]
            val step = if (dt <= RECORDING_PAUSE_SECONDS) (rate[kept[i]]?.toDouble() ?: 0.0) * dt else 0.0
            cumulative[i] = cumulative[i - 1] + step
        }
        val span = keptTime[n - 1] - keptTime[0]
        return BenchmarkDuration.entries
            .filter { it.seconds <= span }
            .mapNotNull { duration -> peakFor(keptTime, cumulative, metric, duration) }
            .map { it.copy(startIndex = kept[it.startIndex], endIndex = kept[it.endIndex]) }
    }

    /** Indices of [t] whose time is later than every kept sample before it; the first sample is always kept. */
    private fun advancingSamples(t: IntArray): IntArray {
        val kept = ArrayList<Int>(t.size)
        kept += 0
        for (i in 1 until t.size) {
            if (t[i] > t[kept.last()]) kept += i
        }
        return kept.toIntArray()
    }

    private fun peakFor(
        t: IntArray,
        cumulative: DoubleArray,
        metric: PeakEffortMetric,
        duration: BenchmarkDuration,
    ): PeakEffort? {
        val n = t.size
        val d = duration.seconds
        var best: PeakEffort? = null

        fun consider(candidate: PeakEffort) {
            val current = best
            best =
                when {
                    current == null -> candidate
                    candidate.value > current.value + TIE_TOLERANCE -> candidate
                    candidate.value >= current.value - TIE_TOLERANCE &&
                        candidate.startTimeSeconds < current.startTimeSeconds - TIE_TOLERANCE -> candidate
                    else -> current
                }
        }

        // Pass A: start on sample `left`, end interpolated between `right - 1` and `right`.
        var right = 0
        for (left in 0 until n) {
            val end = t[left] + d
            if (end > t[n - 1]) break
            while (t[right] < end) right++
            val endValue = interpolate(t, cumulative, right, end.toDouble())
            consider(
                PeakEffort(metric, duration, (endValue - cumulative[left]) / d, left, right, t[left].toDouble(), end.toDouble()),
            )
        }

        // Pass B: end on sample `rightSample`, start interpolated between `left` and `left + 1`.
        var left = 0
        for (rightSample in 0 until n) {
            val start = t[rightSample] - d
            if (start < t[0]) continue
            while (left + 1 < n && t[left + 1] <= start) left++
            val startValue = if (t[left] == start) cumulative[left] else interpolate(t, cumulative, left + 1, start.toDouble())
            consider(
                PeakEffort(
                    metric,
                    duration,
                    (cumulative[rightSample] - startValue) / d,
                    left,
                    rightSample,
                    start.toDouble(),
                    t[rightSample].toDouble(),
                ),
            )
        }

        return best?.takeIf { it.value > TIE_TOLERANCE }
    }

    /** `C` at [time], where `t[upper - 1] < time <= t[upper]` (or `time == t[upper]`), linear between samples. */
    private fun interpolate(
        t: IntArray,
        cumulative: DoubleArray,
        upper: Int,
        time: Double,
    ): Double {
        if (upper == 0 || time >= t[upper]) return cumulative[upper]
        val lower = upper - 1
        val fraction = (time - t[lower]) / (t[upper] - t[lower])
        return cumulative[lower] + fraction * (cumulative[upper] - cumulative[lower])
    }
}
