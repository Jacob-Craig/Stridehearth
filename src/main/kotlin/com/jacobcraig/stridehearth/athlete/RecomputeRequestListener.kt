package com.jacobcraig.stridehearth.athlete

import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Turns a change to an athlete's **Thresholds** or **Athlete Time Zone** into a durable request to recompute their
 * threshold-dependent derived data, the **Training Load** and **VO2max Estimate** (and, for a time zone, a full rebuild
 * of their rollups), which [DerivedDataRecomputeWorker] serves in the background (ADR-0014). A plain listener, so the
 * request commits or rolls back with the change itself.
 */
@Component
class RecomputeRequestListener(
    private val stateRepository: AthleteAnalyticsStateRepository,
) {
    /**
     * Every Threshold kind feeds some Training Load Method, so any change recomputes from its affected date. Runs
     * before every other listener, so the exclusive scoring lock is taken before any of them touches a row a
     * before-commit rollup (holding the shared side) may write, such as a Threshold Proposal (ADR-0016).
     */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun onThresholdsChanged(event: ThresholdsChanged) = stateRepository.requestRecompute(event.userId, event.affectedFrom)

    /**
     * A new zone can move any Activity to another local date, and so to other Thresholds and another day of their
     * Daily Load: every Training Load is recomputed, and their rollups are marked stale for the worker to rebuild in
     * full (ADR-0016).
     */
    @EventListener
    fun onTimeZoneChanged(event: AthleteTimeZoneChanged) {
        stateRepository.requestRecompute(event.userId, from = null)
        stateRepository.markRollupsStale(event.userId)
    }
}
