package com.jacobcraig.stridehearth.system.fit

import com.garmin.fit.DateTime
import com.garmin.fit.RecordMesg
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure mapper converting decoded Garmin FIT [RecordMesg] telemetry streams into format-independent [FitTelemetry].
 *
 * Key guarantees:
 * - Pure functions with no mutable state and zero dependencies on PostGIS/JTS or database frameworks.
 * - Semicircle coordinate transformation to WGS84 decimal degrees: `degrees = semicircles * (180.0 / 2^31)`.
 * - Dropout handling: invalid or missing sensor entries are stored as `null` while preserving the exact timeline length.
 */
internal object FitTelemetryMapper {
    /** Garmin FIT semicircles to degrees conversion factor: 180.0 / 2^31. */
    private const val SEMICIRCLES_TO_DEGREES = 180.0 / 2147483648.0

    /** Sentinel value used by Garmin FIT SDK to denote invalid or unavailable GPS semicircles. */
    private const val INVALID_SEMICIRCLE = 0x7FFFFFFF

    /** Mean Earth radius in meters for Haversine great-circle distance calculation. */
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Converts raw FIT [records] into [FitTelemetry] relative to [sessionStart].
     *
     * @param sessionStart the UTC start timestamp of the enclosing session.
     * @param records chronological list of [RecordMesg] samples within the session window.
     * @return [FitTelemetry] instance containing parallel arrays, or `null` if [records] is empty.
     */
    fun map(
        sessionStart: DateTime,
        records: List<RecordMesg>,
    ): FitTelemetry? {
        if (records.isEmpty()) return null

        val startTimestamp = sessionStart.timestamp
        val size = records.size

        val tList = IntArray(size)
        val lonsList = ArrayList<Double>(size)
        val latsList = ArrayList<Double>(size)
        val routeIndex = arrayOfNulls<Int>(size)

        // Metrics arrays
        val heartRate = arrayOfNulls<Short>(size)
        val power = arrayOfNulls<Short>(size)
        val cadence = arrayOfNulls<Short>(size)
        val altitude = arrayOfNulls<Float>(size)
        val speed = arrayOfNulls<Float>(size)
        val distance = arrayOfNulls<Float>(size)

        val verticalOscillation = arrayOfNulls<Float>(size)
        val stanceTime = arrayOfNulls<Float>(size)
        val stanceTimePercent = arrayOfNulls<Float>(size)
        val stanceTimeBalance = arrayOfNulls<Float>(size)
        val stepLength = arrayOfNulls<Float>(size)
        val verticalRatio = arrayOfNulls<Float>(size)

        val enhancedSpeed = arrayOfNulls<Float>(size)
        val enhancedAltitude = arrayOfNulls<Float>(size)
        val enhancedRespirationRate = arrayOfNulls<Float>(size)
        val respirationRate = arrayOfNulls<Short>(size)
        val temperature = arrayOfNulls<Short>(size)
        val coreTemperature = arrayOfNulls<Float>(size)
        val fractionalCadence = arrayOfNulls<Float>(size)
        val calories = arrayOfNulls<Int>(size)
        val currentStress = arrayOfNulls<Float>(size)
        val batterySoc = arrayOfNulls<Float>(size)
        val gpsAccuracy = arrayOfNulls<Short>(size)
        val verticalSpeed = arrayOfNulls<Float>(size)

        val grade = arrayOfNulls<Float>(size)
        val resistance = arrayOfNulls<Short>(size)
        val timeFromCourse = arrayOfNulls<Float>(size)
        val cycleLength = arrayOfNulls<Float>(size)
        val cycles = arrayOfNulls<Short>(size)
        val totalCycles = arrayOfNulls<Long>(size)
        val accumulatedPower = arrayOfNulls<Long>(size)
        val leftRightBalance = arrayOfNulls<Short>(size)
        val leftTorqueEffectiveness = arrayOfNulls<Float>(size)
        val rightTorqueEffectiveness = arrayOfNulls<Float>(size)
        val leftPedalSmoothness = arrayOfNulls<Float>(size)
        val rightPedalSmoothness = arrayOfNulls<Float>(size)
        val combinedPedalSmoothness = arrayOfNulls<Float>(size)
        val time128 = arrayOfNulls<Float>(size)
        val zone = arrayOfNulls<Short>(size)
        val ballSpeed = arrayOfNulls<Float>(size)
        val cadence256 = arrayOfNulls<Float>(size)
        val deviceIndex = arrayOfNulls<Short>(size)
        val leftPco = arrayOfNulls<Short>(size)
        val rightPco = arrayOfNulls<Short>(size)
        val motorPower = arrayOfNulls<Short>(size)
        val cycleLength16 = arrayOfNulls<Float>(size)

        val strokeType = arrayOfNulls<Short>(size)
        val absolutePressure = arrayOfNulls<Long>(size)
        val depth = arrayOfNulls<Float>(size)
        val nextStopDepth = arrayOfNulls<Float>(size)
        val nextStopTime = arrayOfNulls<Int>(size)
        val timeToSurface = arrayOfNulls<Int>(size)
        val ndlTime = arrayOfNulls<Int>(size)
        val cnsLoad = arrayOfNulls<Short>(size)
        val n2Load = arrayOfNulls<Short>(size)
        val airTimeRemaining = arrayOfNulls<Int>(size)
        val pressureSac = arrayOfNulls<Float>(size)
        val volumeSac = arrayOfNulls<Float>(size)
        val rmv = arrayOfNulls<Float>(size)
        val ascentRate = arrayOfNulls<Float>(size)
        val po2 = arrayOfNulls<Float>(size)

        val grit = arrayOfNulls<Float>(size)
        val flow = arrayOfNulls<Float>(size)
        val ebikeTravelRange = arrayOfNulls<Int>(size)
        val ebikeBatteryLevel = arrayOfNulls<Short>(size)
        val ebikeAssistMode = arrayOfNulls<Short>(size)
        val ebikeAssistLevelPercent = arrayOfNulls<Short>(size)

        val totalHemoglobinConc = arrayOfNulls<Float>(size)
        val totalHemoglobinConcMin = arrayOfNulls<Float>(size)
        val totalHemoglobinConcMax = arrayOfNulls<Float>(size)
        val saturatedHemoglobinPercent = arrayOfNulls<Float>(size)
        val saturatedHemoglobinPercentMin = arrayOfNulls<Float>(size)
        val saturatedHemoglobinPercentMax = arrayOfNulls<Float>(size)

        var routeVertexIndex = 0

        for (i in 0 until size) {
            val record = records[i]

            // t: seconds since session start
            val recTime = record.timestamp?.timestamp ?: startTimestamp
            tList[i] = (recTime - startTimestamp).toInt()

            // Route coordinates
            val rawLat = record.positionLat
            val rawLon = record.positionLong
            if (rawLat != null && rawLon != null && rawLat != INVALID_SEMICIRCLE && rawLon != INVALID_SEMICIRCLE) {
                lonsList.add(rawLon * SEMICIRCLES_TO_DEGREES)
                latsList.add(rawLat * SEMICIRCLES_TO_DEGREES)
                routeIndex[i] = routeVertexIndex++
            } else {
                routeIndex[i] = null
            }

            // Metrics
            heartRate[i] = record.heartRate
            power[i] = record.power?.toShort()
            cadence[i] = record.cadence
            altitude[i] = record.altitude
            speed[i] = record.speed
            distance[i] = record.distance

            verticalOscillation[i] = record.verticalOscillation
            stanceTime[i] = record.stanceTime
            stanceTimePercent[i] = record.stanceTimePercent
            stanceTimeBalance[i] = record.stanceTimeBalance
            stepLength[i] = record.stepLength
            verticalRatio[i] = record.verticalRatio

            enhancedSpeed[i] = record.enhancedSpeed
            enhancedAltitude[i] = record.enhancedAltitude
            enhancedRespirationRate[i] = record.enhancedRespirationRate
            respirationRate[i] = record.respirationRate
            temperature[i] = record.temperature?.toShort()
            coreTemperature[i] = record.coreTemperature
            fractionalCadence[i] = record.fractionalCadence
            calories[i] = record.calories
            currentStress[i] = record.currentStress
            batterySoc[i] = record.batterySoc
            gpsAccuracy[i] = record.gpsAccuracy
            verticalSpeed[i] = record.verticalSpeed

            grade[i] = record.grade
            resistance[i] = record.resistance
            timeFromCourse[i] = record.timeFromCourse
            cycleLength[i] = record.cycleLength
            cycles[i] = record.cycles
            totalCycles[i] = record.totalCycles
            accumulatedPower[i] = record.accumulatedPower
            leftRightBalance[i] = record.leftRightBalance
            leftTorqueEffectiveness[i] = record.leftTorqueEffectiveness
            rightTorqueEffectiveness[i] = record.rightTorqueEffectiveness
            leftPedalSmoothness[i] = record.leftPedalSmoothness
            rightPedalSmoothness[i] = record.rightPedalSmoothness
            combinedPedalSmoothness[i] = record.combinedPedalSmoothness
            time128[i] = record.time128
            zone[i] = record.zone
            ballSpeed[i] = record.ballSpeed
            cadence256[i] = record.cadence256
            deviceIndex[i] = record.deviceIndex
            leftPco[i] = record.leftPco?.toShort()
            rightPco[i] = record.rightPco?.toShort()
            motorPower[i] = record.motorPower?.toShort()
            cycleLength16[i] = record.cycleLength16

            strokeType[i] = record.strokeType?.value
            absolutePressure[i] = record.absolutePressure
            depth[i] = record.depth
            nextStopDepth[i] = record.nextStopDepth
            nextStopTime[i] = record.nextStopTime?.toInt()
            timeToSurface[i] = record.timeToSurface?.toInt()
            ndlTime[i] = record.ndlTime?.toInt()
            cnsLoad[i] = record.cnsLoad
            n2Load[i] = record.n2Load?.toShort()
            airTimeRemaining[i] = record.airTimeRemaining?.toInt()
            pressureSac[i] = record.pressureSac
            volumeSac[i] = record.volumeSac
            rmv[i] = record.rmv
            ascentRate[i] = record.ascentRate
            po2[i] = record.po2

            grit[i] = record.grit
            flow[i] = record.flow
            ebikeTravelRange[i] = record.ebikeTravelRange
            ebikeBatteryLevel[i] = record.ebikeBatteryLevel
            ebikeAssistMode[i] = record.ebikeAssistMode
            ebikeAssistLevelPercent[i] = record.ebikeAssistLevelPercent

            totalHemoglobinConc[i] = record.totalHemoglobinConc
            totalHemoglobinConcMin[i] = record.totalHemoglobinConcMin
            totalHemoglobinConcMax[i] = record.totalHemoglobinConcMax
            saturatedHemoglobinPercent[i] = record.saturatedHemoglobinPercent
            saturatedHemoglobinPercentMin[i] = record.saturatedHemoglobinPercentMin
            saturatedHemoglobinPercentMax[i] = record.saturatedHemoglobinPercentMax
        }

        val lonsArray = lonsList.toDoubleArray()
        val latsArray = latsList.toDoubleArray()
        synthesizeDistanceIfAbsent(
            t = tList,
            lons = lonsArray,
            lats = latsArray,
            routeIndex = routeIndex,
            enhancedSpeed = enhancedSpeed,
            speed = speed,
            distance = distance,
        )

        return FitTelemetry(
            t = tList,
            lons = lonsArray,
            lats = latsArray,
            routeIndex = routeIndex,
            heartRate = heartRate,
            power = power,
            cadence = cadence,
            altitude = altitude,
            speed = speed,
            distance = distance,
            verticalOscillation = verticalOscillation,
            stanceTime = stanceTime,
            stanceTimePercent = stanceTimePercent,
            stanceTimeBalance = stanceTimeBalance,
            stepLength = stepLength,
            verticalRatio = verticalRatio,
            enhancedSpeed = enhancedSpeed,
            enhancedAltitude = enhancedAltitude,
            enhancedRespirationRate = enhancedRespirationRate,
            respirationRate = respirationRate,
            temperature = temperature,
            coreTemperature = coreTemperature,
            fractionalCadence = fractionalCadence,
            calories = calories,
            currentStress = currentStress,
            batterySoc = batterySoc,
            gpsAccuracy = gpsAccuracy,
            verticalSpeed = verticalSpeed,
            grade = grade,
            resistance = resistance,
            timeFromCourse = timeFromCourse,
            cycleLength = cycleLength,
            cycles = cycles,
            totalCycles = totalCycles,
            accumulatedPower = accumulatedPower,
            leftRightBalance = leftRightBalance,
            leftTorqueEffectiveness = leftTorqueEffectiveness,
            rightTorqueEffectiveness = rightTorqueEffectiveness,
            leftPedalSmoothness = leftPedalSmoothness,
            rightPedalSmoothness = rightPedalSmoothness,
            combinedPedalSmoothness = combinedPedalSmoothness,
            time128 = time128,
            zone = zone,
            ballSpeed = ballSpeed,
            cadence256 = cadence256,
            deviceIndex = deviceIndex,
            leftPco = leftPco,
            rightPco = rightPco,
            motorPower = motorPower,
            cycleLength16 = cycleLength16,
            strokeType = strokeType,
            absolutePressure = absolutePressure,
            depth = depth,
            nextStopDepth = nextStopDepth,
            nextStopTime = nextStopTime,
            timeToSurface = timeToSurface,
            ndlTime = ndlTime,
            cnsLoad = cnsLoad,
            n2Load = n2Load,
            airTimeRemaining = airTimeRemaining,
            pressureSac = pressureSac,
            volumeSac = volumeSac,
            rmv = rmv,
            ascentRate = ascentRate,
            po2 = po2,
            grit = grit,
            flow = flow,
            ebikeTravelRange = ebikeTravelRange,
            ebikeBatteryLevel = ebikeBatteryLevel,
            ebikeAssistMode = ebikeAssistMode,
            ebikeAssistLevelPercent = ebikeAssistLevelPercent,
            totalHemoglobinConc = totalHemoglobinConc,
            totalHemoglobinConcMin = totalHemoglobinConcMin,
            totalHemoglobinConcMax = totalHemoglobinConcMax,
            saturatedHemoglobinPercent = saturatedHemoglobinPercent,
            saturatedHemoglobinPercentMin = saturatedHemoglobinPercentMin,
            saturatedHemoglobinPercentMax = saturatedHemoglobinPercentMax,
        )
    }

    /**
     * Populates [distance] in-place when all recorded entries are `null`, synthesizing cumulative meters
     * in $O(N)$ time using Haversine great-circle distance between consecutive GPS fixes and falling back
     * to `(enhancedSpeed ?: speed) * dt` across steps where GPS coordinates are absent.
     *
     * Leaves [distance] untouched if any non-null distance sample is already present or if no positive
     * motion is observed across the timeline.
     */
    fun synthesizeDistanceIfAbsent(
        t: IntArray,
        lons: DoubleArray,
        lats: DoubleArray,
        routeIndex: Array<Int?>,
        enhancedSpeed: Array<Float?>,
        speed: Array<Float?>,
        distance: Array<Float?>,
    ): Boolean {
        if (t.size < 2 || distance.size != t.size || FitTelemetry.hasDistance(distance)) {
            return false
        }

        val candidate = FloatArray(t.size)
        var cumulative = 0.0
        candidate[0] = 0.0f

        for (i in 1 until t.size) {
            val prevVertex = routeIndex.getOrNull(i - 1)
            val currVertex = routeIndex.getOrNull(i)
            val stepMeters =
                if (prevVertex != null && currVertex != null && prevVertex in lats.indices && currVertex in lats.indices) {
                    haversineMeters(lats[prevVertex], lons[prevVertex], lats[currVertex], lons[currVertex])
                } else {
                    val dt = (t[i] - t[i - 1]).coerceAtLeast(0)
                    val sampleSpeed =
                        (enhancedSpeed.getOrNull(i) ?: speed.getOrNull(i))
                            ?.toDouble()
                            ?.coerceAtLeast(0.0) ?: 0.0
                    sampleSpeed * dt
                }
            cumulative += stepMeters
            candidate[i] = cumulative.toFloat()
        }

        if (cumulative <= 0.0) {
            return false
        }

        for (i in candidate.indices) {
            distance[i] = candidate[i]
        }
        return true
    }

    private fun haversineMeters(
        lat1Deg: Double,
        lon1Deg: Double,
        lat2Deg: Double,
        lon2Deg: Double,
    ): Double {
        if (lat1Deg == lat2Deg && lon1Deg == lon2Deg) return 0.0
        val dLat = Math.toRadians(lat2Deg - lat1Deg)
        val dLon = Math.toRadians(lon2Deg - lon1Deg)
        val rLat1 = Math.toRadians(lat1Deg)
        val rLat2 = Math.toRadians(lat2Deg)
        val sinDLat = sin(dLat * 0.5)
        val sinDLon = sin(dLon * 0.5)
        val a = sinDLat * sinDLat + cos(rLat1) * cos(rLat2) * sinDLon * sinDLon
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_METERS * c
    }
}
