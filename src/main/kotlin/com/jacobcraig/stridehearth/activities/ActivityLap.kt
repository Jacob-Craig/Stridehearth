package com.jacobcraig.stridehearth.activities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * Relational entity representing a single device-recorded lap for an [Activity].
 */
@Entity
@Table(
    name = "activity_laps",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_activity_laps_activity_lap_number",
            columnNames = ["activity_id", "lap_number"],
        ),
    ],
)
class ActivityLap(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "activity_id", nullable = false)
    var activityId: Long,
    @Column(name = "lap_number", nullable = false)
    var lapNumber: Int,
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,
    @Column(nullable = false)
    var distance: Double,
    @Column(name = "time_taken_seconds", nullable = false)
    var timeTakenSeconds: Int,
    @Column(name = "average_pace_kmh")
    var averagePaceKmh: Double? = null,
    @Column(name = "average_heart_rate")
    var averageHeartRate: Int? = null,
    @Column(name = "elevation_gain_meters")
    var elevationGainMeters: Double? = null,
)
