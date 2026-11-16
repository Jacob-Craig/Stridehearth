package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED

/*
 * OpenAPI contract for `GET /api/activities/{id}/telemetry`.
 *
 * These classes are never instantiated: the response body is the JSON built by PostgreSQL in
 * `ActivityDetailsRepository.findTelemetryJson` and streamed through verbatim. They exist so springdoc
 * publishes the exact shape of that JSON, giving the TypeScript client matching types and Zod schemas.
 * `ActivityTelemetryApiIntegrationTest` fails if the declared and emitted shapes ever drift apart.
 */

private const val ARRAY = "array"
private const val INTEGER = "integer"
private const val NUMBER = "number"
private const val NULL = "null"

/** The **Activity Details** of one **Activity**, with its **Best Effort** windows for highlighting. */
data class ActivityTelemetryResponseDTO(
    @field:Schema(requiredMode = REQUIRED)
    val activityId: Long,
    @field:Schema(requiredMode = REQUIRED)
    val sport: ActivitySport,
    @field:Schema(
        requiredMode = REQUIRED,
        types = ["object", NULL],
        description = "Every recorded GPS fix in order, or null when fewer than two were recorded",
    )
    val route: GeoJsonLineStringDTO?,
    @field:Schema(requiredMode = REQUIRED, description = "Best Efforts, shortest Benchmark Distance first")
    val bestEfforts: List<ActivityTelemetryBestEffortDTO>,
    @field:Schema(requiredMode = REQUIRED)
    val telemetry: ActivityTelemetryStreamsDTO,
)

/** A GeoJSON `LineString` (RFC 7946) of `[longitude, latitude]` positions. */
data class GeoJsonLineStringDTO(
    @field:Schema(requiredMode = REQUIRED, allowableValues = ["LineString"])
    val type: String,
    @field:Schema(requiredMode = REQUIRED, description = "[longitude, latitude] positions")
    val coordinates: List<List<Double>>,
)

/** A **Best Effort** with its effort window flattened, as emitted by the telemetry query. */
data class ActivityTelemetryBestEffortDTO(
    @field:Schema(requiredMode = REQUIRED)
    val benchmarkDistance: BenchmarkDistance,
    @field:Schema(requiredMode = REQUIRED)
    val elapsedTimeSeconds: Double,
    @field:Schema(requiredMode = REQUIRED)
    val startIndex: Int,
    @field:Schema(requiredMode = REQUIRED)
    val endIndex: Int,
    @field:Schema(requiredMode = REQUIRED)
    val startTimeSeconds: Double,
    @field:Schema(requiredMode = REQUIRED)
    val endTimeSeconds: Double,
    @field:Schema(requiredMode = REQUIRED)
    val startDistanceMeters: Double,
    @field:Schema(requiredMode = REQUIRED)
    val endDistanceMeters: Double,
)

/**
 * Parallel sensor streams of the **Activity Details**: element `i` of every stream belongs to the sample
 * recorded `t[i]` seconds after the start. A stream is null when the device never recorded it, and an
 * element is null for a sensor dropout. `routeIndex[i]` indexes the GPS fix in `route.coordinates`.
 */
data class ActivityTelemetryStreamsDTO(
    @field:Schema(requiredMode = REQUIRED, description = "Elapsed seconds since the start of each sample")
    val t: List<Int>,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val routeIndex: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val heartRate: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val power: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val cadence: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val altitude: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val speed: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val distance: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val verticalOscillation: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val stanceTime: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val stanceTimePercent: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val stanceTimeBalance: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val stepLength: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val verticalRatio: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val enhancedSpeed: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val enhancedAltitude: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val enhancedRespirationRate: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val respirationRate: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val temperature: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val coreTemperature: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val fractionalCadence: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val calories: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val currentStress: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val batterySoc: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val gpsAccuracy: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val verticalSpeed: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val grade: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val resistance: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val timeFromCourse: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val cycleLength: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val cycles: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val totalCycles: List<Long?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val accumulatedPower: List<Long?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val leftRightBalance: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val leftTorqueEffectiveness: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val rightTorqueEffectiveness: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val leftPedalSmoothness: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val rightPedalSmoothness: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val combinedPedalSmoothness: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val time128: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val zone: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val ballSpeed: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val cadence256: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val deviceIndex: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val leftPco: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val rightPco: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val motorPower: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val cycleLength16: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val strokeType: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val absolutePressure: List<Long?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val depth: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val nextStopDepth: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val nextStopTime: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val timeToSurface: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val ndlTime: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val cnsLoad: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val n2Load: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val airTimeRemaining: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val pressureSac: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val volumeSac: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val rmv: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val ascentRate: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val po2: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val grit: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val flow: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val ebikeTravelRange: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val ebikeBatteryLevel: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val ebikeAssistMode: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [INTEGER, NULL]))
    val ebikeAssistLevelPercent: List<Int?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val totalHemoglobinConc: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val totalHemoglobinConcMin: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val totalHemoglobinConcMax: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val saturatedHemoglobinPercent: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val saturatedHemoglobinPercentMin: List<Double?>?,
    @field:Schema(types = [ARRAY, NULL], requiredMode = REQUIRED)
    @field:ArraySchema(schema = Schema(types = [NUMBER, NULL]))
    val saturatedHemoglobinPercentMax: List<Double?>?,
)
