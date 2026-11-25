package com.jacobcraig.stridehearth.athlete

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Runs [DerivedDataRecomputeWorker.drain] periodically (ADR-0014), so derived data catches up in the background
 * after an upgrade raises its version, and a run that failed is retried at the next one.
 *
 * Each run is bounded to [pagesPerRun] pages, because it shares Spring's single scheduler thread with the import
 * batch reconciler and reclaimer; a long catch-up continues over several runs. Switched off with
 * `stridehearth.analytics.recompute.scheduled=false`, as the tests do, which call the worker directly.
 */
@Component
@ConditionalOnProperty(name = ["stridehearth.analytics.recompute.scheduled"], havingValue = "true", matchIfMissing = true)
class DerivedDataRecomputeSchedule(
    private val worker: DerivedDataRecomputeWorker,
    @param:Value("\${stridehearth.analytics.recompute-pages-per-run:10}") private val pagesPerRun: Int,
) {
    private val logger = LoggerFactory.getLogger(DerivedDataRecomputeSchedule::class.java)

    @Scheduled(
        fixedDelayString = "\${stridehearth.analytics.recompute-interval-ms:15000}",
        initialDelayString = "\${stridehearth.analytics.recompute-initial-delay-ms:15000}",
    )
    fun run() {
        try {
            val result = worker.drain(maxPages = pagesPerRun)
            if (result != RecomputeResult(0, 0)) {
                logger.info(
                    "Derived data: recomputed {} Activities and {} Training Loads, rebuilt {} athletes' rollups, skipped {} Activities " +
                        "and {} athletes' rollups that failed",
                    result.activitiesRecomputed,
                    result.thresholdDependentRecomputed,
                    result.rollupsRebuilt,
                    result.activitiesFailed,
                    result.rollupsFailed,
                )
            }
        } catch (e: Exception) {
            logger.error("Derived data recompute failed; it resumes at the next run", e)
        }
    }
}
