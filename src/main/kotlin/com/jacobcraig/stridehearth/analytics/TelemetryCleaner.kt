package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.system.fit.FitTelemetryMapper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * **Telemetry Cleaning**: turns an Activity's stored Activity Details into **Cleaned Activity Details**
 * ([CleanedTelemetry]) by removing GPS spikes, heart-rate dropouts and cadence-lock, and altitude noise.
 *
 * A pure function with no persistence: derived analytics call it each time they compute, and the stored
 * Activity Details are never modified (ADR-0013). Best Efforts do not use it; they scan the recorded distance.
 *
 * The pass, in order:
 * 1. **Base series.** Speed is `enhancedSpeed ?: speed` and altitude is `enhancedAltitude ?: altitude`, sample
 *    by sample. Details that lack distance get the parser's synthesised distance. When no speed was recorded
 *    at all, speed is the distance covered since the previous sample.
 * 2. **Without any GPS fix** the base speed, distance and altitude are returned as they are; only the
 *    heart-rate rules apply.
 * 3. **Speed.** Samples below zero or above the sport's ceiling ([maxSpeedMetresPerSecond]) go missing.
 * 4. **Distance.** A step between two samples is bad when time does not advance, distance goes backwards, or
 *    the step implies a speed above the ceiling (a GPS teleport). Only the bad step's excess is removed: it is
 *    replaced by the distance the cleaned speed covers, and every later sample shifts by the difference.
 * 5. **Heart rate.** Missing when it is 0, when it tracks and follows step rate (cadence-lock), or when it steps faster
 *    than [MAX_HR_STEP_BPM_PER_SECOND].
 * 6. **Altitude.** The mean over a centred [ALTITUDE_WINDOW_METERS] window of cleaned distance.
 *
 * Missing speed and heart-rate samples are interpolated linearly in time when the good samples either side
 * are at most [MAX_INTERPOLATION_GAP_SECONDS] apart; longer gaps, and gaps at either end, stay missing.
 */
object TelemetryCleaner {
    /** Heart rate may change by at most this many beats per minute for each second since the last good sample. */
    const val MAX_HR_STEP_BPM_PER_SECOND = 30

    /** Heart rate within this many beats per minute of step rate counts as tracking it. */
    const val CADENCE_LOCK_TOLERANCE_BPM = 3

    /** Heart rate must track step rate for at least this long to be cadence-lock. */
    const val CADENCE_LOCK_MIN_SECONDS = 60

    /** A pause in recording longer than this ends a stretch of heart rate tracking step rate. */
    const val CADENCE_LOCK_BREAK_GAP_SECONDS = 10

    /**
     * A change in heart rate of at least this many beats per minute within [CADENCE_LOCK_JUMP_WINDOW_SECONDS]
     * of a stretch's start or end is the sensor locking onto, or letting go of, step rate.
     */
    const val CADENCE_LOCK_JUMP_BPM = 10

    /** How far either side of a stretch tracking step rate heart rate is examined for a jump. */
    const val CADENCE_LOCK_JUMP_WINDOW_SECONDS = 5

    /** Step rate must vary by at least this standard deviation over a stretch for a correlation to mean anything. */
    const val CADENCE_LOCK_MIN_STEP_RATE_SD = 2.0

    /** Heart rate correlating with step rate at least this strongly over a stretch is following it. */
    const val CADENCE_LOCK_MIN_CORRELATION = 0.6

    /** The longest gap, measured between the good samples either side, that is interpolated. */
    const val MAX_INTERPOLATION_GAP_SECONDS = 10

    /** Full width of the centred distance window altitude is averaged over. */
    const val ALTITUDE_WINDOW_METERS = 15.0

    /**
     * The fastest speed a GPS can plausibly report for [sport], in metres per second, or `null` when the sport
     * has no ceiling. A Workout is the fallback for unrecognised sports, so nothing is safe to reject there.
     */
    fun maxSpeedMetresPerSecond(sport: ActivitySport): Double? =
        when (sport) {
            ActivitySport.RUN -> 8.5
            ActivitySport.WALK, ActivitySport.HIKE -> 5.0
            ActivitySport.RIDE -> 30.0
            ActivitySport.SWIM -> 3.0
            ActivitySport.WORKOUT -> null
        }

    /** Cleans [details] for an Activity of [sport]. Never modifies [details] and shares no array with it. */
    fun clean(
        details: FitTelemetry,
        sport: ActivitySport,
    ): CleanedTelemetry {
        val t = details.t.copyOf()
        val n = t.size
        val distance = baseDistance(details)
        val speed = baseSpeed(details, distance)
        val altitude = DoubleArray(n) { (details.enhancedAltitude.getOrNull(it) ?: details.altitude.getOrNull(it)).orMissing() }
        val heartRate = DoubleArray(n) { details.heartRate.getOrNull(it)?.toDouble() ?: MISSING }

        val hasGps = details.routeIndex.any { it != null }
        var cleanedAltitude = altitude
        if (hasGps) {
            val maxSpeed = maxSpeedMetresPerSecond(sport)
            rejectImplausibleSpeed(speed, maxSpeed)
            interpolateShortGaps(t, speed)
            repairDistance(t, distance, speed, maxSpeed)
            cleanedAltitude = smoothAltitudeOverDistance(altitude, distance)
        }

        rejectHeartRateDropouts(heartRate)
        if (sport in CADENCE_LOCK_SPORTS) rejectCadenceLock(t, heartRate, details)
        rejectHeartRateSteps(t, heartRate)
        interpolateShortGaps(t, heartRate)

        return CleanedTelemetry(
            t = t,
            speed = speed.toNullableFloats(),
            distance = distance.toNullableFloats(),
            heartRate = Array(n) { if (heartRate[it].isNaN()) null else heartRate[it].roundToInt().toShort() },
            altitude = cleanedAltitude.toNullableFloats(),
            hasGps = hasGps,
        )
    }

    /** Recorded distance, or the parser's synthesised distance when the details lack it. Always a fresh array. */
    private fun baseDistance(details: FitTelemetry): DoubleArray {
        val n = details.t.size
        val distance = Array(n) { details.distance.getOrNull(it) }
        FitTelemetryMapper.synthesizeDistanceIfAbsent(
            t = details.t,
            lons = details.lons,
            lats = details.lats,
            routeIndex = details.routeIndex,
            enhancedSpeed = details.enhancedSpeed,
            speed = details.speed,
            distance = distance,
        )
        return DoubleArray(n) { distance[it].orMissing() }
    }

    /** `enhancedSpeed ?: speed` per sample; derived from [distance] when no speed sample was recorded at all. */
    private fun baseSpeed(
        details: FitTelemetry,
        distance: DoubleArray,
    ): DoubleArray {
        val t = details.t
        val speed = DoubleArray(t.size) { (details.enhancedSpeed.getOrNull(it) ?: details.speed.getOrNull(it)).orMissing() }
        if (speed.any { !it.isNaN() }) return speed
        for (i in 1 until t.size) {
            val dt = t[i] - t[i - 1]
            if (dt > 0 && !distance[i].isNaN() && !distance[i - 1].isNaN()) {
                speed[i] = (distance[i] - distance[i - 1]) / dt
            }
        }
        return speed
    }

    private fun rejectImplausibleSpeed(
        speed: DoubleArray,
        maxSpeed: Double?,
    ) {
        for (i in speed.indices) {
            if (speed[i] < 0.0 || (maxSpeed != null && speed[i] > maxSpeed)) speed[i] = MISSING
        }
    }

    /**
     * Fills each run of missing samples by linear interpolation in time between the good samples either side,
     * when those are at most [MAX_INTERPOLATION_GAP_SECONDS] apart. Never extrapolates.
     */
    private fun interpolateShortGaps(
        t: IntArray,
        values: DoubleArray,
    ) {
        var previousGood = -1
        for (i in values.indices) {
            if (values[i].isNaN()) continue
            val span = if (previousGood >= 0) t[i] - t[previousGood] else 0
            if (previousGood >= 0 && i - previousGood > 1 && span in 1..MAX_INTERPOLATION_GAP_SECONDS) {
                val from = values[previousGood]
                val slope = (values[i] - from) / span
                for (j in previousGood + 1 until i) values[j] = from + slope * (t[j] - t[previousGood])
            }
            previousGood = i
        }
    }

    /**
     * Removes the excess of every bad step from [distance] in place, leaving good steps exactly as recorded.
     *
     * A bad step is replaced by the time it took multiplied by the mean cleaned [speed] at its two ends; when
     * either is missing, by the mean rate of the nearest good step on each side; and by nothing when there is
     * no good step at all. Samples before the first bad step keep their recorded value bit for bit.
     */
    private fun repairDistance(
        t: IntArray,
        distance: DoubleArray,
        speed: DoubleArray,
        maxSpeed: Double?,
    ) {
        val present = distance.indices.filter { !distance[it].isNaN() }
        if (present.size < 2) return

        // Step s runs from present[s - 1] to present[s]; its rate is missing when the step is bad.
        val rate = DoubleArray(present.size) { MISSING }
        var anyBad = false
        for (s in 1 until present.size) {
            val dt = t[present[s]] - t[present[s - 1]]
            val dd = distance[present[s]] - distance[present[s - 1]]
            if (dt > 0 && dd >= 0.0 && (maxSpeed == null || dd / dt <= maxSpeed)) rate[s] = dd / dt else anyBad = true
        }
        if (!anyBad) return

        val goodRateBefore = nearestGoodRates(rate, 1 until rate.size)
        val goodRateAfter = nearestGoodRates(rate, rate.size - 1 downTo 1)

        var offset = 0.0
        var previousCleaned = distance[present[0]]
        for (s in 1 until present.size) {
            val from = present[s - 1]
            val to = present[s]
            if (rate[s].isNaN()) {
                val dt = max(t[to] - t[from], 0)
                val replacementRate =
                    if (!speed[from].isNaN() && !speed[to].isNaN()) {
                        (speed[from] + speed[to]) / 2
                    } else {
                        meanOfPresent(goodRateBefore[s], goodRateAfter[s])
                    }
                // `distance[from]` is already cleaned, so add back the offset to recover the recorded step.
                val recordedStep = distance[to] - (distance[from] + offset)
                offset += recordedStep - replacementRate * dt
            }
            // Guard against rounding ever making the cleaned series dip.
            previousCleaned = if (offset == 0.0) distance[to] else max(distance[to] - offset, previousCleaned)
            distance[to] = previousCleaned
        }
    }

    /** For each step, the rate of the nearest good step met earlier when walking [order]; missing if none. */
    private fun nearestGoodRates(
        rate: DoubleArray,
        order: IntProgression,
    ): DoubleArray {
        val nearest = DoubleArray(rate.size) { MISSING }
        var last = MISSING
        for (s in order) {
            nearest[s] = last
            if (!rate[s].isNaN()) last = rate[s]
        }
        return nearest
    }

    private fun meanOfPresent(
        a: Double,
        b: Double,
    ): Double =
        when {
            a.isNaN() && b.isNaN() -> 0.0
            a.isNaN() -> b
            b.isNaN() -> a
            else -> (a + b) / 2
        }

    private fun rejectHeartRateDropouts(heartRate: DoubleArray) {
        for (i in heartRate.indices) if (heartRate[i] <= 0.0) heartRate[i] = MISSING
    }

    /**
     * Removes cadence-lock: an optical sensor reporting the athlete's step rate as heart rate.
     *
     * Stored cadence is strides per minute of one leg with the half-step in `fractionalCadence`, so step rate
     * is twice their sum. A lock is an unbroken stretch of samples, each with heart rate within
     * [CADENCE_LOCK_TOLERANCE_BPM] of step rate, that spans at least [CADENCE_LOCK_MIN_SECONDS], and in which
     * heart rate follows cadence rather than merely coinciding with it ([followsStepRate]). A sample without
     * heart rate or cadence, or a recording pause over [CADENCE_LOCK_BREAK_GAP_SECONDS], ends a stretch.
     */
    private fun rejectCadenceLock(
        t: IntArray,
        heartRate: DoubleArray,
        details: FitTelemetry,
    ) {
        val stepRate =
            DoubleArray(t.size) { i ->
                details.cadence.getOrNull(i)?.let { 2.0 * (it + (details.fractionalCadence.getOrNull(i) ?: 0.0f)) } ?: MISSING
            }

        fun tracksStepRate(i: Int): Boolean =
            !heartRate[i].isNaN() && !stepRate[i].isNaN() && abs(heartRate[i] - stepRate[i]) <= CADENCE_LOCK_TOLERANCE_BPM

        var first = -1
        for (i in 0..t.size) {
            val continues =
                i < t.size && tracksStepRate(i) && (first < 0 || t[i] - t[i - 1] <= CADENCE_LOCK_BREAK_GAP_SECONDS)
            if (continues) {
                if (first < 0) first = i
                continue
            }
            if (first >= 0 && t[i - 1] - t[first] >= CADENCE_LOCK_MIN_SECONDS && followsStepRate(t, heartRate, stepRate, first, i)) {
                for (j in first until i) heartRate[j] = MISSING
            }
            // A sample that tracks step rate but follows a long pause starts the next stretch.
            first = if (i < t.size && tracksStepRate(i)) i else -1
        }
    }

    /**
     * Whether heart rate over the stretch `[first, end)` follows step rate, so that the stretch is a lock and not
     * a true heart rate that happens to sit at step rate (#52). It does when any of these holds:
     * - heart rate jumps by at least [CADENCE_LOCK_JUMP_BPM] within [CADENCE_LOCK_JUMP_WINDOW_SECONDS] of either
     *   end of the stretch: the sensor locking on or letting go;
     * - step rate varies over the stretch (standard deviation at least [CADENCE_LOCK_MIN_STEP_RATE_SD]) and heart
     *   rate correlates with it at least [CADENCE_LOCK_MIN_CORRELATION];
     * - there is no heart rate within the window on either side to tell, as when a lock spans the whole recording.
     *
     * A steady heart rate that drifts into and out of a steady step rate passes none of these and is kept.
     */
    private fun followsStepRate(
        t: IntArray,
        heartRate: DoubleArray,
        stepRate: DoubleArray,
        first: Int,
        end: Int,
    ): Boolean {
        val last = end - 1
        val before = (first - 1 downTo 0).takeWhile { t[first] - t[it] <= CADENCE_LOCK_JUMP_WINDOW_SECONDS }
        val after = (end until t.size).takeWhile { t[it] - t[last] <= CADENCE_LOCK_JUMP_WINDOW_SECONDS }
        val entry = before.map { heartRate[it] }.filterNot { it.isNaN() }
        val exit = after.map { heartRate[it] }.filterNot { it.isNaN() }
        if (entry.isEmpty() && exit.isEmpty()) return true
        if (entry.any { abs(it - heartRate[first]) >= CADENCE_LOCK_JUMP_BPM }) return true
        if (exit.any { abs(it - heartRate[last]) >= CADENCE_LOCK_JUMP_BPM }) return true
        return correlation(heartRate, stepRate, first, end)?.let { it >= CADENCE_LOCK_MIN_CORRELATION } ?: false
    }

    /**
     * Pearson correlation of heart rate with step rate over `[first, end)`, or null when step rate's standard
     * deviation is below [CADENCE_LOCK_MIN_STEP_RATE_SD] (too steady to say) or heart rate does not vary at all.
     */
    private fun correlation(
        heartRate: DoubleArray,
        stepRate: DoubleArray,
        first: Int,
        end: Int,
    ): Double? {
        val n = end - first
        var meanHr = 0.0
        var meanSr = 0.0
        for (i in first until end) {
            meanHr += heartRate[i] / n
            meanSr += stepRate[i] / n
        }
        var covariance = 0.0
        var varianceHr = 0.0
        var varianceSr = 0.0
        for (i in first until end) {
            val dHr = heartRate[i] - meanHr
            val dSr = stepRate[i] - meanSr
            covariance += dHr * dSr
            varianceHr += dHr * dHr
            varianceSr += dSr * dSr
        }
        if (sqrt(varianceSr / n) < CADENCE_LOCK_MIN_STEP_RATE_SD || varianceHr == 0.0) return null
        return covariance / sqrt(varianceHr * varianceSr)
    }

    /**
     * Rejects each sample that differs from the last accepted one by more than [MAX_HR_STEP_BPM_PER_SECOND]
     * for every second between them. The first sample present is always accepted.
     */
    private fun rejectHeartRateSteps(
        t: IntArray,
        heartRate: DoubleArray,
    ) {
        var lastAccepted = -1
        for (i in heartRate.indices) {
            if (heartRate[i].isNaN()) continue
            if (lastAccepted >= 0) {
                val allowed = MAX_HR_STEP_BPM_PER_SECOND.toDouble() * max(t[i] - t[lastAccepted], 0)
                if (abs(heartRate[i] - heartRate[lastAccepted]) > allowed) {
                    heartRate[i] = MISSING
                    continue
                }
            }
            lastAccepted = i
        }
    }

    /**
     * The mean of the altitude samples whose cleaned [distance] lies within half of [ALTITUDE_WINDOW_METERS]
     * either side of each sample. Missing where the sample has no distance or its window has no altitude.
     * Returns [altitude] unsmoothed when there is no distance at all. Runs in O(N): [distance] never
     * decreases, so both window edges only move forwards.
     */
    private fun smoothAltitudeOverDistance(
        altitude: DoubleArray,
        distance: DoubleArray,
    ): DoubleArray {
        val present = distance.indices.filter { !distance[it].isNaN() }
        if (present.isEmpty()) return altitude

        val halfWindow = ALTITUDE_WINDOW_METERS / 2
        val smoothed = DoubleArray(altitude.size) { MISSING }
        var lo = 0
        var hi = 0
        var sum = 0.0
        var count = 0
        for (centre in present) {
            while (hi < present.size && distance[present[hi]] - distance[centre] <= halfWindow) {
                val entering = altitude[present[hi]]
                if (!entering.isNaN()) {
                    sum += entering
                    count++
                }
                hi++
            }
            while (distance[centre] - distance[present[lo]] > halfWindow) {
                val leaving = altitude[present[lo]]
                if (!leaving.isNaN()) {
                    sum -= leaving
                    count--
                }
                lo++
            }
            // An empty window resets the running sum so rounding residue cannot build up across the Activity.
            if (count > 0) smoothed[centre] = sum / count else sum = 0.0
        }
        return smoothed
    }

    private fun Float?.orMissing(): Double = this?.toDouble() ?: MISSING

    private fun DoubleArray.toNullableFloats(): Array<Float?> = Array(size) { if (this[it].isNaN()) null else this[it].toFloat() }

    private val CADENCE_LOCK_SPORTS = setOf(ActivitySport.RUN, ActivitySport.WALK, ActivitySport.HIKE)

    /** Marks a sample with no value in the working series; never leaves this object. */
    private const val MISSING = Double.NaN
}
