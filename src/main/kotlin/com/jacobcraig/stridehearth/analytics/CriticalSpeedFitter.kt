package com.jacobcraig.stridehearth.analytics

/** Whether a **Critical Speed** fit published a model, and if not, why not. */
enum class CriticalSpeedStatus {
    /** A model: the fit's values are published. */
    MODEL,

    /** Fewer than [CriticalSpeedFitter.MIN_POINTS] Peak Efforts to fit. */
    INSUFFICIENT_POINTS,

    /** The line fits the points with R² below [CriticalSpeedFitter.MIN_R_SQUARED]. */
    POOR_FIT,

    /**
     * The line fits, but with a critical value at or below zero, or a reserve below [CriticalSpeedFitter.MIN_D_PRIME]
     * (or [CriticalSpeedFitter.MIN_W_PRIME] for power): no athlete has the first, and the second comes from
     * sub-maximal efforts, such as a single steady easy run, rather than from their limits.
     */
    NON_PHYSICAL,
}

/**
 * One 2-parameter fit. [criticalValue] is **Critical Speed** (m/s) or **Critical Power** (W), [reserveCapacity] is
 * **D′** (m) or **W′** (J); both are null unless [status] is [CriticalSpeedStatus.MODEL]. [rSquared] is the fit
 * quality, reported whenever there were enough points to fit a line; [pointCount] how many points were fitted.
 */
data class CriticalSpeedFit(
    val status: CriticalSpeedStatus,
    val criticalValue: Double?,
    val reserveCapacity: Double?,
    val rSquared: Double?,
    val pointCount: Int,
)

/**
 * The 2-parameter hyperbolic model `D(t) = CS·t + D′` (#39), fitted by ordinary least squares of distance on time
 * over an athlete's best Peak Efforts at the [FIT_DURATIONS]. A Peak Effort of speed `v` (m/s) over `t` seconds
 * covers `d = v·t` metres; the slope of `d` on `t` is **Critical Speed** and the intercept is **D′**. Power peaks
 * fit the same way, `w = P·t` joules, giving **Critical Power** and **W′**.
 *
 * Distance grows with time whatever the athlete's fitness, so R² in this form is almost always high, even for the
 * near-straight `D = v·t` of one steady easy run. A non-positive slope, or an intercept under the reserve floor
 * ([MIN_D_PRIME], [MIN_W_PRIME]), is refused too ([CriticalSpeedStatus.NON_PHYSICAL]), since it comes from
 * sub-maximal efforts: their 2-minute peak barely beats their 20-minute one.
 *
 * No Spring and no I/O.
 */
object CriticalSpeedFitter {
    /** The Benchmark Durations the model is fitted over: those from 2 to 20 minutes. */
    val FIT_DURATIONS: List<BenchmarkDuration> = BenchmarkDuration.entries.filter { it.seconds in 120..1200 }

    /** How many athlete-local days, today included, a model's Peak Efforts are drawn from. */
    const val WINDOW_DAYS = 90

    /** The fewest points a model is published from. */
    const val MIN_POINTS = 3

    /** The lowest R² a model is published with. */
    const val MIN_R_SQUARED = 0.9

    /**
     * The smallest **D′** (m) a speed model is published with. Runners' D′ is typically 150-300 m; less means the
     * peaks were sub-maximal, and would drain Stamina on ordinary runs and propose an easy pace as Threshold Pace.
     */
    const val MIN_D_PRIME = 100.0

    /** The smallest **W′** (J) a power model is published with, [MIN_D_PRIME]'s counterpart for running power. */
    const val MIN_W_PRIME = 5_000.0

    /** The reserve floor for [metric]: [MIN_D_PRIME] for speed, [MIN_W_PRIME] for power. */
    fun minReserve(metric: PeakEffortMetric): Double =
        when (metric) {
            PeakEffortMetric.GAP_SPEED -> MIN_D_PRIME
            PeakEffortMetric.POWER -> MIN_W_PRIME
        }

    /** Fits [points] of [metric], each a duration in seconds and the speed (m/s) or power (W) held over it. */
    fun fit(
        points: List<Pair<Int, Double>>,
        metric: PeakEffortMetric,
    ): CriticalSpeedFit {
        val n = points.size
        if (n < MIN_POINTS) return CriticalSpeedFit(CriticalSpeedStatus.INSUFFICIENT_POINTS, null, null, null, n)
        val t = points.map { it.first.toDouble() }
        val d = points.map { (seconds, value) -> seconds * value }
        val tBar = t.average()
        val dBar = d.average()
        val sxx = t.sumOf { (it - tBar) * (it - tBar) }
        val sxy = t.indices.sumOf { (t[it] - tBar) * (d[it] - dBar) }
        val slope = sxy / sxx
        val intercept = dBar - slope * tBar
        val ssTot = d.sumOf { (it - dBar) * (it - dBar) }
        val ssRes = t.indices.sumOf { (d[it] - (slope * t[it] + intercept)).let { residual -> residual * residual } }
        // Every point covering the same distance leaves nothing to explain: the line is flat, so CS is 0 anyway.
        val rSquared = if (ssTot > 0) 1 - ssRes / ssTot else null
        val status =
            when {
                rSquared != null && rSquared < MIN_R_SQUARED -> CriticalSpeedStatus.POOR_FIT
                !(slope > 0) || !(intercept >= minReserve(metric)) -> CriticalSpeedStatus.NON_PHYSICAL
                else -> CriticalSpeedStatus.MODEL
            }
        return if (status == CriticalSpeedStatus.MODEL) {
            CriticalSpeedFit(status, slope, intercept, rSquared, n)
        } else {
            CriticalSpeedFit(status, null, null, rSquared, n)
        }
    }
}
