package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.api.ApiErrorResponseDoc
import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The signed-in athlete's **Heat Acclimation** and **Altitude Acclimation**; only ever their own.
 *
 * No SPA screen reads it yet: it serves a future consumer, such as acclimation feeding Heat Derating (ADR-0017).
 */
@RestController
@RequestMapping("/api/athlete/acclimation")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class AcclimationApiController(
    private val acclimationService: AcclimationService,
    private val athleteTimeZone: AthleteTimeZone,
    private val clock: Clock,
) {
    @GetMapping
    @Operation(
        operationId = "getAcclimation",
        summary = "The athlete's daily Heat and Altitude Acclimation, decayed to today",
    )
    @ApiResponse(responseCode = "200", description = "Today's acclimation and one entry per day")
    @ApiErrorResponseDoc(responseCode = "400", description = "A malformed date, or from after to")
    fun getAcclimation(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @Parameter(
            description = "First day (YYYY-MM-DD); defaults to, and never precedes, the first exposed day",
            schema = Schema(format = "date"),
        )
        @RequestParam(required = false)
        from: String?,
        @Parameter(
            description = "Last day (YYYY-MM-DD); defaults to, and is capped at, today in the athlete's time zone",
            schema = Schema(format = "date"),
        )
        @RequestParam(required = false)
        to: String?,
    ): AcclimationResponseDTO {
        val errors = mutableMapOf<String, String>()
        val fromDay = parseDate(from, "from", errors)
        val toDay = parseDate(to, "to", errors)
        if (errors.isEmpty() && fromDay != null && toDay != null && fromDay > toDay) errors["from"] = "Must not be after to"
        if (errors.isNotEmpty()) throw RequestValidationException(errors)
        val today = athleteTimeZone.localDate(currentUser.id, clock.instant())
        return acclimationService.series(currentUser.id, fromDay, toDay, today).toDTO()
    }

    private fun parseDate(
        value: String?,
        field: String,
        errors: MutableMap<String, String>,
    ): LocalDate? =
        value?.let {
            try {
                LocalDate.parse(it)
            } catch (_: DateTimeParseException) {
                errors[field] = "Must be a date as YYYY-MM-DD"
                null
            }
        }
}
