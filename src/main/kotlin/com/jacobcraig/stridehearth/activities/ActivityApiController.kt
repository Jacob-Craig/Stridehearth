package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.api.ApiErrorResponse
import com.jacobcraig.stridehearth.api.PagedResponseDTO
import com.jacobcraig.stridehearth.api.toPagedResponse
import com.jacobcraig.stridehearth.analytics.WorkoutType
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * JSON API for browsing and managing the athlete's **Activities** in the React SPA.
 *
 * Activities owned by another athlete are reported as `404 Not Found` so IDs cannot be enumerated.
 */
@RestController
@Validated
@RequestMapping("/api/activities")
@ApiResponses(
    ApiResponse(
        responseCode = "401",
        description = "Unauthenticated",
        content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
    ),
)
class ActivityApiController(
    private val activityService: ActivityService,
) {
    @GetMapping
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "One page of the athlete's matching Activities, newest first"),
        ApiResponse(
            responseCode = "400",
            description = "Unsupported page size",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun listActivities(
        @Parameter(description = "Case-insensitive substring of the Activity title") @RequestParam(required = false) q: String?,
        @Parameter(description = "Only Activities of this sport") @RequestParam(required = false) sport: ActivitySport?,
        @Parameter(description = "Only Activities from this Activity Source") @RequestParam(required = false) source: ActivitySource?,
        @Parameter(description = "Only Runs of this Workout Type") @RequestParam(required = false) workoutType: WorkoutType?,
        @Parameter(description = "Zero-based page index (ignored when size is 'all')") @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @Parameter(
            description = "Page size from 1 to 100, or 'all' for every matching Activity in one page",
            schema = Schema(type = "string", defaultValue = ActivityPageSize.DEFAULT, example = "20"),
        )
        @RequestParam(defaultValue = ActivityPageSize.DEFAULT) size: String,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): PagedResponseDTO<ActivitySummaryDTO> {
        val pageable = ActivityPageSize.parse(size).toPageable(page, NEWEST_FIRST)
        val filter = ActivityFilter(titleContains = q, sport = sport, source = source, workoutType = workoutType)
        return activityService.getActivitySummaries(currentUser.id, filter, pageable).toPagedResponse()
    }

    @GetMapping("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The Activity with its Laps, Activity History Entries and Best Efforts"),
        ApiResponse(
            responseCode = "404",
            description = "No such Activity for this athlete",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun getActivity(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): ActivityDetailDTO = activityService.getActivityDetail(id, currentUser.id) ?: throw activityNotFound(id)

    @PostMapping
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The created MANUAL Activity"),
        ApiResponse(
            responseCode = "400",
            description = "Validation failed",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun createActivity(
        @Valid @RequestBody request: ActivityCreateRequestDTO,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): ResponseEntity<ActivitySummaryDTO> {
        val created =
            activityService.createManualActivity(
                userId = currentUser.id,
                title = request.title!!,
                sport = request.sport!!,
                metrics = request.toProtectedMetrics(),
            )
        return ResponseEntity.created(URI.create("/api/activities/${created.id}")).body(created)
    }

    @PutMapping("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The updated Activity"),
        ApiResponse(
            responseCode = "400",
            description = "Validation failed, including any attempt to edit Protected Metrics",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No such Activity for this athlete",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun updateActivity(
        @PathVariable id: Long,
        @Valid @RequestBody request: ActivityUpdateRequestDTO,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): ActivitySummaryDTO = activityService.updateActivity(id, currentUser.id, request) ?: throw activityNotFound(id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Deleted; Personal Records recalibrated"),
        ApiResponse(
            responseCode = "404",
            description = "No such Activity for this athlete",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun deleteActivity(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ) {
        if (!activityService.deleteActivity(id, currentUser.id)) throw activityNotFound(id)
    }

    private fun activityNotFound(id: Long) = NoSuchElementException("Activity $id not found")

    private companion object {
        val NEWEST_FIRST: Sort = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))
    }
}
