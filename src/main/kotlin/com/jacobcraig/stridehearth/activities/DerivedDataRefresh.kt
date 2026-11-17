package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.ActivityAnalysis
import com.jacobcraig.stridehearth.athlete.AthleteRollups
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The version of the derived-data algorithms (Telemetry Cleaning, Grade-Adjusted Pace and whatever later analytics
 * [ActivityAnalysis] adds). Raise it whenever any of them would compute different values from the same Activity
 * Details: the recompute worker then **Recomputes** every Activity whose row is of an older version (ADR-0014).
 * Separate from the Parser Version.
 */
const val DERIVED_DATA_VERSION: Short = 1

/**
 * The derived-data module (ADR-0014): the only writer of `activity_derived_metrics`, which holds one row per
 * **Activity** of every sport, computed in one pure pass ([ActivityAnalysis]) from its **Cleaned Activity
 * Details** and stamped with [DERIVED_DATA_VERSION]. It also marks the athlete's rollups ([AthleteRollups]).
 *
 * Caller contract, as for [BestEffortRefresh]:
 * - Call it in the caller's transaction whenever an input changes: an Activity is created (with or without Activity
 *   Details, MANUAL included), its stored details change (an Upgrade), its sport or `startedAt` is edited, or it is
 *   deleted. Title-only edits do not need it.
 * - Call it directly after [BestEffortRefresh.refresh] and before heatmap and Activity History writes: it flushes
 *   the caller's pending change, and its write key-share locks the Activity's row, both of which ADR-0006 orders
 *   after the Personal Record lock.
 * - `details`, if passed, must be the Activity Details just stored; otherwise the stored ones are read.
 * - A deleted Activity's row goes by `ON DELETE CASCADE`; the refresh only marks its athlete's rollups.
 * - It is idempotent, takes no lock of its own, and writes no **Activity History Entry**.
 */
@Component
class DerivedDataRefresh(
    private val entityManager: EntityManager,
    private val activityDetailsService: ActivityDetailsService,
    private val derivedMetricsRepository: ActivityDerivedMetricsRepository,
    private val athleteRollups: AthleteRollups,
) {
    /**
     * Recomputes [activity]'s derived data from [details] (or its stored Activity Details) if it still exists, and
     * marks its athlete's rollups. [previousStartedAt] and [previousSport] are its values before an edit, if the
     * edit changed them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun refresh(
        activity: Activity,
        details: FitTelemetry? = null,
        previousStartedAt: Instant? = null,
        previousSport: ActivitySport? = null,
    ) {
        entityManager.flush()
        val activityId = checkNotNull(activity.id) { "Only a saved Activity has derived data" }
        if (activityId in derivedMetricsRepository.findExistingActivityIds(listOf(activityId))) {
            recompute(activity, details)
        }
        markRollups(activity, previousStartedAt, previousSport)
    }

    /**
     * [refresh] for each of [activities] from their stored Activity Details. [previousSports] holds, by Activity id,
     * the sport before an edit that changed it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun refresh(
        activities: Collection<Activity>,
        previousSports: Map<Long, ActivitySport> = emptyMap(),
    ) {
        if (activities.isEmpty()) return
        entityManager.flush()
        val existing = derivedMetricsRepository.findExistingActivityIds(activities.map { checkNotNull(it.id) })
        for (activity in activities) {
            if (activity.id in existing) recompute(activity, details = null)
            markRollups(activity, previousStartedAt = null, previousSport = previousSports[activity.id])
        }
    }

    private fun recompute(
        activity: Activity,
        details: FitTelemetry?,
    ) {
        val activityId = activity.id!!
        val source = details ?: activityDetailsService.findAnalyticsSeries(activityId)
        val analysis = source?.let { ActivityAnalysis.analyse(it, activity.sport) }
        derivedMetricsRepository.upsert(activityId, DERIVED_DATA_VERSION, analysis?.gapSpeed)
    }

    /**
     * Marks, from the in-memory entity, the athlete's daily rollups at the Activity's start (and its previous start),
     * and their peaks when it is a Run or was one before the edit.
     */
    private fun markRollups(
        activity: Activity,
        previousStartedAt: Instant?,
        previousSport: ActivitySport?,
    ) {
        val userId = checkNotNull(activity.user.id)
        val moments = listOfNotNull(activity.startedAt, previousStartedAt)
        moments.forEach { athleteRollups.markDailyLoad(userId, it) }
        if (activity.sport == ActivitySport.RUN || previousSport == ActivitySport.RUN) {
            moments.forEach { athleteRollups.markPeaksChanged(userId, it) }
        }
    }
}
