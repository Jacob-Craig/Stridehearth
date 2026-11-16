package com.jacobcraig.stridehearth.system.fit

/**
 * In-memory representation of telemetry time-series streams extracted from Garmin FIT `record` messages.
 *
 * Designed to decouple raw Garmin SDK message objects from downstream database persistence:
 * - [t]: Seconds elapsed since session start, serving as the common time index for all metric arrays.
 * - [lons], [lats]: Longitudinal and latitudinal coordinates in decimal degrees (SRID 4326) for valid GPS fixes.
 * - [routeIndex]: Parallel to [t]; contains the 0-based index into ([lons], [lats]) corresponding to timestamp [t],
 *   or `null` when no GPS fix was recorded at that instant (e.g. indoor activity or tunnel dropout).
 * - Metric arrays: Parallel to [t] with identical length, containing `null` where a sensor dropped out or was absent.
 */
data class FitTelemetry(
    val t: IntArray,
    val lons: DoubleArray,
    val lats: DoubleArray,
    val routeIndex: Array<Int?>,
    // Primary Metrics
    val heartRate: Array<Short?> = emptyArray(),
    val power: Array<Short?> = emptyArray(),
    val cadence: Array<Short?> = emptyArray(),
    val altitude: Array<Float?> = emptyArray(),
    val speed: Array<Float?> = emptyArray(),
    val distance: Array<Float?> = emptyArray(),
    // Running Dynamics
    val verticalOscillation: Array<Float?> = emptyArray(),
    val stanceTime: Array<Float?> = emptyArray(),
    val stanceTimePercent: Array<Float?> = emptyArray(),
    val stanceTimeBalance: Array<Float?> = emptyArray(),
    val stepLength: Array<Float?> = emptyArray(),
    val verticalRatio: Array<Float?> = emptyArray(),
    // Enhanced / Physiological
    val enhancedSpeed: Array<Float?> = emptyArray(),
    val enhancedAltitude: Array<Float?> = emptyArray(),
    val enhancedRespirationRate: Array<Float?> = emptyArray(),
    val respirationRate: Array<Short?> = emptyArray(),
    val temperature: Array<Short?> = emptyArray(),
    val coreTemperature: Array<Float?> = emptyArray(),
    val fractionalCadence: Array<Float?> = emptyArray(),
    val calories: Array<Int?> = emptyArray(),
    val currentStress: Array<Float?> = emptyArray(),
    val batterySoc: Array<Float?> = emptyArray(),
    val gpsAccuracy: Array<Short?> = emptyArray(),
    val verticalSpeed: Array<Float?> = emptyArray(),
    // Cycling Dynamics
    val grade: Array<Float?> = emptyArray(),
    val resistance: Array<Short?> = emptyArray(),
    val timeFromCourse: Array<Float?> = emptyArray(),
    val cycleLength: Array<Float?> = emptyArray(),
    val cycles: Array<Short?> = emptyArray(),
    val totalCycles: Array<Long?> = emptyArray(),
    val accumulatedPower: Array<Long?> = emptyArray(),
    val leftRightBalance: Array<Short?> = emptyArray(),
    val leftTorqueEffectiveness: Array<Float?> = emptyArray(),
    val rightTorqueEffectiveness: Array<Float?> = emptyArray(),
    val leftPedalSmoothness: Array<Float?> = emptyArray(),
    val rightPedalSmoothness: Array<Float?> = emptyArray(),
    val combinedPedalSmoothness: Array<Float?> = emptyArray(),
    val time128: Array<Float?> = emptyArray(),
    val zone: Array<Short?> = emptyArray(),
    val ballSpeed: Array<Float?> = emptyArray(),
    val cadence256: Array<Float?> = emptyArray(),
    val deviceIndex: Array<Short?> = emptyArray(),
    val leftPco: Array<Short?> = emptyArray(),
    val rightPco: Array<Short?> = emptyArray(),
    val motorPower: Array<Short?> = emptyArray(),
    val cycleLength16: Array<Float?> = emptyArray(),
    // Swimming & Diving
    val strokeType: Array<Short?> = emptyArray(),
    val absolutePressure: Array<Long?> = emptyArray(),
    val depth: Array<Float?> = emptyArray(),
    val nextStopDepth: Array<Float?> = emptyArray(),
    val nextStopTime: Array<Int?> = emptyArray(),
    val timeToSurface: Array<Int?> = emptyArray(),
    val ndlTime: Array<Int?> = emptyArray(),
    val cnsLoad: Array<Short?> = emptyArray(),
    val n2Load: Array<Short?> = emptyArray(),
    val airTimeRemaining: Array<Int?> = emptyArray(),
    val pressureSac: Array<Float?> = emptyArray(),
    val volumeSac: Array<Float?> = emptyArray(),
    val rmv: Array<Float?> = emptyArray(),
    val ascentRate: Array<Float?> = emptyArray(),
    val po2: Array<Float?> = emptyArray(),
    // eBike & Motion
    val grit: Array<Float?> = emptyArray(),
    val flow: Array<Float?> = emptyArray(),
    val ebikeTravelRange: Array<Int?> = emptyArray(),
    val ebikeBatteryLevel: Array<Short?> = emptyArray(),
    val ebikeAssistMode: Array<Short?> = emptyArray(),
    val ebikeAssistLevelPercent: Array<Short?> = emptyArray(),
    // Muscle Oxygen
    val totalHemoglobinConc: Array<Float?> = emptyArray(),
    val totalHemoglobinConcMin: Array<Float?> = emptyArray(),
    val totalHemoglobinConcMax: Array<Float?> = emptyArray(),
    val saturatedHemoglobinPercent: Array<Float?> = emptyArray(),
    val saturatedHemoglobinPercentMin: Array<Float?> = emptyArray(),
    val saturatedHemoglobinPercentMax: Array<Float?> = emptyArray(),
) {
    /** Whether these Activity Details have distance; see [hasDistance]. Details without it "lack distance". */
    val hasDistance: Boolean get() = hasDistance(distance)

    companion object {
        /**
         * The single definition of "has distance": at least one sample has a distance.
         * Takes the bare series so the parser's distance synthesis can apply it before the details exist.
         */
        fun hasDistance(distance: Array<Float?>): Boolean = distance.any { it != null }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FitTelemetry

        if (!t.contentEquals(other.t)) return false
        if (!lons.contentEquals(other.lons)) return false
        if (!lats.contentEquals(other.lats)) return false
        if (!routeIndex.contentEquals(other.routeIndex)) return false
        if (!heartRate.contentEquals(other.heartRate)) return false
        if (!power.contentEquals(other.power)) return false
        if (!cadence.contentEquals(other.cadence)) return false
        if (!altitude.contentEquals(other.altitude)) return false
        if (!speed.contentEquals(other.speed)) return false
        if (!distance.contentEquals(other.distance)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = t.contentHashCode()
        result = 31 * result + lons.contentHashCode()
        result = 31 * result + lats.contentHashCode()
        result = 31 * result + routeIndex.contentHashCode()
        return result
    }
}
