package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ActivitySport
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * Relational entity representing a peak continuous best effort for a specific [BenchmarkDistance]
 * within an [Activity].
 */
@Entity
@Table(
    name = "activity_best_effort",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_activity_best_effort_activity_benchmark",
            columnNames = ["activity_id", "benchmark_distance"],
        ),
    ],
)
class ActivityBestEffort(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "activity_id", nullable = false)
    var activityId: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var sport: ActivitySport,
    @Enumerated(EnumType.STRING)
    @Column(name = "benchmark_distance", nullable = false, length = 32)
    var benchmarkDistance: BenchmarkDistance,
    @Column(name = "elapsed_time_seconds", nullable = false)
    var elapsedTimeSeconds: Double,
    @Embedded
    var window: EffortWindow = EffortWindow(),
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    constructor(
        id: Long? = null,
        activityId: Long,
        sport: ActivitySport,
        benchmarkDistance: BenchmarkDistance,
        elapsedTimeSeconds: Double,
        startIndex: Int,
        endIndex: Int,
        startTimeSeconds: Double,
        endTimeSeconds: Double,
        startDistanceMeters: Double,
        endDistanceMeters: Double,
        createdAt: Instant = Instant.now(),
    ) : this(
        id = id,
        activityId = activityId,
        sport = sport,
        benchmarkDistance = benchmarkDistance,
        elapsedTimeSeconds = elapsedTimeSeconds,
        window =
            EffortWindow(
                startIndex = startIndex,
                endIndex = endIndex,
                startTimeSeconds = startTimeSeconds,
                endTimeSeconds = endTimeSeconds,
                startDistanceMeters = startDistanceMeters,
                endDistanceMeters = endDistanceMeters,
            ),
        createdAt = createdAt,
    )

    val startIndex: Int get() = window.startIndex
    val endIndex: Int get() = window.endIndex
    val startTimeSeconds: Double get() = window.startTimeSeconds
    val endTimeSeconds: Double get() = window.endTimeSeconds
    val startDistanceMeters: Double get() = window.startDistanceMeters
    val endDistanceMeters: Double get() = window.endDistanceMeters
}
