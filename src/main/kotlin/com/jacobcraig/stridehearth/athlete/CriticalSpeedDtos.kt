package com.jacobcraig.stridehearth.athlete

import com.fasterxml.jackson.annotation.JsonProperty
import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.CriticalSpeedStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/**
 * The athlete's 2-parameter models as of their local today ([asOf]), from their Run Peak Efforts of the last
 * [windowDays] days: [pace] over Grade-Adjusted speed (**Critical Speed**, m/s, and **D′**, m) and [power]
 * (**Critical Power**, W, and **W′**, J), null when they have no power Peak Effort in the window.
 */
data class CriticalSpeedResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val asOf: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val windowDays: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val pace: CriticalSpeedFitDTO,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["object", "null"])
    val power: CriticalSpeedFitDTO?,
)

/**
 * One fit. [criticalValue] and [reserveCapacity] are null unless [status] is MODEL; [rSquared] is null when there
 * were too few [points] to fit.
 */
data class CriticalSpeedFitDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val status: CriticalSpeedStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val criticalValue: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val reserveCapacity: Double?,
    // Named explicitly: bean introspection would otherwise read the getter getRSquared as "rsquared".
    @field:JsonProperty("rSquared")
    @get:JsonProperty("rSquared")
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["number", "null"])
    val rSquared: Double?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val pointCount: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val points: List<CriticalSpeedPointDTO>,
)

/** A fitted point: the best Peak Effort [value] (m/s or W) at a **Benchmark Duration** and its **Activity**. */
data class CriticalSpeedPointDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val benchmarkDuration: BenchmarkDuration,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val durationSeconds: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val value: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityId: Long,
)

fun CriticalSpeedModel.toDTO() =
    CriticalSpeedFitDTO(
        status = fit.status,
        criticalValue = fit.criticalValue,
        reserveCapacity = fit.reserveCapacity,
        rSquared = fit.rSquared,
        pointCount = fit.pointCount,
        points = points.map { CriticalSpeedPointDTO(it.benchmarkDuration, it.benchmarkDuration.seconds, it.value, it.activityId) },
    )
