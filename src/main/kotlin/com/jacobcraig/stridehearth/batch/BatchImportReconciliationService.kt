package com.jacobcraig.stridehearth.batch

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/** Re-enqueues PENDING items whose stream message was lost between the database commit and `XADD`. */
@Service
class BatchImportReconciliationService(
    private val lifecycle: ImportBatchLifecycle,
    private val streamDispatcher: BatchImportStreamDispatcher,
    private val properties: BatchImportProperties,
) {
    private val logger = LoggerFactory.getLogger(BatchImportReconciliationService::class.java)

    @Scheduled(fixedDelayString = "\${stridehearth.import.reconciliation-interval-ms:10000}")
    fun reconcilePendingItems(): Int {
        var reEnqueuedCount = 0
        for (ticket in lifecycle.claimStalledForRedispatch(properties.reconciliationThreshold)) {
            try {
                streamDispatcher.dispatch(ticket.batchId, ticket.itemId, ticket.stagedFilePath)
                reEnqueuedCount++
                logger.info("Re-enqueued stalled pending item {} for batch {}", ticket.itemId, ticket.batchId)
            } catch (e: Exception) {
                logger.warn("Failed to re-enqueue item {}: {}", ticket.itemId, e.message)
            }
        }
        return reEnqueuedCount
    }
}
