package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.WorkoutType
import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.system.ActivitySport
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import kotlin.reflect.KProperty1

/**
 * Summary of one **Activity** using canonical primitives only (distance in kilometers, duration in
 * seconds, speed in km/h); all display formatting is performed by the client.
 */
data class ActivitySummaryDTO(
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
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["string", "null"],
        description = "Workout Type of a Run; null for any other sport and for a Run not yet classified",
    )
    val workoutType: WorkoutType?,
)

fun Activity.toSummaryDto(
    hasActivityDetails: Boolean,
    workoutType: WorkoutType? = WorkoutType.fromStored(storedWorkoutType),
): ActivitySummaryDTO =
    ActivitySummaryDTO(
        id = id!!,
        title = title,
        sport = sport,
        startedAt = startedAt,
        distance = distance,
        timeTakenSeconds = timeTakenSeconds,
        averagePaceKmh = averagePaceKmh,
        averageHeartRate = averageHeartRate,
        calories = calories,
        source = source,
        hasProtectedMetrics = hasProtectedMetrics(hasActivityDetails),
        workoutType = workoutType.takeIf { sport == ActivitySport.RUN },
    )

/** Validation messages shared by the create and edit request bodies, so both report identical errors. */
object ActivityValidationMessages {
    const val TITLE_EMPTY = "Title cannot be empty"
    const val TITLE_LENGTH = "Title must be between 1 and 255 characters"
    const val TITLE_MAX_LENGTH = 255
    const val SPORT_REQUIRED = "Sport is required"
    const val START_TIME_REQUIRED = "Start time is required"
    const val DISTANCE_REQUIRED = "Distance is required"
    const val DISTANCE_POSITIVE = "Distance must be greater than zero"
    const val DURATION_REQUIRED = "Duration is required"
    const val DURATION_POSITIVE = "Duration must be greater than zero"
    const val AVERAGE_SPEED_POSITIVE = "Average speed must be greater than zero"
    const val AVERAGE_HEART_RATE_POSITIVE = "Average heart rate must be greater than zero"
    const val CALORIES_NON_NEGATIVE = "Calories cannot be negative"
}

/** Request body for logging a `MANUAL` **Activity**. Distance is in kilometers and average speed in km/h. */
data class ActivityCreateRequestDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = ActivityValidationMessages.TITLE_EMPTY)
    @field:Size(min = 1, max = ActivityValidationMessages.TITLE_MAX_LENGTH, message = ActivityValidationMessages.TITLE_LENGTH)
    val title: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = ActivityValidationMessages.SPORT_REQUIRED)
    val sport: ActivitySport?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = ActivityValidationMessages.START_TIME_REQUIRED)
    override val startedAt: Instant?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Distance in kilometers")
    @field:NotNull(message = ActivityValidationMessages.DISTANCE_REQUIRED)
    @field:DecimalMin(value = "0", inclusive = false, message = ActivityValidationMessages.DISTANCE_POSITIVE)
    override val distance: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = ActivityValidationMessages.DURATION_REQUIRED)
    @field:DecimalMin(value = "0", inclusive = false, message = ActivityValidationMessages.DURATION_POSITIVE)
    override val timeTakenSeconds: Int?,
    @field:Schema(types = ["number", "null"], description = "Average speed in km/h")
    @field:DecimalMin(value = "0", inclusive = false, message = ActivityValidationMessages.AVERAGE_SPEED_POSITIVE)
    override val averagePaceKmh: Double? = null,
    @field:Schema(types = ["integer", "null"])
    @field:DecimalMin(value = "0", inclusive = false, message = ActivityValidationMessages.AVERAGE_HEART_RATE_POSITIVE)
    override val averageHeartRate: Int? = null,
    @field:Schema(types = ["integer", "null"], minimum = "0")
    @field:PositiveOrZero(message = ActivityValidationMessages.CALORIES_NON_NEGATIVE)
    override val calories: Int? = null,
) : ProtectedMetricValues

/**
 * Request body for editing an **Activity**. [title] and [sport] are always editable. The remaining
 * **Protected Metrics** must all be supplied for an editable activity, and must all be omitted when
 * the activity's `hasProtectedMetrics` is true.
 */
data class ActivityUpdateRequestDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = ActivityValidationMessages.TITLE_EMPTY)
    @field:Size(min = 1, max = ActivityValidationMessages.TITLE_MAX_LENGTH, message = ActivityValidationMessages.TITLE_LENGTH)
    val title: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = ActivityValidationMessages.SPORT_REQUIRED)
    val sport: ActivitySport?,
    @field:Schema(types = ["string", "null"], format = "date-time")
    override val startedAt: Instant? = null,
    @field:Schema(types = ["number", "null"], description = "Distance in kilometers")
    @field:DecimalMin(value = "0", inclusive = false, message = ActivityValidationMessages.DISTANCE_POSITIVE)
    override val distance: Double? = null,
    @field:Schema(types = ["integer", "null"])
    @field:DecimalMin(value = "0", inclusive = false, message = ActivityValidationMessages.DURATION_POSITIVE)
    override val timeTakenSeconds: Int? = null,
    @field:Schema(types = ["number", "null"], description = "Average speed in km/h")
    @field:DecimalMin(value = "0", inclusive = false, message = ActivityValidationMessages.AVERAGE_SPEED_POSITIVE)
    override val averagePaceKmh: Double? = null,
    @field:Schema(types = ["integer", "null"])
    @field:DecimalMin(value = "0", inclusive = false, message = ActivityValidationMessages.AVERAGE_HEART_RATE_POSITIVE)
    override val averageHeartRate: Int? = null,
    @field:Schema(types = ["integer", "null"], minimum = "0")
    @field:PositiveOrZero(message = ActivityValidationMessages.CALORIES_NON_NEGATIVE)
    override val calories: Int? = null,
) : ProtectedMetricValues

/** The **Protected Metrics** every **Activity** must have, with the error reported when one is missing. */
private val REQUIRED_PROTECTED_METRICS: Map<KProperty1<ProtectedMetricValues, Any?>, String> =
    mapOf(
        ProtectedMetricValues::startedAt to ActivityValidationMessages.START_TIME_REQUIRED,
        ProtectedMetricValues::distance to ActivityValidationMessages.DISTANCE_REQUIRED,
        ProtectedMetricValues::timeTakenSeconds to ActivityValidationMessages.DURATION_REQUIRED,
    )

/** Names of the **Protected Metrics** these values attempt to set. */
fun ProtectedMetricValues.suppliedProtectedMetricFields(): List<String> =
    ProtectedMetricValues.ALL.filter { it.get(this) != null }.map { it.name }

/**
 * The one mapping from request values to complete **Protected Metrics**.
 *
 * @throws RequestValidationException naming each missing required metric.
 */
fun ProtectedMetricValues.toProtectedMetrics(): ProtectedMetrics {
    val missing =
        REQUIRED_PROTECTED_METRICS
            .filterKeys { it.get(this) == null }
            .mapKeys { (metric, _) -> metric.name }
    if (missing.isNotEmpty()) throw RequestValidationException(missing)
    return ProtectedMetrics(
        startedAt = startedAt!!,
        distance = distance!!,
        timeTakenSeconds = timeTakenSeconds!!,
        averagePaceKmh = averagePaceKmh,
        averageHeartRate = averageHeartRate,
        calories = calories,
    )
}
