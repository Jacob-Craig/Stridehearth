package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * The athlete's **Dashboard** payload. Exposes canonical domain primitives only; all display
 * formatting (durations, paces, dates) is performed by the client.
 */
data class DashboardResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val personalRecordSlots: List<PersonalRecordSlotDTO>,
)

/**
 * One **Benchmark Distance** slot on the Dashboard. [personalRecord] is `null` when no qualifying
 * **Best Effort** has been recorded for the benchmark yet.
 */
data class PersonalRecordSlotDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val benchmarkDistance: BenchmarkDistance,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val benchmarkName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val distanceMeters: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["object", "null"])
    val personalRecord: PersonalRecordDTO?,
)

/** The athlete's all-time **Personal Record** for a benchmark, linking to the record-holding **Activity**. */
data class PersonalRecordDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val bestEffortId: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityId: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityTitle: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityStartedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val elapsedTimeSeconds: Double,
)

/** Builds one slot per [BenchmarkDistance] of [sport] (shortest first), filling those with a Personal Record. */
fun buildPersonalRecordSlots(
    sport: ActivitySport,
    records: List<PersonalRecordRow>,
): List<PersonalRecordSlotDTO> {
    val recordsByBenchmark = records.associateBy { it.benchmarkDistance }
    return BenchmarkDistance
        .forSport(sport)
        .sortedBy { it.distanceMeters }
        .map { benchmark ->
            PersonalRecordSlotDTO(
                benchmarkDistance = benchmark,
                benchmarkName = benchmark.displayName,
                distanceMeters = benchmark.distanceMeters,
                personalRecord = recordsByBenchmark[benchmark]?.toDTO(),
            )
        }
}

fun PersonalRecordRow.toDTO(): PersonalRecordDTO =
    PersonalRecordDTO(
        bestEffortId = bestEffortId,
        activityId = activityId,
        activityTitle = activityTitle,
        activityStartedAt = activityStartedAt,
        elapsedTimeSeconds = elapsedTimeSeconds,
    )
