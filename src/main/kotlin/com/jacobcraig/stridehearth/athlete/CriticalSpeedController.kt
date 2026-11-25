package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.CriticalSpeedFitter
import com.jacobcraig.stridehearth.api.ApiErrorResponse
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
import java.time.Clock
import java.time.LocalDate

/**
 * The athlete's **Critical Speed** and **Critical Power** models for their local today, fitted live from their own
 * Runs' Peak Efforts of the last 90 days (#39). It never writes. A missing model is a 200 with its status.
 */
@RestController
@RequestMapping("/api/athlete")
class CriticalSpeedController(
    private val criticalSpeedService: CriticalSpeedService,
    private val athleteTimeZone: AthleteTimeZone,
    private val clock: Clock,
) {
    @GetMapping("/critical-speed")
    @Operation(
        operationId = "getCriticalSpeed",
        summary = "The athlete's Critical Speed / D′ (and Critical Power / W′) model from their last 90 days of Peak Efforts",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The authenticated athlete's models, or why there is none"),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun getCriticalSpeed(
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): CriticalSpeedResponseDTO {
        val today = LocalDate.now(clock.withZone(athleteTimeZone.of(currentUser.id)))
        val models = criticalSpeedService.live(currentUser.id, today)
        return CriticalSpeedResponseDTO(
            asOf = today,
            windowDays = CriticalSpeedFitter.WINDOW_DAYS,
            pace = models.pace.toDTO(),
            power = models.power?.toDTO(),
        )
    }
}
