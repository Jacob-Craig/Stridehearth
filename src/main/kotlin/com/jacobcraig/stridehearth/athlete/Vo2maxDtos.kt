package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.activities.BenchmarkDistance
import com.jacobcraig.stridehearth.analytics.FitnessDay
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/** The athlete's **Effective VO2max**: today's, one entry per calendar day, and the Runs with a VO2max Estimate. */
data class Vo2maxResponseDTO(
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["object", "null"],
        description = "Effective VO2max on the athlete's today; null before their first VO2max Estimate",
    )
    val current: Vo2maxCurrentDTO?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Every day in range, never before the first VO2max Estimate nor after today; empty before it",
    )
    val days: List<Vo2maxDayDTO>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The Runs in range with a VO2max Estimate, oldest first")
    val runs: List<Vo2maxRunDTO>,
)

/** Effective VO2max on the athlete's today, in ml/kg/min, unrounded, with the Correction Factor and where it came from. */
data class Vo2maxCurrentDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val day: LocalDate,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Null when no Run of the last 30 days has a VO2max Estimate",
    )
    val effectiveVo2max: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Between 0.8 and 1.2; 1.0 without a calibrating race")
    val correctionFactor: Double,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["object", "null"],
        description = "The Best Effort the Correction Factor came from; null when it is 1.0 for want of one",
    )
    val correctionSource: CorrectionSourceDTO?,
)

/** A Run that was essentially one Best Effort, and that effort's Daniels VDOT. */
data class CorrectionSourceDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityId: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val benchmarkDistance: BenchmarkDistance,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val vdot: Double,
)

/** One day of Effective VO2max, in ml/kg/min, unrounded. */
data class Vo2maxDayDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val day: LocalDate,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Null when no Run of the 30 days ending that day has a VO2max Estimate",
    )
    val effectiveVo2max: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val correctionFactor: Double,
)

/** One Run's VO2max Estimate, times the Correction Factor of its day, in ml/kg/min, unrounded. */
data class Vo2maxRunDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityId: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The Run's day in the athlete's time zone")
    val day: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val vo2maxEstimate: Double,
)

fun FitnessSeries.toDTO() =
    Vo2maxResponseDTO(
        current = current?.toCurrentDTO(),
        days = days.map { Vo2maxDayDTO(it.day, it.effectiveVo2max, it.correctionFactor) },
        runs = runs.map { Vo2maxRunDTO(it.activityId, it.day, it.vo2maxEstimate) },
    )

private fun FitnessDay.toCurrentDTO() =
    Vo2maxCurrentDTO(
        day = day,
        effectiveVo2max = effectiveVo2max,
        correctionFactor = correctionFactor,
        correctionSource = correctionSource?.let { CorrectionSourceDTO(it.activityId, it.benchmarkDistance, it.vdot) },
    )
