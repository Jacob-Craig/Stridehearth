package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.activities.history.ActivityHistoryEntryDTO
import com.jacobcraig.stridehearth.activities.history.HistoryEntry
import com.jacobcraig.stridehearth.activities.history.toDto
import com.jacobcraig.stridehearth.system.ActivitySport
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * One **Activity** with everything its detail view shows: the summary metrics (canonical primitives
 * only, as in [ActivitySummaryDTO]), its owner, device **Laps**, **Activity History Entries** and
 * **Best Efforts**. The high-resolution **Activity Details** are served separately by
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
