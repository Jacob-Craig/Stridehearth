package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.GradeAdjustedPace
import com.jacobcraig.stridehearth.analytics.TelemetryCleaner
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The version of the derived-metrics algorithms (Telemetry Cleaning and Grade-Adjusted Pace). Raise it whenever
 * either would compute different values from the same Activity Details: the Backfill then recomputes every row
 * stored at an older version (ADR-0013). Separate from the Parser Version.
 */
const val DERIVED_METRICS_VERSION: Short = 1

/**
 * The derived-metrics module: the only writer of `activity_derived_metrics`, an **Activity**'s derived data
 * (its **Grade-Adjusted Pace** stream), computed from its **Cleaned Activity Details**.
 *
 * Caller contract, as for [BestEffortRefresh]:
 * - Call [refresh] in the caller's transaction after the change, when an Activity is created with Activity
 *   Details, its stored details changed (Upgrade or Backfill), or its sport changed. Title and metadata edits do
 *   not need it, and neither does a delete: the row goes with the Activity by `ON DELETE CASCADE`.
 * - Call it after [BestEffortRefresh.refresh]: it flushes the caller's pending change and its write key-share
 *   locks the Activity's row, both of which ADR-0006 orders after the Personal Record lock.
 * - `details`, if passed, must be the Activity Details just stored.
 * - It is idempotent, takes no lock of its own, and writes no **Activity History Entry**.
 */
@Component
class DerivedMetricsRefresh(
    private val entityManager: EntityManager,
    private val activityDetailsService: ActivityDetailsService,
    private val derivedMetricsRepository: ActivityDerivedMetricsRepository,
) {
    /**
     * Recomputes [activity]'s derived metrics from [details] (or its stored Activity Details) and stores them at
     * [DERIVED_METRICS_VERSION], or removes them when its sport carries none or it has no Activity Details.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun refresh(
        activity: Activity,
        details: FitTelemetry? = null,
    ) {
        entityManager.flush()
        val activityId = checkNotNull(activity.id) { "Only a saved Activity has derived metrics" }
        val carriesGap = activity.sport in GradeAdjustedPace.GAP_SPORTS
        val source = if (carriesGap) details ?: activityDetailsService.findCleaningSeries(activityId) else null
        if (source == null) {
            derivedMetricsRepository.deleteByActivityId(activityId)
        } else {
            derivedMetricsRepository.upsert(activityId, DERIVED_METRICS_VERSION, gapSpeed(activity, source))
        }
    }

    /** [refresh] for each of [activities]. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun refresh(activities: Collection<Activity>) {
        activities.forEach { refresh(it) }
    }

    /**
     * Computes the derived metrics of each of [activities] from its stored Activity Details for a **Backfill**,
     * storing them only where nothing is stored or what is stored is of an older version, and only while the
     * Activity's sport still carries them. Returns how many Activities it wrote.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun backfill(activities: Collection<Activity>): Int =
        activities.count { activity ->
            val activityId = checkNotNull(activity.id)
            val details = activityDetailsService.findCleaningSeries(activityId)
            activity.sport in GradeAdjustedPace.GAP_SPORTS &&
                details != null &&
                derivedMetricsRepository.upsertIfOlderVersion(activityId, DERIVED_METRICS_VERSION, gapSpeed(activity, details))
        }

    private fun gapSpeed(
        activity: Activity,
        details: FitTelemetry,
    ): Array<Float?>? = GradeAdjustedPace.gapSpeed(TelemetryCleaner.clean(details, activity.sport))
}
