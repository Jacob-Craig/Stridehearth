package com.jacobcraig.stridehearth.activities

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

data class DerivedMetricsBackfillResult(
    val activitiesProcessed: Int,
)

/**
 * Computes the derived metrics (the **Grade-Adjusted Pace** stream) of every athlete's **Activities** that lack
 * them, or hold them from an older [DERIVED_METRICS_VERSION]: a **Backfill** in pages, through
 * [DerivedMetricsRefresh.backfill].
 *
 * Each page runs and commits in its own transaction (as ADR-0006 has the Best Effort backfill do). A failing page
 * rolls back on its own and the failure is rethrown, leaving earlier pages committed; a rerun resumes, because the
 * candidates are exactly the Activities whose derived metrics are missing or out of date. Writes are guarded by
 * version, so a page never overwrites what a concurrent import or edit has just written, and it takes no lock.
 */
@Service
class DerivedMetricsBackfillService(
    private val entityManager: EntityManager,
    private val activityRepository: ActivityRepository,
    private val derivedMetricsRepository: ActivityDerivedMetricsRepository,
    private val derivedMetricsRefresh: DerivedMetricsRefresh,
    private val transactionTemplate: TransactionTemplate,
) {
    /**
     * Backfills every candidate Activity, [pageSize] per page. It opens one transaction per page itself, so it
     * must not be called inside a transaction.
     */
    @Transactional(propagation = Propagation.NEVER)
    fun backfillAll(pageSize: Int = 50): DerivedMetricsBackfillResult {
        val limit = pageSize.coerceAtLeast(1)
        var lastActivityId = 0L
        var processed = 0
        while (true) {
            // Only a committed page advances the scan: a failing page has rolled back and thrown.
            val page = transactionTemplate.execute { backfillPage(lastActivityId, limit) } ?: break
            lastActivityId = page.lastActivityId
            processed += page.written
        }
        return DerivedMetricsBackfillResult(activitiesProcessed = processed)
    }

    private data class Page(
        val lastActivityId: Long,
        val written: Int,
    )

    private fun backfillPage(
        afterActivityId: Long,
        limit: Int,
    ): Page? {
        val candidateIds = derivedMetricsRepository.findNextBackfillPage(afterActivityId, limit, DERIVED_METRICS_VERSION)
        if (candidateIds.isEmpty()) return null
        val written = derivedMetricsRefresh.backfill(activityRepository.findAllById(candidateIds))
        // Keep the persistence context from growing with every page of a long backfill.
        entityManager.clear()
        return Page(lastActivityId = candidateIds.last(), written = written)
    }
}
