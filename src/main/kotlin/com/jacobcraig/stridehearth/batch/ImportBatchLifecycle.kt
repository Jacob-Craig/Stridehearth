package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityUpgrade
import com.jacobcraig.stridehearth.activities.ImportOutcome
import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

/**
 * Thrown by [ImportBatchLifecycle.recordOutcome] when the item was already recorded or its batch was
 * cancelled or interrupted. It rolls back the caller's transaction, and with it the file's **Activities**.
 */
class ItemNoLongerRecordable(
    reason: String,
) : RuntimeException(reason)

/** Whether a delivery may process its **Import Batch Item**: see [ImportBatchLifecycle.claim]. */
sealed interface ItemClaim {
    /** The delivery owns the item and processes [stagedFilePath] for the athlete [ownerId]. */
    data class Claimed(
        val ownerId: Long,
        val stagedFilePath: String,
    ) : ItemClaim

    /** The delivery has nothing to process. */
    data class Skip(
        val reason: SkipReason,
    ) : ItemClaim

    enum class SkipReason {
        /** The item no longer exists. */
        ITEM_MISSING,

        /** Its batch was cancelled or interrupted; the item is a Stranded Import Batch Item. */
        STRANDED,

        /** Another delivery is processing it and that delivery's message is still live. */
        OWNED_ELSEWHERE,

        /** The item has already been recorded. */
        ALREADY_RECORDED,
    }
}

/** A stalled PENDING item the reconciler should enqueue again. */
data class RedispatchTicket(
    val batchId: Long,
    val itemId: Long,
    val stagedFilePath: String,
)

/**
 * The only writer of **Import Batch** and **Import Batch Item** status, counters and messages.
 *
 * Processing is at-least-once; recording is exactly-once. Every transition here is a compare-and-set in
 * Postgres, so it happens at most once however many workers race for it. Recording locks the item row
 * and then the batch row, always in that order.
 */
@Service
class ImportBatchLifecycle(
    private val transitions: ImportBatchTransitionRepository,
    private val batches: ImportBatchRepository,
    private val items: ImportBatchItemRepository,
    private val batchStorage: BatchStorage,
    private val activityHistory: ActivityHistory,
) {
    private val logger = LoggerFactory.getLogger(ImportBatchLifecycle::class.java)

    /**
     * Decides whether a delivery of the item's message may process it.
     *
     * A PENDING item is claimed by compare-and-set, so only one first delivery wins it. A PROCESSING item
     * is taken over only by a [redelivered] message (one reclaimed from a dead or slow consumer); a first
     * delivery of a PROCESSING item is a reconciler duplicate whose owner is still live, and is skipped.
     * Taking over is safe because recording is exactly-once.
     */
    @Transactional
    fun claim(
        itemId: Long,
        redelivered: Boolean,
    ): ItemClaim {
        val view = transitions.findClaimView(itemId) ?: return ItemClaim.Skip(ItemClaim.SkipReason.ITEM_MISSING)
        val claimed = ItemClaim.Claimed(view.getOwnerId(), view.getStagedFilePath())
        return when {
            view.getBatchStatus() !in ImportBatchStatus.ACTIVE -> ItemClaim.Skip(ItemClaim.SkipReason.STRANDED)
            view.getItemStatus() == ImportBatchItemStatus.PENDING ->
                if (transitions.claimPending(itemId, ImportBatchItemStatus.PENDING, ImportBatchItemStatus.PROCESSING) > 0) {
                    claimed
                } else {
                    ItemClaim.Skip(ItemClaim.SkipReason.OWNED_ELSEWHERE)
                }
            view.getItemStatus() == ImportBatchItemStatus.PROCESSING ->
                if (redelivered) claimed else ItemClaim.Skip(ItemClaim.SkipReason.OWNED_ELSEWHERE)
            else -> ItemClaim.Skip(ItemClaim.SkipReason.ALREADY_RECORDED)
        }
    }

    /**
     * Hands out the PENDING items of active batches that have waited longer than [threshold] since they
     * were enqueued or last redispatched, stamping each so that no other pass hands it out again within
     * the threshold.
     */
    @Transactional
    fun claimStalledForRedispatch(threshold: Duration): List<RedispatchTicket> {
        val now = Instant.now()
        val cutoff = now.minus(threshold)
        return transitions
            .findStalled(cutoff, ImportBatchItemStatus.PENDING, ImportBatchStatus.ACTIVE)
            .filter { transitions.markRedispatched(it.itemId, now, cutoff, ImportBatchItemStatus.PENDING) > 0 }
    }

    /**
     * Records a PROCESSING item's **Import Outcome** and counts it towards its batch, inside the import's
     * own transaction so that both commit with the file's Activities or not at all.
     *
     * @throws ItemNoLongerRecordable when the item is no longer PROCESSING (another worker recorded it)
     *   or its batch was cancelled or interrupted first (the item is now Stranded).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun recordOutcome(
        itemId: Long,
        outcome: ImportOutcome,
    ) {
        val recorded = RecordedOutcome.of(outcome)
        val now = Instant.now()
        val itemRecorded =
            transitions.recordItem(
                itemId,
                recorded.status,
                activityHistory.encodeItemUpgrades(recorded.upgrades),
                now,
                ImportBatchItemStatus.PROCESSING,
            ) > 0
        if (!itemRecorded) throw ItemNoLongerRecordable("item $itemId is no longer PROCESSING")
        if (!addToCounters(itemId, recorded.status)) throw ItemNoLongerRecordable("the batch of item $itemId was cancelled or interrupted")
        if (recorded.activities.isNotEmpty()) transitions.linkActivities(itemId, recorded.activities.map { it.id!! })
    }

    /**
     * Records an in-flight item as FAILED with [reason] (null when the failure gave none; the summary then
     * shows a fallback) and counts it, unless it was already recorded or its batch was cancelled or
     * interrupted (the item is Stranded), in which case nothing changes. Always runs in its own transaction, so declining never rolls back a caller's.
     *
     * @return whether the failure was recorded.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun fail(
        itemId: Long,
        reason: String?,
    ): Boolean {
        val now = Instant.now()
        if (transitions.failItem(itemId, reason, now, ImportBatchItemStatus.FAILED, ImportBatchItemStatus.IN_FLIGHT) == 0) {
            return false
        }
        if (!addToCounters(itemId, ImportBatchItemStatus.FAILED)) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
            return false
        }
        return true
    }

    /**
     * The athlete cancels their active batch. Whichever of the cancel and an item transaction takes the
     * batch row first wins: an item already counting finishes and counts; any later one is Stranded.
     *
     * @return whether the batch was cancelled; false when it is not the athlete's or already finished.
     */
    @Transactional
    fun cancel(
        batchId: Long,
        userId: Long,
    ): Boolean {
        val cancelled =
            transitions.cancel(batchId, userId, Instant.now(), ImportBatchStatus.CANCELLED, ImportBatchStatus.ACTIVE) > 0
        if (cancelled) {
            deleteBatchDirectoryAfterCommit(batchId)
            logger.info("Cancelled batch {}; its batch directory is deleted once the cancel commits", batchId)
        }
        return cancelled
    }

    /**
     * Completes the batch once it is active and none of its items is in flight. Idempotent, and the
     * mutex of ADR-0001: the single caller that gets `true` has completed the batch and scheduled its
     * staging directory for deletion once the completion commits.
     */
    @Transactional
    fun finalise(batchId: Long): Boolean {
        val won =
            transitions.completeIfSettled(
                batchId,
                Instant.now(),
                ImportBatchStatus.COMPLETED,
                ImportBatchStatus.ACTIVE,
                ImportBatchItemStatus.IN_FLIGHT,
            ) > 0
        if (won) {
            logger.info("Won the finalisation mutex for batch {}. Its batch directory is deleted once this commits.", batchId)
            deleteBatchDirectoryAfterCommit(batchId)
        }
        return won
    }

    /**
     * Crash recovery: marks every active batch INTERRUPTED. Its in-flight items are left as they are,
     * Stranded and never counted. Assumes a single node (ADR-0001).
     *
     * @return the number of batches interrupted.
     */
    @Transactional
    fun interruptActive(): Int =
        transitions.interruptActive(INTERRUPTED_MESSAGE, Instant.now(), ImportBatchStatus.INTERRUPTED, ImportBatchStatus.ACTIVE)

    /**
     * The athlete's live view of one batch, polled every second: its counter projection, plus its items'
     * failure and upgrade messages only when it has any. Null when the batch is not the athlete's.
     */
    @Transactional(readOnly = true)
    fun summary(
        batchId: Long,
        userId: Long,
    ): ImportBatchSummaryDTO? {
        val batch = batches.findSummaryByIdAndUserId(batchId, userId) ?: return null
        return toSummaries(listOf(batch)).single()
    }

    /** The athlete's import history, newest first, built exactly as [summary] builds each batch. */
    @Transactional(readOnly = true)
    fun history(userId: Long): List<ImportBatchSummaryDTO> = toSummaries(batches.findSummariesByUserId(userId))

    /** The one mapping from counter projections to summaries: one item-message query for all [summaries]. */
    private fun toSummaries(summaries: List<ImportBatchSummaryProjection>): List<ImportBatchSummaryDTO> {
        val withMessages = summaries.filter { it.getFailedFiles() > 0 || it.getUpgradedFiles() > 0 }.map { it.getId() }
        val messagesByBatch =
            if (withMessages.isEmpty()) {
                emptyMap()
            } else {
                items.findItemMessages(withMessages, MESSAGE_STATUSES).groupBy { it.getBatchId() }
            }
        return summaries.map { batch ->
            val messages = messagesByBatch[batch.getId()].orEmpty()
            ImportBatchSummaryDTO(
                id = batch.getId(),
                status = batch.getStatus(),
                totalFiles = batch.getTotalFiles(),
                processedFiles = batch.getProcessedFiles(),
                successfulFiles = batch.getSuccessfulFiles(),
                duplicateFiles = batch.getDuplicateFiles(),
                upgradedFiles = batch.getUpgradedFiles(),
                failedFiles = batch.getFailedFiles(),
                errorMessage = batch.getErrorMessage(),
                itemErrors =
                    messages
                        .filter { it.getStatus() in ImportBatchItemStatus.WITH_ERRORS }
                        .map { ImportBatchItemErrorDTO(it.getFilename(), it.getErrorMessage() ?: FAILED_FALLBACK) },
                itemUpgrades =
                    messages
                        .filter { it.getStatus() in ImportBatchItemStatus.WITH_UPGRADES }
                        .map {
                            ImportBatchItemUpgradeDTO(
                                it.getFilename(),
                                activityHistory.itemUpgradeChanges(it.getUpgrades(), it.getUpgradeMessage()),
                            )
                        },
                createdAt = batch.getCreatedAt(),
                completedAt = batch.getCompletedAt(),
            )
        }
    }

    /**
     * Deletes the batch's staging directory once the transition that ended the batch has committed,
     * so a failed commit never leaves an active batch without its staged files.
     */
    private fun deleteBatchDirectoryAfterCommit(batchId: Long) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    batchStorage.deleteBatchDirectory(batchId)
                }
            },
        )
    }

    /** Adds a just-recorded item to its batch's counters; false when the batch is no longer active. */
    private fun addToCounters(
        itemId: Long,
        status: ImportBatchItemStatus,
    ): Boolean {
        val deltas = CounterDeltas.of(status)
        return transitions.countItem(
            itemId,
            deltas.successful,
            deltas.upgraded,
            deltas.duplicate,
            deltas.failed,
            ImportBatchStatus.ACTIVE,
        ) > 0
    }

    /** What one recorded item adds to its batch's counters, besides one processed file. */
    private data class CounterDeltas(
        val successful: Int = 0,
        val upgraded: Int = 0,
        val duplicate: Int = 0,
        val failed: Int = 0,
    ) {
        companion object {
            fun of(status: ImportBatchItemStatus): CounterDeltas =
                when (status) {
                    ImportBatchItemStatus.SUCCESS -> CounterDeltas(successful = 1)
                    ImportBatchItemStatus.UPGRADED -> CounterDeltas(upgraded = 1)
                    // A MIXED file both created and upgraded Activities, so it counts towards both totals.
                    ImportBatchItemStatus.MIXED -> CounterDeltas(successful = 1, upgraded = 1)
                    ImportBatchItemStatus.DUPLICATE -> CounterDeltas(duplicate = 1)
                    ImportBatchItemStatus.FAILED -> CounterDeltas(failed = 1)
                    ImportBatchItemStatus.PENDING, ImportBatchItemStatus.PROCESSING ->
                        error("$status is not a recorded status")
                }
        }
    }

    /**
     * An Import Outcome in the Import Batch Item's vocabulary: its status, every Activity the file resolved
     * to (created, upgraded and duplicate), and the typed upgrades of an UPGRADED or MIXED item (one per
     * upgraded Activity, in file order; empty for any other item).
     */
    private data class RecordedOutcome(
        val status: ImportBatchItemStatus,
        val activities: List<Activity>,
        val upgrades: List<ActivityChange.Upgraded>,
    ) {
        companion object {
            fun of(outcome: ImportOutcome): RecordedOutcome =
                when (outcome) {
                    is ImportOutcome.Created ->
                        RecordedOutcome(ImportBatchItemStatus.SUCCESS, outcome.created + outcome.duplicates, emptyList())
                    is ImportOutcome.Upgraded ->
                        RecordedOutcome(
                            ImportBatchItemStatus.UPGRADED,
                            outcome.upgrades.map { it.activity } + outcome.duplicates,
                            outcome.upgrades.typed(),
                        )
                    is ImportOutcome.Mixed ->
                        RecordedOutcome(
                            ImportBatchItemStatus.MIXED,
                            outcome.created + outcome.upgrades.map { it.activity } + outcome.duplicates,
                            outcome.upgrades.typed(),
                        )
                    is ImportOutcome.Duplicate -> RecordedOutcome(ImportBatchItemStatus.DUPLICATE, outcome.duplicates, emptyList())
                }

            private fun List<ActivityUpgrade>.typed(): List<ActivityChange.Upgraded> = map { ActivityChange.Upgraded.of(it.change) }
        }
    }

    private companion object {
        const val INTERRUPTED_MESSAGE = "Batch was interrupted by a server restart"
        const val FAILED_FALLBACK = "Processing failed"
        val MESSAGE_STATUSES = ImportBatchItemStatus.WITH_ERRORS + ImportBatchItemStatus.WITH_UPGRADES
    }
}
