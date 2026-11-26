package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.athlete.ThresholdsOn
import io.swagger.v3.oas.annotations.media.Schema
import kotlin.math.abs

/** The **Workout Type** of a Run: what kind of session it was, by [WorkoutTypeClassifier]'s rules. */
@Schema(enumAsRef = true)
enum class WorkoutType(
    val displayName: String,
) {
    EASY("Easy"),
    LONG_RUN("Long Run"),
    TEMPO("Tempo"),
    INTERVALS("Intervals"),
    RACE("Race"),
    ;

    companion object {
        /** The type stored under [name], or null for none, or for one a later version no longer has. */
        fun fromStored(name: String?): WorkoutType? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What one Run's analysis says about its **Workout Type**, before the athlete's other Runs are considered. It depends on
 * the **Thresholds** in force on the Run's date, so it is recomputed with the Training Load when they change, and it
 * is stored with the Run so a neighbour's change can reclassify it without analysing it again.
 *
 * - [thresholdWorkIntervals]: how many of its **Detected Intervals** are work at or above threshold: average pace in
 *   Friel's pace Z4 or faster, or (without a threshold pace) heart rate in Z4 or higher, or (without either) power of
 *   at least [POWER_FRACTION_OF_CP] of Critical Power. With none of those Thresholds, 0.
 * - [tempoSeconds]: seconds of Moving Time in Seiler's zone 2 (Friel Z3-Z4).
 * - [easyShare]: the share of its zoned Moving Time in Seiler's zone 1 (Friel Z1-Z2). Zones are read from heart rate and
 *   LTHR when heart rate covers enough of the Run, else from Grade-Adjusted Pace and threshold pace; null when neither
 *   works.
 * - [raceIntensity]: the Run's average pace over its threshold pace (or average heart rate over LTHR without a
 *   threshold pace); null with neither.
 * - [distanceMeters]: the cleaned distance covered; null without distance.
 */
data class WorkoutEvidence(
    val thresholdWorkIntervals: Int,
    val tempoSeconds: Int,
    val easyShare: Double?,
    val raceIntensity: Double?,
    val distanceMeters: Double?,
) {
    companion object {
        /** Share of Critical Power a work interval must reach when it can only be judged by power. */
        const val POWER_FRACTION_OF_CP = 0.95

        /** Index of Friel's Z4 in the zone lists: its lower bound is where the threshold zone starts. */
        private const val Z4 = 3

        /** Evidence for a Run from its [cleaned] details, GAP, [power] and [intervals], against [thresholds]. */
        internal fun of(
            cleaned: CleanedTelemetry,
            gapSpeed: Array<Float?>,
            power: Array<Short?>,
            intervals: List<DetectedInterval>,
            thresholds: ThresholdsOn,
        ): WorkoutEvidence {
            val moving = MovingSeries.of(cleaned, gapSpeed, power)
            val zones = ZoneSeconds.of(cleaned, gapSpeed, thresholds)
            return WorkoutEvidence(
                thresholdWorkIntervals = intervals.count { it.isWork && atThreshold(it, thresholds) },
                tempoSeconds = zones?.zone2 ?: 0,
                easyShare = zones?.let { it.zone1.toDouble() / it.placed },
                raceIntensity = raceIntensity(moving, thresholds),
                distanceMeters = distance(cleaned),
            )
        }

        private fun distance(cleaned: CleanedTelemetry): Double? {
            val distances = cleaned.distance.filterNotNull()
            return if (distances.isEmpty()) null else (distances.last() - distances.first()).toDouble()
        }

        private fun atThreshold(
            interval: DetectedInterval,
            thresholds: ThresholdsOn,
        ): Boolean {
            val vT = thresholds.thresholdSpeedMps
            val lthr = thresholds.lthr
            val cp = thresholds.criticalPowerWatts
            return when {
                vT != null -> interval.avgGap >= Zones.pace(vT)[Z4].lowerSpeedMps!!
                lthr != null && interval.avgHr != null -> interval.avgHr >= Zones.heartRate(lthr, thresholds.maxHr)[Z4].lowerBpm!!
                cp != null && interval.avgPower != null -> interval.avgPower >= POWER_FRACTION_OF_CP * cp
                else -> false
            }
        }

        private fun raceIntensity(
            moving: MovingSeries,
            thresholds: ThresholdsOn,
        ): Double? {
            if (moving.size == 0) return null
            val vT = thresholds.thresholdSpeedMps
            if (vT != null && vT > 0) return moving.gap.average() / vT
            val lthr = thresholds.lthr
            if (lthr != null && lthr > 0 && moving.heartRateCovers(0, moving.size)) {
                return moving.heartRate.filterNotNull().average() / lthr
            }
            return null
        }
    }
}

/** Seconds of a Run's Moving Time in Seiler's zone 1 and zone 2, of [placed] seconds in a zone at all. */
private class ZoneSeconds(
    val zone1: Int,
    val zone2: Int,
    val placed: Int,
) {
    companion object {
        /** The seconds in each zone, or null when the Thresholds and streams place no sample in a zone. */
        fun of(
            cleaned: CleanedTelemetry,
            gapSpeed: Array<Float?>,
            thresholds: ThresholdsOn,
        ): ZoneSeconds? {
            val t = cleaned.t
            val hrZones = thresholds.lthr?.let { Zones.heartRate(it, thresholds.maxHr) }
            val paceZones = thresholds.thresholdSpeedMps?.let { Zones.pace(it) }
            // Steps that are not recording pauses, and of those the ones moving.
            val moving =
                (1 until t.size).filter {
                    t[it] - t[it - 1] in 1..RunMetrics.RECORDING_PAUSE_SECONDS && (cleaned.speed[it] ?: 0f) >= RunMetrics.MOVING_SPEED_MPS
                }
            val heartRateSteps = moving.count { cleaned.heartRate[it] != null }
            val useHeartRate = hrZones != null && heartRateSteps > 0 && heartRateSteps >= RunMetrics.MIN_COVERAGE * moving.size
            val seilerZoneOf: (Int) -> Int? =
                when {
                    useHeartRate -> { i -> cleaned.heartRate[i]?.let { bpm -> hrZones!!.last { it.lowerBpm == null || bpm >= it.lowerBpm }.seilerZone } }
                    paceZones != null -> { i -> gapSpeed[i]?.let { v -> paceZones.last { it.lowerSpeedMps == null || v >= it.lowerSpeedMps }.seilerZone } }
                    else -> return null
                }
            var zone1 = 0
            var zone2 = 0
            var placed = 0
            for (i in moving) {
                val seiler = seilerZoneOf(i) ?: continue
                val dt = t[i] - t[i - 1]
                placed += dt
                if (seiler == 1) zone1 += dt else if (seiler == 2) zone2 += dt
            }
            return if (placed == 0) null else ZoneSeconds(zone1, zone2, placed)
        }
    }
}

/**
 * The rule-based **Workout Type** of a Run (#48), in this order, the first rule that holds winning:
 *
 * 1. **Race**: its distance is within [RACE_DISTANCE_TOLERANCE] of a Race distance (5K, 10K, half marathon or marathon)
 *    and its [WorkoutEvidence.raceIntensity] is at least that distance's intensity in [RACE_DISTANCES]. (Closeness to a
 *    Personal Record or Race Prognosis is #84.)
 * 2. **Intervals**: at least [MIN_WORK_INTERVALS] Detected Intervals of work at or above threshold. A fast finish or a
 *    fast first half is one work interval, so a progression run is not Intervals.
 * 3. **Long Run**: Moving Time of at least [LONG_RUN_SECONDS], or [LONG_RUN_MEDIAN_FACTOR] times the median Moving Time of
 *    the athlete's Runs in the 28 days before it (when there are at least [MIN_MEDIAN_RUNS]), at mostly easy intensity:
 *    at least [MOSTLY_EASY] of its zoned time in Seiler's zone 1 (a Run with no zones counts as easy).
 * 4. **Tempo**: at least [TEMPO_SECONDS] in Seiler's zone 2.
 * 5. **Easy**: anything else.
 *
 * No Spring and no I/O.
 */
object WorkoutTypeClassifier {
    /** Days before a Run whose Runs make up the median duration the Long Run rule compares with. */
    const val MEDIAN_WINDOW_DAYS = 28L

    const val MIN_MEDIAN_RUNS = 3
    const val MIN_WORK_INTERVALS = 3
    const val TEMPO_SECONDS = 15 * 60
    const val LONG_RUN_SECONDS = 90 * 60
    const val LONG_RUN_MEDIAN_FACTOR = 1.5
    const val MOSTLY_EASY = 0.7
    const val RACE_DISTANCE_TOLERANCE = 0.05

    /** Race distances in metres with the least average intensity (over threshold) that makes a Run over them a Race. */
    val RACE_DISTANCES: List<Pair<Double, Double>> =
        listOf(5_000.0 to 1.00, 10_000.0 to 0.97, 21_097.5 to 0.92, 42_195.0 to 0.86)

    /**
     * The type of a Run with [evidence] and [movingSeconds] of Moving Time, given the [medianMovingSeconds] of the Runs
     * the athlete did in the 28 days before it ([medianRuns] of them).
     */
    fun classify(
        evidence: WorkoutEvidence,
        movingSeconds: Int,
        medianMovingSeconds: Double? = null,
        medianRuns: Int = 0,
    ): WorkoutType =
        when {
            isRace(evidence) -> WorkoutType.RACE
            evidence.thresholdWorkIntervals >= MIN_WORK_INTERVALS -> WorkoutType.INTERVALS
            isLong(evidence, movingSeconds, medianMovingSeconds, medianRuns) -> WorkoutType.LONG_RUN
            evidence.tempoSeconds >= TEMPO_SECONDS -> WorkoutType.TEMPO
            else -> WorkoutType.EASY
        }

    private fun isRace(evidence: WorkoutEvidence): Boolean {
        val distance = evidence.distanceMeters ?: return false
        val intensity = evidence.raceIntensity ?: return false
        return RACE_DISTANCES.any { (race, least) -> abs(distance - race) <= RACE_DISTANCE_TOLERANCE * race && intensity >= least }
    }

    private fun isLong(
        evidence: WorkoutEvidence,
        movingSeconds: Int,
        median: Double?,
        medianRuns: Int,
    ): Boolean {
        val long =
            movingSeconds >= LONG_RUN_SECONDS ||
                (median != null && medianRuns >= MIN_MEDIAN_RUNS && movingSeconds >= LONG_RUN_MEDIAN_FACTOR * median)
        return long && (evidence.easyShare ?: 1.0) >= MOSTLY_EASY
    }
}
