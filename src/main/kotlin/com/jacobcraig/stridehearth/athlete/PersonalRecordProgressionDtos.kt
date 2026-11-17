package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.activities.BenchmarkDistance
import com.jacobcraig.stridehearth.activities.PersonalRecordStepRow
import com.jacobcraig.stridehearth.system.ActivitySport
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/** The athlete's running **Personal Record Progression**, one entry per **Benchmark Distance**, shortest first. */
data class PersonalRecordProgressionResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val progressions: List<BenchmarkProgressionDTO>,
)

/** The records set over time for one **Benchmark Distance**, oldest first; empty when it has none. */
data class BenchmarkProgressionDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val benchmarkDistance: BenchmarkDistance,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val benchmarkName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val distanceMeters: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val records: List<PersonalRecordStepDTO>,
)

/** One step: the Best Effort that became the **Personal Record** when its **Activity** was run. */
data class PersonalRecordStepDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityId: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityTitle: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityStartedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val elapsedTimeSeconds: Double,
)

/** Groups [steps] under every [BenchmarkDistance] of [sport], shortest first. */
fun buildProgressions(
    sport: ActivitySport,
    steps: List<PersonalRecordStepRow>,
): List<BenchmarkProgressionDTO> {
    val stepsByBenchmark = steps.groupBy { it.benchmarkDistance }
    return BenchmarkDistance
        .forSport(sport)
        .sortedBy { it.distanceMeters }
        .map { benchmark ->
            BenchmarkProgressionDTO(
                benchmarkDistance = benchmark,
                benchmarkName = benchmark.displayName,
                distanceMeters = benchmark.distanceMeters,
                records =
                    stepsByBenchmark[benchmark].orEmpty().map {
                        PersonalRecordStepDTO(it.activityId, it.activityTitle, it.activityStartedAt, it.elapsedTimeSeconds)
                    },
            )
        }
}
