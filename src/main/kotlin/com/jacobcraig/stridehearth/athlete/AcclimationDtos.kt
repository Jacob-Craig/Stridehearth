package com.jacobcraig.stridehearth.athlete

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/** The athlete's **Heat Acclimation** and **Altitude Acclimation**: today's, and one entry per calendar day, ascending. */
data class AcclimationResponseDTO(
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["object", "null"],
        description = "Acclimation on the athlete's today; null before their first exposed day",
    )
    val current: AcclimationCurrentDTO?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Every day in range, never before the first exposed day nor after today; empty before it",
    )
    val days: List<AcclimationDayDTO>,
)

/** Acclimation on one day, in percent (0-100), unrounded. */
data class AcclimationCurrentDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val day: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val heatPct: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val altitudePct: Double,
)

/** One day of acclimation, in percent (0-100), unrounded, and whether the day was exposed. */
data class AcclimationDayDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val day: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val heatPct: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val altitudePct: Double,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Whether an Activity of at least 20 minutes that day was above 22 °C Ambient Temperature",
    )
    val heatExposed: Boolean,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Whether an Activity of at least 20 minutes that day was above 800 m Mean Altitude",
    )
    val altitudeExposed: Boolean,
)

fun AcclimationSeries.toDTO() =
    AcclimationResponseDTO(
        current = current?.let { AcclimationCurrentDTO(today, it.heatPct, it.altitudePct) },
        days = days.map { AcclimationDayDTO(it.day, it.heatPct, it.altitudePct, it.heatExposed, it.altitudeExposed) },
    )
