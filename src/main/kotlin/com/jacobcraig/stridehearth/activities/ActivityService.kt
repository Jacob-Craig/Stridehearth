package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.FieldChange
import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.heatmap.HeatmapRouteRepository
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ActivityService(
    private val activityRepository: ActivityRepository,
    private val userRepository: UserRepository,
    private val activityLapRepository: ActivityLapRepository,
    private val activityHistory: ActivityHistory,
    private val activityBestEffortRepository: ActivityBestEffortRepository,
    private val activityDetailsRepository: ActivityDetailsRepository,
    private val bestEffortRefresh: BestEffortRefresh,
    private val derivedMetricsRefresh: DerivedMetricsRefresh,
    private val heatmapRouteRepository: HeatmapRouteRepository,
) {
    /** Logs a `MANUAL` **Activity** for [userId], recording its creation as an **Activity History Entry**. */
    @Transactional
    fun createManualActivity(
        userId: Long,
        title: String,
        sport: ActivitySport,
        metrics: ProtectedMetrics,
    ): ActivitySummaryDTO {
        val owner =
            userRepository
                .findById(userId)
                .orElseThrow { IllegalArgumentException("User $userId not found") }

        val saved =
            activityRepository.save(
                Activity(
                    title = title.trim(),
                    sport = sport,
                    startedAt = metrics.startedAt,
                    distance = metrics.distance,
                    timeTakenSeconds = metrics.timeTakenSeconds,
                    user = owner,
                    averagePaceKmh = metrics.averagePaceKmh,
                    averageHeartRate = metrics.averageHeartRate,
                    calories = metrics.calories,
                    source = ActivitySource.MANUAL,
                ),
            )
        activityHistory.record(saved, ActivityChange.Created.MANUAL)
        return saved.toSummaryDto(hasActivityDetails = false)
    }

    /**
     * The **Activity** with its **Laps** (in lap order), **Activity History Entries** (oldest first) and
     * **Best Efforts** (shortest **Benchmark Distance** first), or null if it doesn't exist for this user.
     */
    @Transactional(readOnly = true)
    fun getActivityDetail(
        id: Long,
        userId: Long,
    ): ActivityDetailDTO? {
        val activity = activityRepository.findByIdAndUserId(id, userId) ?: return null
        return activity.toDetailDto(
            hasActivityDetails = hasActivityDetails(id),
            laps = activityLapRepository.findByActivityIdOrderByLapNumberAsc(id),
            history = activityHistory.entriesFor(id),
            bestEfforts = activityBestEffortRepository.findByActivityId(id).sortedBy { it.benchmarkDistance.distanceMeters },
        )
    }

    private fun hasActivityDetails(activityId: Long): Boolean =
        activityId in activityDetailsRepository.findActivityIdsWithDetails(listOf(activityId))

    /** One page of the athlete's **Activities** matching [filter]. */
    fun getActivitySummaries(
        userId: Long,
        filter: ActivityFilter,
        pageable: Pageable,
    ): Page<ActivitySummaryDTO> {
        val page = activityRepository.findFiltered(userId, filter, pageable)
        val idsWithDetails = activityDetailsRepository.findActivityIdsWithDetails(page.content.mapNotNull { it.id })
        return page.map { it.toSummaryDto(hasActivityDetails = it.id in idsWithDetails) }
    }

    /**
     * Edits an **Activity** from the JSON API, enforcing **Protected Metrics** immutability: when the
     * Activity has protected metrics, supplying any of them is rejected; otherwise all required metrics
     * must be supplied. Returns null if the activity doesn't exist for this user.
     *
     * @throws RequestValidationException naming each offending field.
     */
    @Transactional
    fun updateActivity(
        id: Long,
        userId: Long,
        request: ActivityUpdateRequestDTO,
    ): ActivitySummaryDTO? {
        val existingActivity = activityRepository.findByIdAndUserId(id, userId) ?: return null
        val hasDetails = hasActivityDetails(id)
        val metrics =
            if (existingActivity.hasProtectedMetrics(hasDetails)) {
                val attempted = request.suppliedProtectedMetricFields()
                if (attempted.isNotEmpty()) {
                    throw RequestValidationException(
                        "Protected Metrics cannot be edited",
                        attempted.associateWith { "$it is a Protected Metric and cannot be edited" },
                    )
                }
                null
            } else {
                request.toProtectedMetrics()
            }
        applyEdit(existingActivity, request.title!!, request.sport!!, metrics)
        return existingActivity.toSummaryDto(hasDetails)
    }

    /**
     * Applies an edit (with [metrics] only when they are editable), recording history and, on a sport change,
     * refreshing Best Efforts and derived metrics and recomputing the Activity's heatmap route.
     */
    private fun applyEdit(
        activity: Activity,
        title: String,
        sport: ActivitySport,
        metrics: ProtectedMetrics?,
    ) {
        val oldSport = activity.sport
        val changes =
            activity.applyMetadataChanges(title, sport) + (metrics?.let { activity.applyProtectedMetricChanges(it) } ?: emptyList())

        activityRepository.save(activity)
        if (activity.sport != oldSport) {
            bestEffortRefresh.refresh(activity)
            derivedMetricsRefresh.refresh(activity)
            // After the refresh, which flushes the edit: a heatmap route insert key-share locks the Activity's row.
            heatmapRouteRepository.recomputeForSport(listOf(activity.id!!), activity.sport)
        }
        // After the refresh: the entry's insert runs at once and key-share locks the Activity's row, which must
        // come after the Personal Record lock (ADR-0006).
        if (changes.isNotEmpty()) {
            activityHistory.record(activity, ActivityChange.Edited(changes))
        }
    }

    /** Deletes the athlete's Activity [id] and refreshes its **Best Efforts**, returning false if it doesn't exist for them. */
    @Transactional
    fun deleteActivity(
        id: Long,
        userId: Long,
    ): Boolean {
        val existingActivity = activityRepository.findByIdAndUserId(id, userId) ?: return false
        activityRepository.delete(existingActivity)
        bestEffortRefresh.refresh(existingActivity)
        return true
    }

    /**
     * Applies [change] to the athlete's [activityIds], recording a `BATCH_EDITED` **Activity History Entry**
     * per changed Activity and refreshing the **Best Efforts** of every reclassified Activity in one call.
     *
     * All or nothing: returns null, changing nothing, unless every requested Activity exists and belongs to
     * [userId] (so a batch can neither touch nor probe another athlete's Activities).
     */
    @Transactional
    fun batchUpdateActivities(
        activityIds: Collection<Long>,
        userId: Long,
        change: BatchMetadataChange,
    ): BatchUpdateResult? {
        val ownedActivities = findAllOwnedActivities(activityIds, userId) ?: return null

        val edited = mutableListOf<Pair<Activity, List<FieldChange>>>()
        val reclassified = mutableListOf<Activity>()
        for (activity in ownedActivities) {
            val oldSport = activity.sport
            val changes = activity.applyMetadataChanges(change.title, change.sport)
            if (changes.isNotEmpty()) {
                activityRepository.save(activity)
                edited += activity to changes
                if (activity.sport != oldSport) reclassified += activity
            }
        }
        if (reclassified.isNotEmpty()) {
            bestEffortRefresh.refresh(reclassified)
            derivedMetricsRefresh.refresh(reclassified)
            // Every reclassified Activity now has the batch's sport; after the refresh, as for history below.
            heatmapRouteRepository.recomputeForSport(reclassified.map { it.id!! }, change.sport!!)
        }
        // After the refresh, as in applyEdit: each entry's insert key-share locks its Activity's row (ADR-0006).
        edited.forEach { (activity, changes) -> activityHistory.record(activity, ActivityChange.BatchEdited(changes)) }
        return BatchUpdateResult(matchedCount = ownedActivities.size, modifiedCount = edited.size)
    }

    /**
     * Deletes the athlete's [activityIds], refreshing their **Best Efforts** in one call, and returns how many
     * were deleted. All or nothing, like [batchUpdateActivities]: null, deleting nothing, unless every requested
     * Activity exists and belongs to [userId].
     */
    @Transactional
    fun batchDeleteActivities(
        activityIds: Collection<Long>,
        userId: Long,
    ): Int? {
        val ownedActivities = findAllOwnedActivities(activityIds, userId) ?: return null
        activityRepository.deleteAll(ownedActivities)
        bestEffortRefresh.refresh(ownedActivities)
        return ownedActivities.size
    }

    /** Every one of [activityIds] as the athlete's Activities, or null if any is missing or another athlete's. */
    private fun findAllOwnedActivities(
        activityIds: Collection<Long>,
        userId: Long,
    ): List<Activity>? {
        val requestedIds = activityIds.distinct()
        val owned = requestedIds.chunkedForIdLookup().flatMap { activityRepository.findByIdInAndUserId(it, userId) }
        return owned.takeIf { requestedIds.isNotEmpty() && it.size == requestedIds.size }
    }
}

/**
 * Outcome of a batch update: [matchedCount] Activities were selected (all the athlete's), and [modifiedCount]
 * of those actually changed (the rest already matched the target values).
 */
data class BatchUpdateResult(
    val matchedCount: Int,
    val modifiedCount: Int,
)
