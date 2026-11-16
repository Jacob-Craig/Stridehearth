package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.api.ApiErrorResponse
import com.jacobcraig.stridehearth.api.ApiErrorResponseDoc
import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * JSON API behind the bulk **Activity** management workspace: batch update, batch delete and the
 * **Best Efforts** backfill. Batches are all or nothing: if any selected Activity is missing or another
 * athlete's, nothing changes and the request is reported as `404 Not Found`.
 */
@RestController
@RequestMapping("/api/activities")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class ActivityBatchApiController(
    private val activityService: ActivityService,
    private val bestEffortBackfillService: BestEffortBackfillService,
) {
    @PatchMapping("/batch")
    @ApiResponse(responseCode = "200", description = "The Activities were updated, with BATCH_EDITED history recorded")
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "No Activities selected, or neither a title nor a sport given",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "A selected Activity is missing or belongs to another athlete; nothing was changed",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun batchUpdate(
        @Valid @RequestBody request: BatchUpdateRequestDTO,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): BatchMutationResultDTO {
        val change =
            BatchMetadataChange.of(request.title, request.sport)
                ?: throw RequestValidationException(
                    mapOf("title" to BatchMetadataChange.NOTHING_TO_APPLY, "sport" to BatchMetadataChange.NOTHING_TO_APPLY),
                )
        val result =
            activityService.batchUpdateActivities(request.activityIds!!, currentUser.id, change)
                ?: throw NoSuchElementException(ActivityBatchApiErrors.ACTIVITIES_NOT_FOUND)
        return BatchMutationResultDTO(affectedCount = result.modifiedCount, matchedCount = result.matchedCount)
    }

    @PostMapping("/batch-delete")
    @ApiResponse(responseCode = "200", description = "The Activities were deleted; Personal Records recalibrated")
    @ApiResponses(
        ApiResponse(
            responseCode = "400",
            description = "No Activities selected",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "A selected Activity is missing or belongs to another athlete; nothing was deleted",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun batchDelete(
        @Valid @RequestBody request: BatchDeleteRequestDTO,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): BatchMutationResultDTO {
        val deletedCount =
            activityService.batchDeleteActivities(request.activityIds!!, currentUser.id)
                ?: throw NoSuchElementException(ActivityBatchApiErrors.ACTIVITIES_NOT_FOUND)
        return BatchMutationResultDTO(affectedCount = deletedCount, matchedCount = deletedCount)
    }

    @PostMapping("/backfill-best-efforts")
    @ApiResponse(
        responseCode = "200",
        description = "Best Efforts detected for historical Activities that lack them; Personal Records updated",
    )
    fun backfillBestEfforts(
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): BestEffortBackfillResultDTO = bestEffortBackfillService.backfillForUser(currentUser.id).toDto()
}
