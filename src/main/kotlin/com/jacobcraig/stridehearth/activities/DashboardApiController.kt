package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.api.ApiErrorResponse
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** JSON API backing the athlete's **Dashboard** in the React SPA. */
@RestController
@RequestMapping("/api/dashboard")
class DashboardApiController(
    private val personalRecordRepository: PersonalRecordRepository,
) {
    @GetMapping
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The authenticated athlete's running Personal Records"),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun getDashboard(
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): DashboardResponseDTO {
        val sport = ActivitySport.RUN
        val records = personalRecordRepository.findByUserIdAndSport(currentUser.id, sport)
        return DashboardResponseDTO(personalRecordSlots = buildPersonalRecordSlots(sport, records))
    }
}
