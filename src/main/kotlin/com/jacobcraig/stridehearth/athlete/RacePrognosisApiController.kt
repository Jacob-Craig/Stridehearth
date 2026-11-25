package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.RaceConditions
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

/** The signed-in athlete's **Race Prognosis**; only ever their own. */
@RestController
@RequestMapping("/api/athlete/race-prognosis")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class RacePrognosisApiController(
    private val racePrognosisService: RacePrognosisService,
    private val athleteTimeZone: AthleteTimeZone,
    private val clock: Clock,
) {
    @GetMapping
    @Operation(
        operationId = "getRacePrognosis",
        summary = "The athlete's predicted 5K, 10K, Half and Marathon times today, and on each day",
    )
    @ApiResponse(
        responseCode = "200",
        description = "Today's predictions, derated for any race-day conditions, and one entry per day with an Effective VO2max",
    )
    @ApiErrorResponseDoc(
        responseCode = "400",
        description = "A malformed date or number, from after to, or race-day conditions out of range",
    )
    fun getRacePrognosis(
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
        @Parameter(
            description = "Race-day Ambient Temperature in °C, from -30 to 50; derates today's predictions only",
            schema = Schema(type = "number", minimum = "-30", maximum = "50"),
        )
        @RequestParam(required = false)
        temperatureC: String?,
        @Parameter(
            description = "The course's total climb in m, from 0 to 5000; derates today's predictions only",
            schema = Schema(type = "number", minimum = "0", maximum = "5000"),
        )
        @RequestParam(required = false)
        elevationGainM: String?,
    ): RacePrognosisResponseDTO {
        val errors = mutableMapOf<String, String>()
        val fromDay = parseDate(from, "from", errors)
        val toDay = parseDate(to, "to", errors)
        if (errors.isEmpty() && fromDay != null && toDay != null && fromDay > toDay) errors["from"] = "Must not be after to"
        val conditions =
            RaceConditions(
                temperatureC = parseNumber(temperatureC, "temperatureC", MIN_TEMPERATURE_C..MAX_TEMPERATURE_C, errors),
                elevationGainM = parseNumber(elevationGainM, "elevationGainM", 0.0..MAX_ELEVATION_GAIN_M, errors),
            )
        if (errors.isNotEmpty()) throw RequestValidationException(errors)
        val today = athleteTimeZone.localDate(currentUser.id, clock.instant())
        return racePrognosisService.prognosis(currentUser.id, fromDay, toDay, today, conditions).toDTO()
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

    private fun parseNumber(
        value: String?,
        field: String,
        range: ClosedFloatingPointRange<Double>,
        errors: MutableMap<String, String>,
    ): Double? {
        if (value == null) return null
        val number = value.toDoubleOrNull()
        if (number == null || number !in range) {
            errors[field] = "Must be a number from ${range.start.format()} to ${range.endInclusive.format()}"
            return null
        }
        return number
    }

    private fun Double.format() = if (this % 1.0 == 0.0) toLong().toString() else toString()

    private companion object {
        const val MIN_TEMPERATURE_C = -30.0
        const val MAX_TEMPERATURE_C = 50.0
        const val MAX_ELEVATION_GAIN_M = 5000.0
    }
}
