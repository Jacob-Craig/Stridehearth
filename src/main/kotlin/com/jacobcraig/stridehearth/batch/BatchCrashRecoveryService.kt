package com.jacobcraig.stridehearth.batch

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class BatchCrashRecoveryService(
    private val lifecycle: ImportBatchLifecycle,
    private val batchStorageService: BatchStorage,
) {
    private val logger = LoggerFactory.getLogger(BatchCrashRecoveryService::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady(event: ApplicationReadyEvent? = null): Int = recoverInFlightBatches()

    fun recoverInFlightBatches(): Int {
        val count = lifecycle.interruptActive()
        if (count > 0) {
            logger.warn("Crash recovery: marked {} in-flight batch(es) as INTERRUPTED", count)
        }
        batchStorageService.purgeStorageDirectory()
        logger.info("Crash recovery: purged temporary batch staging storage")
        return count
    }
}
