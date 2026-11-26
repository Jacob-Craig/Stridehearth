package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.ActivityAnalysis
import com.jacobcraig.stridehearth.analytics.ThresholdDependentMetrics
import com.jacobcraig.stridehearth.analytics.TrainingLoadResult
import com.jacobcraig.stridehearth.analytics.TrainingLoadUnavailableReason
import com.jacobcraig.stridehearth.analytics.Vo2maxResult
import com.jacobcraig.stridehearth.analytics.Vo2maxUnavailableReason
import com.jacobcraig.stridehearth.athlete.AthleteAnalyticsStateRepository
import com.jacobcraig.stridehearth.athlete.AthleteRollups
import com.jacobcraig.stridehearth.athlete.AthleteThresholdRepository
import com.jacobcraig.stridehearth.athlete.ThresholdHistory
import com.jacobcraig.stridehearth.athlete.ThresholdsOn
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId

/**
 * The version of the derived-data algorithms (Telemetry Cleaning, Grade-Adjusted Pace, the Run metrics and whatever
 * later analytics [ActivityAnalysis] adds). Raise it whenever any of them would compute different values from the same Activity
 * Details: the recompute worker then **Recomputes** every Activity whose row is of an older version (ADR-0014).
 * Separate from the Parser Version.
 *
 * - 1: Grade-Adjusted Pace (#32).
 * - 2: Moving Time, Normalized Graded Pace, Efficiency Factor and Aerobic Decoupling of Runs (#33).
 * - 3: Grade-Adjusted Pace measures grade over a 180 m baseline between 60 m altitude anchors (#51).
 * - 4: Peak Efforts of Runs by Benchmark Duration, over GAP speed and power (#38).
 * - 5: Training Load of every Activity (#35).
 * - 6: Activity conditions (#43): the device and Ambient Temperature and the Mean Altitude of every Activity.
 * - 7: VO2max Estimate of Runs (#42).
 */
const val DERIVED_DATA_VERSION: Short = 7

/**
 * The derived-data module (ADR-0014): the only writer of `activity_derived_metrics`, which holds one row per
 * **Activity** of every sport, computed in one pure pass ([ActivityAnalysis]) from its **Cleaned Activity
 * Details** and stamped with [DERIVED_DATA_VERSION], and of `activity_peak_effort`, a Run's **Peak Efforts** from
 * the same pass. It also marks the athlete's rollups ([AthleteRollups]).
 *
 * Caller contract, as for [BestEffortRefresh]:
 * - Call it in the caller's transaction whenever an input changes: an Activity is created (with or without Activity
 *   Details, MANUAL included), its stored details change (an Upgrade), its sport or `startedAt` is edited, a manual
 *   Activity's Protected Metrics are edited (its distance feeds Marathon Shape), or it is deleted. Title-only edits do
 *   not need it.
 * - Call it directly after [BestEffortRefresh.refresh] and before heatmap and Activity History writes: it flushes
 *   the caller's pending change, and its write key-share locks the Activity's row, both of which ADR-0006 orders
 *   after the Personal Record lock.
 * - `details`, if passed, must be the Activity Details just stored; otherwise the stored ones are read.
 * - The **Training Load** and a Run's **VO2max Estimate** are computed against the **Thresholds** in force on the
 *   Activity's athlete-local date (in the **Athlete Time Zone**). When the Thresholds or the time zone change,
 *   [refreshThresholdDependent] recomputes only them.
 * - A deleted Activity's rows go by `ON DELETE CASCADE`; the refresh only marks its athlete's rollups.
 * - It is idempotent and writes no **Activity History Entry**. Its only lock of its own is the shared side of each
 *   athlete's scoring lock, taken before it reads their Thresholds and held to commit, so a Threshold or time-zone
 *   change waits for it and the recompute that change requests sees this transaction's Activities (ADR-0014).
 */
@Component
class DerivedDataRefresh(
    private val entityManager: EntityManager,
    private val activityDetailsService: ActivityDetailsService,
    private val derivedMetricsRepository: ActivityDerivedMetricsRepository,
    private val peakEffortRepository: ActivityPeakEffortRepository,
    private val athleteRollups: AthleteRollups,
    private val thresholdRepository: AthleteThresholdRepository,
    private val analyticsStateRepository: AthleteAnalyticsStateRepository,
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
            recompute(activity, details, Thresholds())
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
        val thresholds = Thresholds()
        for (activity in activities) {
            if (activity.id in existing) recompute(activity, details = null, thresholds)
            markRollups(activity, previousStartedAt = null, previousSport = previousSports[activity.id])
        }
    }

    /**
     * Recomputes only the Threshold-dependent derived data ([ThresholdDependentMetrics]: the **Training Load** and a
     * Run's **VO2max Estimate**) of each of [activities] that still exists and has a derived-data row, from its stored
     * Activity Details and the Thresholds now in force on its date, and marks its athlete's daily rollups. Used when
     * the athlete's Thresholds or time zone change; returns how many rows it replaced. An Activity without
     * a row, or with a row of an older version, is left to the version Backfill or Recompute, which computes everything.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun refreshThresholdDependent(activities: Collection<Activity>): Int {
        if (activities.isEmpty()) return 0
        entityManager.flush()
        val existing = derivedMetricsRepository.findExistingActivityIds(activities.map { checkNotNull(it.id) })
        val thresholds = Thresholds()
        var replaced = 0
        for (activity in activities) {
            if (activity.id !in existing) continue
            val metrics = analyse(activity, details = null, thresholds)?.thresholdDependent ?: noActivityDetails(activity)
            if (derivedMetricsRepository.updateThresholdDependent(activity.id!!, DERIVED_DATA_VERSION, metrics)) replaced++
            athleteRollups.markDailyLoad(checkNotNull(activity.user.id), activity.startedAt)
        }
        return replaced
    }

    private fun recompute(
        activity: Activity,
        details: FitTelemetry?,
        thresholds: Thresholds,
    ) {
        val activityId = activity.id!!
        val analysis = analyse(activity, details, thresholds)
        derivedMetricsRepository.upsert(
            activityId,
            DERIVED_DATA_VERSION,
            analysis?.gapSpeed,
            analysis?.runMetrics,
            analysis?.thresholdDependent ?: noActivityDetails(activity),
            analysis?.conditions,
        )
        peakEffortRepository.replace(activityId, analysis?.peakEfforts.orEmpty())
    }

    /** [activity]'s analysis from [details] or its stored Activity Details, or null when it has none. */
    private fun analyse(
        activity: Activity,
        details: FitTelemetry?,
        thresholds: Thresholds,
    ): ActivityAnalysis? {
        val source = details ?: activityDetailsService.findAnalyticsSeries(activity.id!!) ?: return null
        return ActivityAnalysis.analyse(source, activity.sport, thresholds.on(activity))
    }

    private val noActivityDetailsVo2max = Vo2maxResult.Unavailable(Vo2maxUnavailableReason.NO_ACTIVITY_DETAILS)

    /**
     * An Activity without Activity Details: no Training Load and, for a Run, no VO2max Estimate, each with that reason.
     * Other sports never have a VO2max Estimate, so they get no reason for it.
     */
    private fun noActivityDetails(activity: Activity): ThresholdDependentMetrics =
        ThresholdDependentMetrics(
            TrainingLoadResult.Unavailable(TrainingLoadUnavailableReason.NO_ACTIVITY_DETAILS),
            vo2max = noActivityDetailsVo2max.takeIf { activity.sport == ActivitySport.RUN },
        )

    /**
     * The Thresholds in force on each Activity's athlete-local date, reading each athlete's history and zone once per
     * call. Both are read under the athlete's shared scoring lock
     * ([AthleteAnalyticsStateRepository.lockScoringInputs]), so a concurrent Threshold or zone change either is read
     * here or requests a recompute that covers this transaction's Activities.
     */
    private inner class Thresholds {
        private val inputs = mutableMapOf<Long, Pair<ZoneId, ThresholdHistory>>()

        fun on(activity: Activity): ThresholdsOn {
            val userId = checkNotNull(activity.user.id)
            val (zone, history) =
                inputs.getOrPut(userId) {
                    analyticsStateRepository.lockScoringInputs(userId) to ThresholdHistory(thresholdRepository.findAll(userId))
                }
            return history.asOf(activity.startedAt.atZone(zone).toLocalDate())
        }
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
