package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.FitnessDay
import com.jacobcraig.stridehearth.analytics.MarathonShapeCalculator
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/** The athlete's **Marathon Shape**: today's, and one entry per calendar day. */
data class MarathonShapeResponseDTO(
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["object", "null"],
        description = "Marathon Shape on the athlete's today; null when they have no Effective VO2max today",
    )
    val current: MarathonShapeCurrentDTO?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Every day in range, never before the first VO2max Estimate nor after today; empty before it",
    )
    val days: List<MarathonShapeDayDTO>,
)

/** Marathon Shape on the athlete's today, its two parts and the targets they are measured against, unrounded. */
data class MarathonShapeCurrentDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val day: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Marathon Shape, 0 to 100")
    val pct: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Volume Score, 0 to 1")
    val volumeScore: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Long-Run Score, 0 to 1")
    val longRunScore: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Run distance per week over the last 26 weeks, in km")
    val weeklyKm: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The weekly distance targeted, in km")
    val targetWeeklyKm: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The long run targeted, in km")
    val targetLongRunKm: Double,
)

/** One day of Marathon Shape, 0 to 100, unrounded. */
data class MarathonShapeDayDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val day: LocalDate,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Null when the day has no Effective VO2max",
    )
    val pct: Double?,
)

fun FitnessSeries.toMarathonShapeDTO() =
    MarathonShapeResponseDTO(
        current = current?.toMarathonShapeCurrentDTO(),
        days = days.map { MarathonShapeDayDTO(it.day, it.marathonShape?.pct) },
    )

private fun FitnessDay.toMarathonShapeCurrentDTO(): MarathonShapeCurrentDTO? {
    val shape = marathonShape ?: return null
    val vo2max = effectiveVo2max ?: return null
    return MarathonShapeCurrentDTO(
        day = day,
        pct = shape.pct,
        volumeScore = shape.volumeScore,
        longRunScore = shape.longRunScore,
        weeklyKm = shape.weeklyKm,
        targetWeeklyKm = MarathonShapeCalculator.targetWeeklyKm(vo2max),
        targetLongRunKm = MarathonShapeCalculator.targetLongRunKm(vo2max),
    )
}
