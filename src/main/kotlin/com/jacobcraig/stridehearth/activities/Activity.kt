package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.activities.history.FieldChange
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import kotlin.reflect.KProperty1

@Entity
@Table(
    name = "activity",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_activity_user_source_external_id",
            columnNames = ["user_id", "source", "external_id"],
        ),
    ],
)
class Activity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false)
    var title: String,
    @Column(name = "sport", nullable = false)
    @Enumerated(EnumType.STRING)
    var sport: ActivitySport = ActivitySport.RUN,
    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,
    @Column(nullable = false)
    var distance: Double,
    @Column(name = "time_taken_seconds", nullable = false)
    var timeTakenSeconds: Int,
    // Many activities belong to one user. LAZY so the user row is only loaded when accessed.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    @Column(name = "average_pace_kmh")
    var averagePaceKmh: Double? = null,
    @Column(name = "average_heart_rate")
    var averageHeartRate: Int? = null,
    @Column
    var calories: Int? = null,
    @Column(name = "source", nullable = false)
    @Enumerated(EnumType.STRING)
    var source: ActivitySource,
    // Null for manually entered activities; set by importers (e.g. composite of FIT file_id message fields)
    @Column(name = "external_id")
    var externalId: String? = null,
) {
    /**
     * True when this Activity's **Protected Metrics** are immutable: it was sourced from an external
     * file (any non-`MANUAL` **Activity Source**) or is backed by recorded **Activity Details**.
     */
    fun hasProtectedMetrics(hasActivityDetails: Boolean): Boolean = source != ActivitySource.MANUAL || hasActivityDetails

    /** Sets the title (trimmed; blank is ignored) and sport when given, returning each field that changed. */
    fun applyMetadataChanges(
        newTitle: String?,
        newSport: ActivitySport?,
    ): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()
        val cleanTitle = newTitle?.trim()?.ifEmpty { null }
        if (cleanTitle != null && this.title != cleanTitle) {
            changes.add(FieldChange.Title(from = this.title, to = cleanTitle))
            this.title = cleanTitle
        }
        if (newSport != null && this.sport != newSport) {
            changes.add(FieldChange.Sport(from = this.sport, to = newSport))
            this.sport = newSport
        }
        return changes
    }

    /** A snapshot of this Activity's current **Protected Metrics**. */
    fun protectedMetrics(): ProtectedMetrics =
        ProtectedMetrics(
            startedAt = startedAt,
            distance = distance,
            timeTakenSeconds = timeTakenSeconds,
            averagePaceKmh = averagePaceKmh,
            averageHeartRate = averageHeartRate,
            calories = calories,
        )

    /** Replaces this Activity's **Protected Metrics**, returning each changed one in [ProtectedMetricValues.ALL] order. */
    fun applyProtectedMetricChanges(metrics: ProtectedMetrics): List<FieldChange> {
        val current = protectedMetrics()
        val changes =
            listOfNotNull(
                changed(current.startedAt, metrics.startedAt, FieldChange::StartedAt),
                changed(current.distance, metrics.distance, FieldChange::Distance),
                changed(current.timeTakenSeconds, metrics.timeTakenSeconds, FieldChange::TimeTaken),
                changed(current.averagePaceKmh, metrics.averagePaceKmh, FieldChange::AveragePace),
                changed(current.averageHeartRate, metrics.averageHeartRate, FieldChange::AverageHeartRate),
                changed(current.calories, metrics.calories, FieldChange::Calories),
            )
        startedAt = metrics.startedAt
        distance = metrics.distance
        timeTakenSeconds = metrics.timeTakenSeconds
        averagePaceKmh = metrics.averagePaceKmh
        averageHeartRate = metrics.averageHeartRate
        calories = metrics.calories
        return changes
    }

    private fun <T> changed(
        old: T,
        new: T,
        change: (T, T) -> FieldChange,
    ): FieldChange? = if (old != new) change(old, new) else null
}

/**
 * Values for each **Protected Metric**, any of which may be absent (as in a request body before it is
 * validated). This is the single definition of the Protected Metrics: [ALL] derives their names.
 */
interface ProtectedMetricValues {
    val startedAt: Instant?
    val distance: Double?
    val timeTakenSeconds: Int?
    val averagePaceKmh: Double?
    val averageHeartRate: Int?
    val calories: Int?

    companion object {
        /** Every **Protected Metric**, in display order; each property's `name` is the metric's field name. */
        val ALL: List<KProperty1<ProtectedMetricValues, Any?>> =
            listOf(
                ProtectedMetricValues::startedAt,
                ProtectedMetricValues::distance,
                ProtectedMetricValues::timeTakenSeconds,
                ProtectedMetricValues::averagePaceKmh,
                ProtectedMetricValues::averageHeartRate,
                ProtectedMetricValues::calories,
            )
    }
}

/** The **Protected Metrics** of an **Activity**: summary measurements that become immutable once protected. */
data class ProtectedMetrics(
    override val startedAt: Instant,
    override val distance: Double,
    override val timeTakenSeconds: Int,
    override val averagePaceKmh: Double?,
    override val averageHeartRate: Int?,
    override val calories: Int?,
) : ProtectedMetricValues
