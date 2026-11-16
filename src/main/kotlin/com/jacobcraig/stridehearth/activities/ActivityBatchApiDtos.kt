package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/**
 * Request body for applying a new [title] and/or [sport] to several **Activities** at once. At least one
 * of the two must be given; a blank [title] means "keep each Activity's title".
 */
data class BatchUpdateRequestDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotEmpty(message = ActivityBatchApiErrors.NO_ACTIVITIES_SELECTED)
    val activityIds: List<Long>?,
    @field:Schema(types = ["string", "null"])
    @field:Size(max = ActivityValidationMessages.TITLE_MAX_LENGTH, message = ActivityValidationMessages.TITLE_LENGTH)
    val title: String? = null,
    @field:Schema(types = ["string", "null"])
    val sport: ActivitySport? = null,
)

/** Request body for permanently deleting several **Activities** at once. */
data class BatchDeleteRequestDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotEmpty(message = ActivityBatchApiErrors.NO_ACTIVITIES_SELECTED)
    val activityIds: List<Long>?,
)

/**
 * Outcome of a batch update or delete, as counts only (the client words the summary). [matchedCount]
 * Activities were selected, all of them the athlete's; [affectedCount] of those were changed or deleted
 * (fewer than [matchedCount] only for an update where some already matched the target values).
 */
data class BatchMutationResultDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val affectedCount: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val matchedCount: Int,
)

/** Outcome of a **Best Efforts** backfill over the athlete's historical RUN Activities. */
data class BestEffortBackfillResultDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activitiesProcessed: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val bestEffortsCreated: Int,
)

fun BackfillResult.toDto() = BestEffortBackfillResultDTO(activitiesProcessed, bestEffortsCreated)

/** Error messages of the batch JSON API. */
object ActivityBatchApiErrors {
    const val NO_ACTIVITIES_SELECTED = "Please select at least one activity"

    /** Deliberately the same whether an id is missing or another athlete's, so batches cannot probe ownership. */
    const val ACTIVITIES_NOT_FOUND = "One or more of the selected activities were not found"
}
