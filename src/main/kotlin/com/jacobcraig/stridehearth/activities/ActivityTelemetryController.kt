package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.api.ApiErrorResponseDoc
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST API exposing an activity's recorded telemetry for the interactive map and charts.
 *
 * The JSON payload is produced entirely by PostgreSQL (see [ActivityDetailsRepository.findTelemetryJson])
 * and streamed through verbatim, avoiding a deserialize/serialize round-trip of large arrays. Its shape is
 * published to OpenAPI as [ActivityTelemetryResponseDTO].
 */
@RestController
@RequestMapping("/api/activities")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class ActivityTelemetryController(
    private val activityDetailsRepository: ActivityDetailsRepository,
) {
    /**
     * Returns `200 OK` with the telemetry JSON for an activity owned by [currentUser].
     * Returns a uniform `404 Not Found` when the activity is missing, has no telemetry, or belongs to
     * another user, so activity IDs cannot be enumerated.
     */
    @GetMapping("/{id}/telemetry", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(operationId = "getActivityTelemetry", summary = "The Activity Details and Best Effort windows of an Activity")
    @ApiResponse(
        responseCode = "200",
        description = "The Activity Details",
        content = [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = ActivityTelemetryResponseDTO::class),
            ),
        ],
    )
    @ApiErrorResponseDoc(responseCode = "404", description = "No such Activity with Activity Details for this athlete")
    fun getTelemetry(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): ResponseEntity<String> {
        val json =
            activityDetailsRepository.findTelemetryJson(id, currentUser.id)
                ?: throw activityDetailsNotFound(id)
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json)
    }

    private fun activityDetailsNotFound(id: Long) = NoSuchElementException("No Activity Details for Activity $id")
}
