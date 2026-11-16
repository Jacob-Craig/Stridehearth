package com.jacobcraig.stridehearth.batch

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/** The running counters of an **Import Batch**, read without hydrating the entity (lightweight polling, ADR-0001). */
interface ImportBatchSummaryProjection {
    fun getId(): Long

    fun getStatus(): ImportBatchStatus

    fun getTotalFiles(): Int

    fun getProcessedFiles(): Int

    fun getSuccessfulFiles(): Int

    fun getDuplicateFiles(): Int

    fun getUpgradedFiles(): Int

    fun getFailedFiles(): Int

    fun getErrorMessage(): String?

    fun getCreatedAt(): Instant

    fun getCompletedAt(): Instant?
}

/** The message an **Import Batch Item** carries into its batch's summary: a failure or an upgrade. */
interface ImportBatchItemMessageProjection {
    fun getBatchId(): Long

    fun getFilename(): String

    fun getStatus(): ImportBatchItemStatus

    fun getErrorMessage(): String?

    /** The typed upgrades, as stored jsonb (see [ImportBatchItem.upgrades]). */
    fun getUpgrades(): String?

    /** The legacy upgrade text of an item recorded before typed upgrades (see [ImportBatchItem.upgradeMessage]). */
    fun getUpgradeMessage(): String?
}

/** The columns of [ImportBatchSummaryProjection], selected once for every summary query. */
private const val SELECT_BATCH_SUMMARY =
    """
    SELECT b.id AS id,
           b.status AS status,
           b.totalFiles AS totalFiles,
           b.processedFiles AS processedFiles,
           b.successfulFiles AS successfulFiles,
           b.duplicateFiles AS duplicateFiles,
           b.upgradedFiles AS upgradedFiles,
           b.failedFiles AS failedFiles,
           b.errorMessage AS errorMessage,
           b.createdAt AS createdAt,
           b.completedAt AS completedAt
    FROM ImportBatch b
    """

interface ImportBatchRepository : JpaRepository<ImportBatch, Long> {
    @Query("$SELECT_BATCH_SUMMARY WHERE b.id = :batchId AND b.user.id = :userId")
    fun findSummaryByIdAndUserId(
        @Param("batchId") batchId: Long,
        @Param("userId") userId: Long,
    ): ImportBatchSummaryProjection?

    @Query("$SELECT_BATCH_SUMMARY WHERE b.user.id = :userId ORDER BY b.createdAt DESC")
    fun findSummariesByUserId(
        @Param("userId") userId: Long,
    ): List<ImportBatchSummaryProjection>
}

interface ImportBatchItemRepository : JpaRepository<ImportBatchItem, Long> {
    @EntityGraph(attributePaths = ["activities"])
    fun findByBatchId(batchId: Long): List<ImportBatchItem>

    /** The messages of the items of [batchIds] whose status is in [statuses], in item order. */
    @Query(
        """
        SELECT i.batch.id AS batchId,
               i.originalFilename AS filename,
               i.status AS status,
               i.errorMessage AS errorMessage,
               i.upgrades AS upgrades,
               i.upgradeMessage AS upgradeMessage
        FROM ImportBatchItem i
        WHERE i.batch.id IN :batchIds AND i.status IN :statuses
        ORDER BY i.id
    """,
    )
    fun findItemMessages(
        @Param("batchIds") batchIds: Collection<Long>,
        @Param("statuses") statuses: Collection<ImportBatchItemStatus>,
    ): List<ImportBatchItemMessageProjection>
}
