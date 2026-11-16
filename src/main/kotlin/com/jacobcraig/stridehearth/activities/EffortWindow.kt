package com.jacobcraig.stridehearth.activities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * Value object representing the temporal, spatial, and index boundary coordinates of an effort window.
 */
@Embeddable
data class EffortWindow(
    @Column(name = "start_index", nullable = false)
    var startIndex: Int = 0,
    @Column(name = "end_index", nullable = false)
    var endIndex: Int = 0,
    @Column(name = "start_time_seconds", nullable = false)
    var startTimeSeconds: Double = 0.0,
    @Column(name = "end_time_seconds", nullable = false)
    var endTimeSeconds: Double = 0.0,
    @Column(name = "start_distance_meters", nullable = false)
    var startDistanceMeters: Double = 0.0,
    @Column(name = "end_distance_meters", nullable = false)
    var endDistanceMeters: Double = 0.0,
) {
    val durationSeconds: Double get() = endTimeSeconds - startTimeSeconds
    val distanceMeters: Double get() = endDistanceMeters - startDistanceMeters
}
