package com.jacobcraig.stridehearth.athlete

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Turns a change to an athlete's **Thresholds** or **Athlete Time Zone** into a durable request to recompute their
 * **Training Load**, which [DerivedDataRecomputeWorker] serves in the background (ADR-0014). A plain listener, so the
 * request commits or rolls back with the change itself.
 */
@Component
class RecomputeRequestListener(
    private val stateRepository: AthleteAnalyticsStateRepository,
) {
    /** Every Threshold kind feeds some Training Load Method, so any change recomputes from its affected date. */
    @EventListener
    fun onThresholdsChanged(event: ThresholdsChanged) = stateRepository.requestRecompute(event.userId, event.affectedFrom)

    /** A new zone can move any Activity to another local date, and so to other Thresholds. */
    @EventListener
    fun onTimeZoneChanged(event: AthleteTimeZoneChanged) = stateRepository.requestRecompute(event.userId, from = null)
}
