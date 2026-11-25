package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.activities.history.ActivityHistoryEntryDTO
import com.jacobcraig.stridehearth.activities.history.HistoryEntry
import com.jacobcraig.stridehearth.activities.history.toDto
import com.jacobcraig.stridehearth.analytics.ActivityConditions
import com.jacobcraig.stridehearth.analytics.DecouplingUnavailableReason
import com.jacobcraig.stridehearth.analytics.RunMetrics
import com.jacobcraig.stridehearth.analytics.TrainingLoadMethod
import com.jacobcraig.stridehearth.analytics.TrainingLoadResult
import com.jacobcraig.stridehearth.analytics.TrainingLoadUnavailableReason
import com.jacobcraig.stridehearth.athlete.AcclimationRow
import com.jacobcraig.stridehearth.system.ActivitySport
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * One **Activity** with everything its detail view shows: the summary metrics (canonical primitives
 * only, as in [ActivitySummaryDTO]), its owner, device **Laps**, **Activity History Entries** and
 * **Best Efforts**, for a Run its derived [RunMetricsDTO], its **Training Load** or why it has none, and the
 * [ActivityConditionsDTO] it was done in. The high-resolution **Activity Details** are served separately by
 * `GET /api/activities/{id}/telemetry` ([ActivityTelemetryResponseDTO]).
 */
data class ActivityDetailDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val title: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val sport: ActivitySport,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val startedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Distance in kilometers")
    val distance: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val timeTakenSeconds: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"], description = "Average speed in km/h")
    val averagePaceKmh: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val averageHeartRate: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val calories: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val source: ActivitySource,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "True when the Protected Metrics are immutable and only title and sport may be edited",
    )
    val hasProtectedMetrics: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val ownerUsername: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Device-recorded Laps in lap order")
    val laps: List<ActivityLapDTO>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Activity History Entries, oldest first")
    val history: List<ActivityHistoryEntryDTO>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Best Efforts, shortest Benchmark Distance first")
    val bestEfforts: List<ActivityBestEffortDTO>,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["object", "null"],
        description = "Derived Run metrics; null for any sport but Run and for a Run whose derived data is not computed yet",
    )
    val runMetrics: RunMetricsDTO?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["object", "null"],
        description = "Training Load and the method that computed it; null when it is unavailable or not computed yet",
    )
    val trainingLoad: TrainingLoadDTO?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["string", "null"],
        description = "Why the Activity has no Training Load; null when it has one or it is not computed yet",
    )
    val trainingLoadUnavailableReason: TrainingLoadUnavailableReason?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "The lowest Stamina the Run reaches, in percent; null when it has no Stamina stream",
    )
    val minStaminaPct: Double?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["object", "null"],
        description = "The temperature, altitude and acclimation the Activity was done in; null until its derived data is computed",
    )
    val conditions: ActivityConditionsDTO?,
)

/**
 * The heat and altitude an Activity was done in, from its own Activity Details (ADR-0017), and the athlete's
 * acclimation on its athlete-local day. Every value is unrounded and null when it cannot be computed.
 */
data class ActivityConditionsDTO(
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "The median temperature the device recorded, in °C",
    )
    val deviceTemperatureC: Double?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Ambient Temperature in °C: the device temperature less the Wrist-Heat Correction",
    )
    val ambientTemperatureC: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The Wrist-Heat Correction subtracted, in °C")
    val wristHeatCorrectionC: Double,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Mean Altitude in metres: the mean of the cleaned altitude",
    )
    val meanAltitudeM: Double?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Heat Acclimation (0-100 %) at the end of the Activity's day; null before the athlete's first exposed day",
    )
    val heatAcclimationPct: Double?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Altitude Acclimation (0-100 %) at the end of the Activity's day; null before the athlete's first exposed day",
    )
    val altitudeAcclimationPct: Double?,
)

fun ActivityConditions.toDto(acclimation: AcclimationRow?): ActivityConditionsDTO =
    ActivityConditionsDTO(
        deviceTemperatureC = deviceTemperatureC,
        ambientTemperatureC = ambientTemperatureC,
        wristHeatCorrectionC = ActivityConditions.WRIST_HEAT_CORRECTION_C,
        meanAltitudeM = meanAltitudeM,
        heatAcclimationPct = acclimation?.heatPct,
        altitudeAcclimationPct = acclimation?.altitudePct,
    )

/** An Activity's **Training Load**, unrounded, scaled so one hour at threshold is 100, and its **Training Load Method**. */
data class TrainingLoadDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val value: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val method: TrainingLoadMethod,
)

/**
 * The derived summary metrics of a Run, unrounded SI values computed from its **Grade-Adjusted Pace** (see
 * [RunMetrics]). [aerobicDecouplingPercent] is null exactly when [decouplingUnavailableReason] says why.
 */
data class RunMetricsDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Moving Time in seconds")
    val movingSeconds: Int,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Normalized Graded Pace in meters per second",
    )
    val ngpMetersPerSecond: Double?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Efficiency Factor: Normalized Graded Pace in meters per minute per beat per minute of heart rate",
    )
    val efficiencyFactor: Double?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Aerobic Decoupling in percent; positive when heart rate rose relative to pace",
    )
    val aerobicDecouplingPercent: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["string", "null"])
    val decouplingUnavailableReason: DecouplingUnavailableReason?,
)

fun RunMetrics.toDto(): RunMetricsDTO =
    RunMetricsDTO(
        movingSeconds = movingSeconds,
        ngpMetersPerSecond = ngpMps,
        efficiencyFactor = efficiencyFactor,
        aerobicDecouplingPercent = aerobicDecouplingPct,
        decouplingUnavailableReason = decouplingUnavailableReason,
    )

/** One device-recorded **Lap**. Distance is in kilometers and average speed in km/h. */
data class ActivityLapDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val lapNumber: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val startedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Distance in kilometers")
    val distance: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val timeTakenSeconds: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"], description = "Average speed in km/h")
    val averagePaceKmh: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["integer", "null"])
    val averageHeartRate: Int?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val elevationGainMeters: Double?,
)

/** One **Best Effort** on the Activity, with the [window] of **Activity Details** it spans. */
data class ActivityBestEffortDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val benchmarkDistance: BenchmarkDistance,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val benchmarkName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val distanceMeters: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val elapsedTimeSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val window: EffortWindowDTO,
)

/**
 * The bounds of an effort within the **Activity Details**: sample indices into the telemetry arrays,
 * elapsed seconds from the start, and cumulative distance in meters.
 */
data class EffortWindowDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val startIndex: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val endIndex: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val startTimeSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val endTimeSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val startDistanceMeters: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val endDistanceMeters: Double,
)

/**
 * Builds on [toSummaryDto] so the summary metrics are mapped in one place. The fields are copied
 * across rather than nested because the detail payload keeps the same flat wire shape as
 * [ActivitySummaryDTO]; Kotlin data classes cannot inherit fields, and `@JsonUnwrapped` is not
 * reliably reflected in the generated OpenAPI schema.
 */
fun Activity.toDetailDto(
    hasActivityDetails: Boolean,
    laps: List<ActivityLap>,
    history: List<HistoryEntry>,
    bestEfforts: List<ActivityBestEffort>,
    runMetrics: RunMetrics? = null,
    trainingLoad: TrainingLoadResult? = null,
    minStaminaPct: Float? = null,
    conditions: ActivityConditionsDTO? = null,
): ActivityDetailDTO =
    toSummaryDto(hasActivityDetails).let { summary ->
        ActivityDetailDTO(
            id = summary.id,
            title = summary.title,
            sport = summary.sport,
            startedAt = summary.startedAt,
            distance = summary.distance,
            timeTakenSeconds = summary.timeTakenSeconds,
            averagePaceKmh = summary.averagePaceKmh,
            averageHeartRate = summary.averageHeartRate,
            calories = summary.calories,
            source = summary.source,
            hasProtectedMetrics = summary.hasProtectedMetrics,
            ownerUsername = user.username,
            laps = laps.map { it.toDto() },
            history = history.map { it.toDto() },
            bestEfforts = bestEfforts.map { it.toDto() },
            runMetrics = runMetrics?.takeIf { sport == ActivitySport.RUN }?.toDto(),
            trainingLoad = (trainingLoad as? TrainingLoadResult.Scored)?.let { TrainingLoadDTO(it.value, it.method) },
            trainingLoadUnavailableReason = (trainingLoad as? TrainingLoadResult.Unavailable)?.reason,
            minStaminaPct = minStaminaPct?.let { Math.round(it * 10.0) / 10.0 },
            conditions = conditions,
        )
    }

fun ActivityLap.toDto(): ActivityLapDTO =
    ActivityLapDTO(
        lapNumber = lapNumber,
        startedAt = startedAt,
        distance = distance,
        timeTakenSeconds = timeTakenSeconds,
        averagePaceKmh = averagePaceKmh,
        averageHeartRate = averageHeartRate,
        elevationGainMeters = elevationGainMeters,
    )

fun ActivityBestEffort.toDto(): ActivityBestEffortDTO =
    ActivityBestEffortDTO(
        id = id!!,
        benchmarkDistance = benchmarkDistance,
        benchmarkName = benchmarkDistance.displayName,
        distanceMeters = benchmarkDistance.distanceMeters,
        elapsedTimeSeconds = elapsedTimeSeconds,
        window = window.toDto(),
    )

fun EffortWindow.toDto(): EffortWindowDTO =
    EffortWindowDTO(
        startIndex = startIndex,
        endIndex = endIndex,
        startTimeSeconds = startTimeSeconds,
        endTimeSeconds = endTimeSeconds,
        startDistanceMeters = startDistanceMeters,
        endDistanceMeters = endDistanceMeters,
    )
