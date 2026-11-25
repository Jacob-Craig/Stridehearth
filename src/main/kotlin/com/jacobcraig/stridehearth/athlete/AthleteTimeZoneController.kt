package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.api.ApiErrorResponseDoc
import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.users.AppUserDetails
import com.jacobcraig.stridehearth.users.UserResponseDTO
import com.jacobcraig.stridehearth.users.toResponseDTO
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The signed-in athlete's **Athlete Time Zone** (ADR-0015). */
@RestController
@RequestMapping("/api/athlete/time-zone")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class AthleteTimeZoneController(
    private val athleteTimeZone: AthleteTimeZone,
) {
    @PutMapping
    @Operation(operationId = "setAthleteTimeZone", summary = "Sets the IANA time zone the athlete's local dates are counted in")
    @ApiResponse(responseCode = "200", description = "The athlete's profile with the zone")
    @ApiErrorResponseDoc(responseCode = "400", description = "Unknown timezone")
    fun setTimeZone(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @RequestBody request: AthleteTimeZoneRequestDTO,
    ): UserResponseDTO {
        val timeZone = request.timeZone ?: throw RequestValidationException(mapOf("timeZone" to "Unknown timezone"))
        return athleteTimeZone.set(currentUser.id, timeZone).toResponseDTO()
    }
}
