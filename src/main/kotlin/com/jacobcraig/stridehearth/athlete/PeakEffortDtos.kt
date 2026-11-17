package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.activities.BestPeakEffortRow
import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * The athlete's best running **Peak Efforts**: one slot per **Benchmark Duration** (shortest first) for Grade-Adjusted
 * speed ([pace], m/s) and for power ([power], W; empty when the athlete has no power peak at all). Each slot holds
 * the best over the last [windowDays] days and the all-time best.
 */
data class PeakEffortsResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val windowDays: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val pace: List<PeakEffortSlotDTO>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val power: List<PeakEffortSlotDTO>,
)

/** One **Benchmark Duration**'s best Peak Efforts; either is `null` when no Run in its range spans the duration. */
data class PeakEffortSlotDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val benchmarkDuration: BenchmarkDuration,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val durationSeconds: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["object", "null"])
    val recent: PeakEffortDTO?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["object", "null"])
    val allTime: PeakEffortDTO?,
)

/** A best Peak Effort's [value] (m/s or W) and the **Activity** it was set in. */
data class PeakEffortDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val value: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityId: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityTitle: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityStartedAt: Instant,
)

/** Builds the response from the all-time and recent best Peak Efforts. */
fun buildPeakEfforts(
    windowDays: Int,
    allTime: List<BestPeakEffortRow>,
    recent: List<BestPeakEffortRow>,
): PeakEffortsResponseDTO {
    fun slots(metric: PeakEffortMetric): List<PeakEffortSlotDTO> {
        val allTimeByDuration = allTime.filter { it.metric == metric }.associateBy { it.benchmarkDuration }
        val recentByDuration = recent.filter { it.metric == metric }.associateBy { it.benchmarkDuration }
        return BenchmarkDuration.entries.map { duration ->
            PeakEffortSlotDTO(
                benchmarkDuration = duration,
                durationSeconds = duration.seconds,
                recent = recentByDuration[duration]?.toDTO(),
                allTime = allTimeByDuration[duration]?.toDTO(),
            )
        }
    }
    val hasPower = allTime.any { it.metric == PeakEffortMetric.POWER }
    return PeakEffortsResponseDTO(
        windowDays = windowDays,
        pace = slots(PeakEffortMetric.GAP_SPEED),
        power = if (hasPower) slots(PeakEffortMetric.POWER) else emptyList(),
    )
}

private fun BestPeakEffortRow.toDTO() = PeakEffortDTO(value, activityId, activityTitle, activityStartedAt)
