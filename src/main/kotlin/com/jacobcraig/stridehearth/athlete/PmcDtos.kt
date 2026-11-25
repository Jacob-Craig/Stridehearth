package com.jacobcraig.stridehearth.athlete

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/** The athlete's **Performance Management Chart**: one entry per calendar day, ascending. */
data class PmcResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The IANA time zone the days are counted in")
    val timeZone: String,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["string", "null"],
        format = "date",
        description = "The athlete's first day with a scored Activity; null without one",
    )
    val firstDay: LocalDate?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Every day in range, never before firstDay; empty without a scored Activity",
    )
    val days: List<PmcDayDTO>,
)

/** One day of the chart and its **Daily Load**. Values are unrounded. */
data class PmcDayDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val date: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The day's summed Training Load; 0 without Activities")
    val trainingLoad: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityCount: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Activities without a Training Load, which count as 0")
    val unscoredActivityCount: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Fitness (CTL)")
    val ctl: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Fatigue (ATL)")
    val atl: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Form (TSB): yesterday's Fitness minus yesterday's Fatigue")
    val tsb: Double,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Uncoupled Acute:Chronic Workload Ratio; null for the first 34 days or without chronic load",
    )
    val acwr: Double?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Foster's Monotony; null for the first 6 days or a week of equal loads",
    )
    val monotony: Double?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Foster's Strain; null with Monotony",
    )
    val strain: Double?,
)

fun PmcSeries.toDTO() =
    PmcResponseDTO(
        timeZone = if (timeZone == java.time.ZoneOffset.UTC) "UTC" else timeZone.id,
        firstDay = firstDay,
        days =
            days.map {
                PmcDayDTO(
                    date = it.date,
                    trainingLoad = it.load.trainingLoad,
                    activityCount = it.load.activityCount,
                    unscoredActivityCount = it.load.unscoredActivityCount,
                    ctl = it.metrics.ctl,
                    atl = it.metrics.atl,
                    tsb = it.metrics.tsb,
                    acwr = it.metrics.acwr,
                    monotony = it.metrics.monotony,
                    strain = it.metrics.strain,
                )
            },
    )
