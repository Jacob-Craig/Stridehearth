package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.analytics.WorkoutTypeClassifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Reclassifies Runs' **Workout Type** from their stored evidence when the Runs of the 28 days before them changed
 * (ADR-0014): the recompute worker's serving of a `workout_type_request`. It never analyses an Activity again, so it
 * is cheap, and it writes only a row whose type actually changes.
 */
@Component
class WorkoutTypeReclassifier(
    private val workoutTypeRepository: WorkoutTypeRepository,
) {
    /**
     * Reclassifies each of [activityIds] that is a Run with evidence; returns how many it changed. The caller holds
     * their row locks (the worker's page does), so the Moving Times it reads were committed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun reclassify(activityIds: Collection<Long>): Int {
        val types =
            workoutTypeRepository.findEvidence(activityIds).associate { stored ->
                val (median, runs) = workoutTypeRepository.findMedianMovingSeconds(stored.userId, stored.startedAt)
                stored.activityId to WorkoutTypeClassifier.classify(stored.evidence, stored.movingSeconds, median, runs)
            }
        return workoutTypeRepository.setTypes(types)
    }
}
