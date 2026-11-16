package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.activities.history.ActivityChangeDTO
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/** Acknowledges an accepted **Import Batch** submission, before any file has been ingested. */
data class ImportBatchReceiptDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val batchId: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val status: ImportBatchStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val totalFiles: Int,
)

/** The failure recorded against one **Import Batch Item**. */
data class ImportBatchItemErrorDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val filename: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val errorMessage: String,
)

/**
 * An **Import Batch Item** whose re-ingestion upgraded or backfilled already-imported **Activities**: one
 * `UPGRADED` change per upgraded Activity, or one `LEGACY` change for an item recorded before typed upgrades.
 * Empty when the item recorded neither; the client then shows a generic upgrade.
 */
data class ImportBatchItemUpgradeDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val filename: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val changes: List<ActivityChangeDTO>,
)

/**
 * Lightweight progress projection of an **Import Batch**, polled every second by the SPA and listed in
 * the import history. Exposes canonical primitives only; durations and dates are formatted by the client.
 */
data class ImportBatchSummaryDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val status: ImportBatchStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val totalFiles: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val processedFiles: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val successfulFiles: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val duplicateFiles: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val upgradedFiles: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val failedFiles: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["string", "null"])
    val errorMessage: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val itemErrors: List<ImportBatchItemErrorDTO>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val itemUpgrades: List<ImportBatchItemUpgradeDTO>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val createdAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = ["string", "null"], format = "date-time")
    val completedAt: Instant?,
)
