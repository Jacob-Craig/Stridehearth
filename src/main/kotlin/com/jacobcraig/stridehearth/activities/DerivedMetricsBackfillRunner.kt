package com.jacobcraig.stridehearth.activities

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Runs the derived-metrics **Backfill** ([DerivedMetricsBackfillService.backfillAll]) once at startup, on a
 * background thread so the Server serves requests meanwhile. It is how Activities imported before Grade-Adjusted
 * Pace existed, or computed by an older algorithm version, get their derived metrics without anyone asking.
 *
 * Switched off with `stridehearth.derived-metrics.backfill-on-startup=false`. A failure is logged; the next
 * startup resumes where it stopped.
 */
@Component
class DerivedMetricsBackfillRunner(
    private val backfillService: DerivedMetricsBackfillService,
    @param:Value("\${stridehearth.derived-metrics.backfill-on-startup:true}") private val enabled: Boolean,
) {
    private val logger = LoggerFactory.getLogger(DerivedMetricsBackfillRunner::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        start()
    }

    /** Starts the backfill thread and returns it, or null when the backfill is switched off. */
    fun start(): Thread? {
        if (!enabled) return null
        return Thread(::backfill, "derived-metrics-backfill").apply {
            isDaemon = true
            start()
        }
    }

    private fun backfill() {
        try {
            val result = backfillService.backfillAll()
            logger.info("Derived metrics backfill: computed {} Activities", result.activitiesProcessed)
        } catch (e: Exception) {
            logger.error("Derived metrics backfill failed; it resumes at the next startup", e)
        }
    }
}
