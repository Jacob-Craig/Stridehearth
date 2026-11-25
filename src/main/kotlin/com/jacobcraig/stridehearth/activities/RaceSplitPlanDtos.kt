package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.ProfilePoint
import com.jacobcraig.stridehearth.analytics.RaceSplit
import com.jacobcraig.stridehearth.analytics.RaceSplitPlan
import com.jacobcraig.stridehearth.analytics.SplitUnit
import io.swagger.v3.oas.annotations.media.Schema

/** A **Race Split Plan** over an Activity's route: target times per Split that hold Grade-Adjusted effort even. */
data class RaceSplitPlanResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityId: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The course's cleaned distance, in m")
    val courseDistanceM: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The Goal Time the splits sum to, in s")
    val goalSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val unit: SplitUnit,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "The Split Bias: how much longer, in percent, the second half takes than the first on even ground",
    )
    val biasPct: Double,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "The course's total climb from its cleaned altitude, in m; null without altitude",
    )
    val elevationGainM: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "One per km or mile from the start; the last may be partial")
    val splits: List<RaceSplitDTO>,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        description = "The course's elevation profile, at most 500 points; empty without altitude",
    )
    val profile: List<ProfilePointDTO>,
)

/** One Split's target time and its stretch of the course. */
data class RaceSplitDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 for the first Split")
    val index: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "From the start, in m")
    val startM: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "From the start, in m")
    val endM: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "In m")
    val distanceM: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The Split's target time, in s")
    val targetSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The target pace, in s per km or mile")
    val paceSecondsPerUnit: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The target time at the Split's end, in s")
    val cumulativeSeconds: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"], description = "In m; null without altitude")
    val elevationGainM: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"], description = "In m; null without altitude")
    val elevationLossM: Double?,
    @field:Schema(
        requiredMode = Schema.RequiredMode.REQUIRED,
        types = ["number", "null"],
        description = "Net rise over the Split's length, in percent; null without altitude",
    )
    val avgGradePct: Double?,
)

/** A point of the elevation profile. */
data class ProfilePointDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "From the start, in m")
    val distanceM: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Cleaned altitude, in m")
    val altitudeM: Double,
)

fun RaceSplitPlan.toDTO(
    activityId: Long,
    unit: SplitUnit,
) = RaceSplitPlanResponseDTO(
    activityId = activityId,
    courseDistanceM = courseDistanceM,
    goalSeconds = goalSeconds,
    unit = unit,
    biasPct = biasPct,
    elevationGainM = elevationGainM,
    splits = splits.map { it.toDTO() },
    profile = profile.map { it.toDTO() },
)

private fun RaceSplit.toDTO() =
    RaceSplitDTO(
        index = index,
        startM = startM,
        endM = endM,
        distanceM = distanceM,
        targetSeconds = targetSeconds,
        paceSecondsPerUnit = paceSecondsPerUnit,
        cumulativeSeconds = cumulativeSeconds,
        elevationGainM = elevationGainM,
        elevationLossM = elevationLossM,
        avgGradePct = avgGradePct,
    )

private fun ProfilePoint.toDTO() = ProfilePointDTO(distanceM = distanceM, altitudeM = altitudeM)
