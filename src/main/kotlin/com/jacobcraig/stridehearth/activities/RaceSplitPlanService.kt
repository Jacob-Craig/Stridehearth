package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.GradeAdjustedPace
import com.jacobcraig.stridehearth.analytics.RaceSplitPlan
import com.jacobcraig.stridehearth.analytics.RaceSplitPlanner
import com.jacobcraig.stridehearth.analytics.SplitUnit
import com.jacobcraig.stridehearth.analytics.TelemetryCleaner
import com.jacobcraig.stridehearth.api.RequestValidationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Lays out a **Race Split Plan** over one of the athlete's own Activities (#46). The plan depends on the Goal Time the
 * athlete enters, so it is computed on read from the **Cleaned Activity Details** and never stored; the stored
 * Activity Details are only read.
 */
@Service
class RaceSplitPlanService(
    private val activityRepository: ActivityRepository,
    private val activityDetailsService: ActivityDetailsService,
) {
    /**
     * The plan for running [activityId]'s route in [goalSeconds].
     *
     * @throws NoSuchElementException unless [userId] owns the Activity.
     * @throws RequestValidationException when it is not a Run, Walk or Hike, or has no GPS route with distance.
     */
    @Transactional(readOnly = true)
    fun plan(
        activityId: Long,
        userId: Long,
        goalSeconds: Double,
        unit: SplitUnit,
        biasPct: Double,
    ): RaceSplitPlan {
        val activity =
            activityRepository.findByIdAndUserId(activityId, userId)
                ?: throw NoSuchElementException("No Activity $activityId")
        if (activity.sport !in GradeAdjustedPace.GAP_SPORTS) {
            throw RequestValidationException(mapOf(ACTIVITY_ID to "Only a Run, Walk or Hike has a Race Split Plan"))
        }
        val details = activityDetailsService.findAnalyticsSeries(activityId) ?: throw noRoute()
        val cleaned = TelemetryCleaner.clean(details, activity.sport)
        return RaceSplitPlanner.plan(cleaned, GradeAdjustedPace.costRatios(cleaned), goalSeconds, unit.meters, biasPct)
            ?: throw noRoute()
    }

    private fun noRoute() = RequestValidationException(mapOf(ACTIVITY_ID to "The Activity has no GPS route with distance"))

    private companion object {
        const val ACTIVITY_ID = "activityId"
    }
}
