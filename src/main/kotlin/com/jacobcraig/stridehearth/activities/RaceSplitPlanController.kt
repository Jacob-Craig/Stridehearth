package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.RaceSplitPlanner
import com.jacobcraig.stridehearth.analytics.SplitUnit
import com.jacobcraig.stridehearth.api.ApiErrorResponse
import com.jacobcraig.stridehearth.api.ApiErrorResponseDoc
import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.users.AppUserDetails
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** A **Race Split Plan** over one of the signed-in athlete's own Activities; never stored. */
@RestController
@RequestMapping("/api/activities")
@ApiErrorResponseDoc(responseCode = "401", description = "Unauthenticated")
class RaceSplitPlanController(
    private val raceSplitPlanService: RaceSplitPlanService,
) {
    @GetMapping("/{id}/race-split-plan")
    @Operation(
        operationId = "getRaceSplitPlan",
        summary = "Target times per km or mile over an Activity's route that hold Grade-Adjusted effort even and sum to a Goal Time",
    )
    @ApiResponse(responseCode = "200", description = "The Race Split Plan")
    @ApiErrorResponseDoc(
        responseCode = "400",
        description = "Not a Run, Walk or Hike with a GPS route, or a Goal Time, unit or Split Bias out of range",
    )
    @ApiResponse(
        responseCode = "404",
        description = "No such Activity for this athlete",
        content = [Content(schema = Schema(implementation = ApiErrorResponse::class))],
    )
    fun getRaceSplitPlan(
        @PathVariable id: Long,
        @AuthenticationPrincipal currentUser: AppUserDetails,
        @Parameter(
            description = "The Goal Time, in s, from 60 to 86400",
            schema = Schema(type = "number", minimum = "60", maximum = "86400"),
        )
        @RequestParam
        goalSeconds: String,
        @Parameter(description = "The length each Split covers", schema = Schema(implementation = SplitUnit::class))
        @RequestParam(required = false)
        unit: String?,
        @Parameter(
            description = "The Split Bias, in percent from -5 to 5: positive makes the second half slower",
            schema = Schema(type = "number", minimum = "-5", maximum = "5"),
        )
        @RequestParam(required = false)
        biasPct: String?,
    ): RaceSplitPlanResponseDTO {
        val errors = mutableMapOf<String, String>()
        val goal = parseNumber(goalSeconds, "goalSeconds", MIN_GOAL_SECONDS..MAX_GOAL_SECONDS, errors)
        val bias =
            if (biasPct ==
                null
            ) {
                0.0
            } else {
                parseNumber(biasPct, "biasPct", -RaceSplitPlanner.MAX_BIAS_PCT..RaceSplitPlanner.MAX_BIAS_PCT, errors)
            }
        val splitUnit =
            if (unit == null) {
                SplitUnit.KM
            } else {
                SplitUnit.entries.firstOrNull { it.name == unit }
                    ?: null.also { errors["unit"] = "Must be one of ${SplitUnit.entries.joinToString()}" }
            }
        if (errors.isNotEmpty() || goal == null || bias == null || splitUnit == null) throw RequestValidationException(errors)
        return raceSplitPlanService.plan(id, currentUser.id, goal, splitUnit, bias).toDTO(id, splitUnit)
    }

    private fun parseNumber(
        value: String,
        field: String,
        range: ClosedFloatingPointRange<Double>,
        errors: MutableMap<String, String>,
    ): Double? {
        val number = value.toDoubleOrNull()
        if (number == null || number !in range) {
            errors[field] = "Must be a number from ${range.start.format()} to ${range.endInclusive.format()}"
            return null
        }
        return number
    }

    private fun Double.format() = if (this % 1.0 == 0.0) toLong().toString() else toString()

    private companion object {
        const val MIN_GOAL_SECONDS = 60.0
        const val MAX_GOAL_SECONDS = 86400.0
    }
}
