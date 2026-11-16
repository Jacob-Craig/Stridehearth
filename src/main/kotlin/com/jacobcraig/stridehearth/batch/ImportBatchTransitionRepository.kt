package com.jacobcraig.stridehearth.batch

import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant

/** What [ImportBatchLifecycle.claim] needs to know about an item and its batch. */
interface ItemClaimView {
    fun getItemStatus(): ImportBatchItemStatus

    fun getBatchStatus(): ImportBatchStatus

    fun getOwnerId(): Long

    fun getStagedFilePath(): String
}

/**
 * The status-writing queries of the **Import Batch** lifecycle. Only [ImportBatchLifecycle] injects this
 * repository: every write is a compare-and-set whose affected-row count tells the module whether it won,
 * and every status value is a parameter taken from the named sets on the status enums.
 */
interface ImportBatchTransitionRepository : Repository<ImportBatch, Long> {
    @Query(
        """
        SELECT i.status AS itemStatus, b.status AS batchStatus, b.user.id AS ownerId, i.stagedFilePath AS stagedFilePath
        FROM ImportBatchItem i JOIN i.batch b
        WHERE i.id = :itemId
    """,
    )
    fun findClaimView(
        @Param("itemId") itemId: Long,
    ): ItemClaimView?

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ImportBatchItem i SET i.status = :processing WHERE i.id = :itemId AND i.status = :pending")
    fun claimPending(
        @Param("itemId") itemId: Long,
        @Param("pending") pending: ImportBatchItemStatus,
        @Param("processing") processing: ImportBatchItemStatus,
    ): Int

    @Query(
        """
        SELECT new com.jacobcraig.stridehearth.batch.RedispatchTicket(i.batch.id, i.id, i.stagedFilePath)
        FROM ImportBatchItem i
        WHERE i.status = :pending AND i.batch.status IN :active AND i.lastReconciledAt < :cutoff
    """,
    )
    fun findStalled(
        @Param("cutoff") cutoff: Instant,
        @Param("pending") pending: ImportBatchItemStatus,
        @Param("active") active: Collection<ImportBatchStatus>,
    ): List<RedispatchTicket>

    /** Stamps a stalled item as redispatched; 0 rows when another reconciler pass already took it. */
    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE ImportBatchItem i
        SET i.lastReconciledAt = :now
        WHERE i.id = :itemId AND i.status = :pending AND i.lastReconciledAt < :cutoff
    """,
    )
    fun markRedispatched(
        @Param("itemId") itemId: Long,
        @Param("now") now: Instant,
        @Param("cutoff") cutoff: Instant,
        @Param("pending") pending: ImportBatchItemStatus,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE ImportBatchItem i
        SET i.status = :status, i.upgrades = :upgrades, i.processedAt = :now
        WHERE i.id = :itemId AND i.status = :processing
    """,
    )
    fun recordItem(
        @Param("itemId") itemId: Long,
        @Param("status") status: ImportBatchItemStatus,
        @Param("upgrades") upgrades: String?,
        @Param("now") now: Instant,
        @Param("processing") processing: ImportBatchItemStatus,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE ImportBatchItem i
        SET i.status = :failed, i.errorMessage = :reason, i.processedAt = :now
        WHERE i.id = :itemId AND i.status IN :inFlight
    """,
    )
    fun failItem(
        @Param("itemId") itemId: Long,
        @Param("reason") reason: String?,
        @Param("now") now: Instant,
        @Param("failed") failed: ImportBatchItemStatus,
        @Param("inFlight") inFlight: Collection<ImportBatchItemStatus>,
    ): Int

    /** Adds one recorded item to its batch's counters, unless the batch was cancelled or interrupted. */
    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE ImportBatch b
        SET b.processedFiles = b.processedFiles + 1,
            b.successfulFiles = b.successfulFiles + :successful,
            b.upgradedFiles = b.upgradedFiles + :upgraded,
            b.duplicateFiles = b.duplicateFiles + :duplicate,
            b.failedFiles = b.failedFiles + :failed
        WHERE b.id = (SELECT i.batch.id FROM ImportBatchItem i WHERE i.id = :itemId)
          AND b.status IN :active
    """,
    )
    fun countItem(
        @Param("itemId") itemId: Long,
        @Param("successful") successful: Int,
        @Param("upgraded") upgraded: Int,
        @Param("duplicate") duplicate: Int,
        @Param("failed") failed: Int,
        @Param("active") active: Collection<ImportBatchStatus>,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        value = """
        INSERT INTO import_batch_item_activity (batch_item_id, activity_id)
        SELECT :itemId, a.id FROM activity a WHERE a.id IN (:activityIds)
        ON CONFLICT DO NOTHING
    """,
        nativeQuery = true,
    )
    fun linkActivities(
        @Param("itemId") itemId: Long,
        @Param("activityIds") activityIds: Collection<Long>,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE ImportBatch b
        SET b.status = :cancelled, b.completedAt = :now
        WHERE b.id = :batchId AND b.user.id = :userId AND b.status IN :active
    """,
    )
    fun cancel(
        @Param("batchId") batchId: Long,
        @Param("userId") userId: Long,
        @Param("now") now: Instant,
        @Param("cancelled") cancelled: ImportBatchStatus,
        @Param("active") active: Collection<ImportBatchStatus>,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE ImportBatch b
        SET b.status = :completed, b.completedAt = :now
        WHERE b.id = :batchId
          AND b.status IN :active
          AND NOT EXISTS (
            SELECT i.id FROM ImportBatchItem i WHERE i.batch.id = :batchId AND i.status IN :inFlight
          )
    """,
    )
    fun completeIfSettled(
        @Param("batchId") batchId: Long,
        @Param("now") now: Instant,
        @Param("completed") completed: ImportBatchStatus,
        @Param("active") active: Collection<ImportBatchStatus>,
        @Param("inFlight") inFlight: Collection<ImportBatchItemStatus>,
    ): Int

    @Modifying(flushAutomatically = true)
    @Query(
        """
        UPDATE ImportBatch b
        SET b.status = :interrupted, b.errorMessage = :message, b.completedAt = :now
        WHERE b.status IN :active
    """,
    )
    fun interruptActive(
        @Param("message") message: String,
        @Param("now") now: Instant,
        @Param("interrupted") interrupted: ImportBatchStatus,
        @Param("active") active: Collection<ImportBatchStatus>,
    ): Int
}
