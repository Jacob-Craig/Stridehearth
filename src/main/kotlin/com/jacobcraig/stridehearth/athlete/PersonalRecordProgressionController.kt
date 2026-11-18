package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.activities.PersonalRecordRepository
import com.jacobcraig.stridehearth.api.ApiErrorResponse
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The athlete's running **Personal Record Progression**, read only from their own Best Efforts. */
@RestController
@RequestMapping("/api/athlete")
class PersonalRecordProgressionController(
    private val personalRecordRepository: PersonalRecordRepository,
) {
    @GetMapping("/personal-record-progression")
    @Operation(
        operationId = "getPersonalRecordProgression",
        summary = "Every running Personal Record the athlete has set, per Benchmark Distance, oldest first",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The authenticated athlete's running Personal Record Progression"),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun getPersonalRecordProgression(
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): PersonalRecordProgressionResponseDTO {
        val sport = ActivitySport.RUN
        val steps = personalRecordRepository.findProgression(currentUser.id, sport)
        return PersonalRecordProgressionResponseDTO(progressions = buildProgressions(sport, steps))
    }
}
