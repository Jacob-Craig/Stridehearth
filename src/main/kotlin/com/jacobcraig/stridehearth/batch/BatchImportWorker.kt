package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.InvalidActivityFileException
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamListener
import org.springframework.stereotype.Component
import java.io.File

/**
 * Processes one stream delivery of an **Import Batch Item**: claim it, import its staged file, record the
 * **Import Outcome** or the failure, then finalise the batch and only then acknowledge the message. Every
 * status change goes through [ImportBatchLifecycle].
 */
@Component
class BatchImportWorker(
    private val activityImportService: ActivityImportService,
    private val lifecycle: ImportBatchLifecycle,
    private val batchStorageService: BatchStorage,
    private val stringRedisTemplate: StringRedisTemplate,
    private val properties: BatchImportProperties,
) : StreamListener<String, MapRecord<String, String, String>> {
    private val logger = LoggerFactory.getLogger(BatchImportWorker::class.java)

    override fun onMessage(record: MapRecord<String, String, String>) {
        processRecord(record)
    }

    fun processRecord(record: MapRecord<String, String, String>) {
        val message = BatchImportStreamMessage.fromRecord(record)
        if (message == null) {
            logger.error("Invalid stream message payload: {}", record.value)
            acknowledge(record)
            return
        }

        // Treating an unknown count as a first delivery would skip a reclaimed PROCESSING item as
        // OWNED_ELSEWHERE and ack it, leaving it PROCESSING forever. Left unacknowledged, so the
        // reclaimer redelivers it.
        val deliveryCount = getDeliveryCount(record.id.value) ?: return
        if (deliveryCount >= properties.maxDeliveryAttempts) {
            logger.warn(
                "Message {} failed delivery {} times (threshold {}). Routing to DLQ.",
                record.id,
                deliveryCount,
                properties.maxDeliveryAttempts,
            )
            routeToDlq(record, message, deliveryCount)
            return
        }

        val claim =
            try {
                lifecycle.claim(message.itemId, redelivered = deliveryCount >= 2)
            } catch (e: Exception) {
                // Left unacknowledged, so the reclaimer redelivers it.
                logger.error("Could not claim item ${message.itemId} of batch ${message.batchId}", e)
                return
            }
        when (claim) {
            is ItemClaim.Claimed -> importItem(message.itemId, claim)
            is ItemClaim.Skip -> logger.info("Skipping item {} of batch {}: {}", message.itemId, message.batchId, claim.reason)
        }
        // A live owner still needs the staged file; every other delivery is done with it.
        val ownedElsewhere = claim == ItemClaim.Skip(ItemClaim.SkipReason.OWNED_ELSEWHERE)
        settle(record, message, deleteStagedFile = !ownedElsewhere)
    }

    private fun routeToDlq(
        record: MapRecord<String, String, String>,
        message: BatchImportStreamMessage,
        deliveryCount: Long,
    ) {
        val reason = "Exceeded maximum delivery attempts ($deliveryCount)"
        // The DLQ entry is added last, just before the ack: if failing or finalising throws, the message
        // stays unacknowledged and its redelivery has not already been added to the DLQ stream.
        lifecycle.fail(message.itemId, reason)
        settle(record, message, deleteStagedFile = true) { addToDlq(record, reason) }
    }

    private fun addToDlq(
        record: MapRecord<String, String, String>,
        reason: String,
    ) {
        try {
            val dlqPayload = record.value.toMutableMap()
            dlqPayload["failureReason"] = reason
            dlqPayload["originalRecordId"] = record.id.value
            stringRedisTemplate.opsForStream<String, String>().add(
                StreamRecords
                    .string(dlqPayload)
                    .withStreamKey(properties.dlqStream),
            )
        } catch (e: Exception) {
            logger.error("Failed to route message {} to DLQ stream: {}", record.id, e.message)
        }
    }

    /**
     * Finishes a delivery: drops its staged file, finalises the batch, and only then acknowledges the
     * message. A crash before the ack leaves the message to be reclaimed, and the redelivery finalises.
     */
    private fun settle(
        record: MapRecord<String, String, String>,
        message: BatchImportStreamMessage,
        deleteStagedFile: Boolean,
        beforeAcknowledge: () -> Unit = {},
    ) {
        if (deleteStagedFile) batchStorageService.deleteItemFile(message.stagedFilePath)
        lifecycle.finalise(message.batchId)
        beforeAcknowledge()
        acknowledge(record)
    }

    /** How many times the message has been delivered, or null when Redis could not tell us. */
    fun getDeliveryCount(recordId: String): Long? =
        try {
            val pendingInfo =
                stringRedisTemplate.opsForStream<String, String>().pending(
                    properties.stream,
                    properties.group,
                    Range.closed(recordId, recordId),
                    1,
                )
            pendingInfo?.firstOrNull()?.totalDeliveryCount ?: 1L
        } catch (e: Exception) {
            logger.warn("Could not read the delivery count of stream record {}; leaving it for the reclaimer: {}", recordId, e.message)
            null
        }

    /** Imports a claimed item's file, recording its Import Outcome inside the import, or its failure. */
    private fun importItem(
        itemId: Long,
        claim: ItemClaim.Claimed,
    ) {
        val file = File(claim.stagedFilePath)
        try {
            check(file.exists()) { "Staged file not found: ${file.absolutePath}" }
            activityImportService.import(FileSystemResource(file), claim.ownerId, ActivitySource.FIT_UPLOAD) { outcome ->
                lifecycle.recordOutcome(itemId, outcome)
            }
        } catch (e: ItemNoLongerRecordable) {
            logger.info("Not recording item {}; its Activities were rolled back: {}", itemId, e.message)
        } catch (e: InvalidActivityFileException) {
            logger.warn("FIT file decoding failed for item {}: {}", itemId, e.message)
            lifecycle.fail(itemId, e.message)
        } catch (e: Exception) {
            logger.error("Could not import item $itemId", e)
            lifecycle.fail(itemId, e.message)
        }
    }

    private fun acknowledge(record: MapRecord<String, String, String>) {
        try {
            stringRedisTemplate.opsForStream<String, String>().acknowledge(
                properties.stream,
                properties.group,
                record.id,
            )
        } catch (e: Exception) {
            logger.warn("Failed to acknowledge stream record {}: {}", record.id, e.message)
        }
    }
}
