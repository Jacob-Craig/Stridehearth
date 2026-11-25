package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.RaceDistance
import com.jacobcraig.stridehearth.analytics.RacePrediction
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/** The athlete's **Race Prognosis**: today's, with any race-day Course Derating, and one entry per day. */
data class RacePrognosisResponseDTO(
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["object", "null"],
        description = "The prognosis on the athlete's today; null when they have no Effective VO2max today",
    )
    val current: RacePrognosisCurrentDTO?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Every day in range with an Effective VO2max, ascending, never after today; without Course Derating",
    )
    val days: List<RacePrognosisDayDTO>,
)

/** The prognosis on the athlete's today and the Effective VO2max and Marathon Shape it comes from, unrounded. */
data class RacePrognosisCurrentDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val day: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Effective VO2max, in ml/kg/min")
    val effectiveVo2max: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Marathon Shape, 0 to 100")
    val marathonShapePct: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "One per race distance, shortest first")
    val predictions: List<RacePredictionDTO>,
)

/** A predicted finish time: the VDOT time, times the endurance penalty and the Course Derating. */
data class RacePredictionDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val distance: RaceDistance,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The race distance, in m")
    val meters: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The time Daniels' VDOT tables give, in s")
    val vdotSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The predicted finish time, in s")
    val predictedSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The endurance penalty of Marathon Shape, at least 1")
    val enduranceFactor: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The Course Derating of the race-day conditions, at least 1")
    val courseFactor: Double,
)

/** One day's predicted finish times, in s, without Course Derating. */
data class RacePrognosisDayDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val day: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val fiveKSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val tenKSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val halfSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val marathonSeconds: Double,
)

fun AthleteRacePrognosis.toDTO() =
    RacePrognosisResponseDTO(
        current =
            current?.let { today ->
                RacePrognosisCurrentDTO(
                    day = today.day,
                    effectiveVo2max = today.effectiveVo2max,
                    marathonShapePct = today.marathonShapePct,
                    predictions = today.predictions.map { it.toDTO() },
                )
            },
        days =
            days.map { day ->
                val seconds = day.predictions.associate { it.distance to it.predictedSeconds }
                RacePrognosisDayDTO(
                    day = day.day,
                    fiveKSeconds = seconds.getValue(RaceDistance.FIVE_K),
                    tenKSeconds = seconds.getValue(RaceDistance.TEN_K),
                    halfSeconds = seconds.getValue(RaceDistance.HALF_MARATHON),
                    marathonSeconds = seconds.getValue(RaceDistance.MARATHON),
                )
            },
    )

private fun RacePrediction.toDTO() =
    RacePredictionDTO(
        distance = distance,
        meters = distance.meters,
        vdotSeconds = vdotSeconds,
        predictedSeconds = predictedSeconds,
        enduranceFactor = enduranceFactor,
        courseFactor = courseFactor,
    )
