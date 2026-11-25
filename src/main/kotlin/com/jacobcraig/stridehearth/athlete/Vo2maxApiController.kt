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

/** The signed-in athlete's **Effective VO2max** and their Runs' **VO2max Estimates**; only ever their own. */
@RestController
@RequestMapping("/api/athlete/vo2max")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class Vo2maxApiController(
    private val fitnessService: AthleteFitnessService,
    private val athleteTimeZone: AthleteTimeZone,
    private val clock: Clock,
) {
    @GetMapping
    @Operation(
        operationId = "getVo2max",
        summary = "The athlete's daily Effective VO2max, continued to today, and their Runs' VO2max Estimates",
    )
    @ApiResponse(responseCode = "200", description = "Today's Effective VO2max, one entry per day, and the Runs in range")
    @ApiErrorResponseDoc(responseCode = "400", description = "A malformed date, or from after to")
    fun getVo2max(
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @Parameter(
            description = "First day (YYYY-MM-DD); defaults to, and never precedes, the first VO2max Estimate",
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
    ): Vo2maxResponseDTO {
        val errors = mutableMapOf<String, String>()
        val fromDay = parseDate(from, "from", errors)
        val toDay = parseDate(to, "to", errors)
        if (errors.isEmpty() && fromDay != null && toDay != null && fromDay > toDay) errors["from"] = "Must not be after to"
        if (errors.isNotEmpty()) throw RequestValidationException(errors)
        val today = athleteTimeZone.localDate(currentUser.id, clock.instant())
        return fitnessService.series(currentUser.id, fromDay, toDay, today).toDTO()
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
