package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.activities.ActivityPeakEffortRepository
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
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The athlete's best running **Peak Efforts**, read only from their own Runs. "Recent" means started on one of the
 * last [WINDOW_DAYS] days, today included. Days are UTC until athletes have a time zone of their own.
 */
@RestController
@RequestMapping("/api/athlete")
class AthletePeakEffortController(
    private val peakEffortRepository: ActivityPeakEffortRepository,
) {
    @GetMapping("/peak-efforts")
    @Operation(
        operationId = "getPeakEfforts",
        summary = "The athlete's best Peak Effort per Benchmark Duration, over the last 90 days and all-time",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The authenticated athlete's best running Peak Efforts"),
        ApiResponse(
            responseCode = "401",
            description = "Unauthenticated",
            content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
        ),
    )
    fun getPeakEfforts(
        @AuthenticationPrincipal currentUser: AppUserDetails,
    ): PeakEffortsResponseDTO {
        val since =
            LocalDate
                .now(ZoneOffset.UTC)
                .minusDays(WINDOW_DAYS - 1L)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
        return buildPeakEfforts(
            windowDays = WINDOW_DAYS,
            allTime = peakEffortRepository.findBestPeakEfforts(currentUser.id),
            recent = peakEffortRepository.findBestPeakEfforts(currentUser.id, since),
        )
    }

    companion object {
        const val WINDOW_DAYS = 90
    }
}
