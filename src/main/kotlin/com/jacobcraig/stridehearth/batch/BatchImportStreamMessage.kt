package com.jacobcraig.stridehearth.batch

import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

data class BatchImportStreamMessage(
    val batchId: Long,
    val itemId: Long,
    val stagedFilePath: String,
) {
    fun toRecord(streamKey: String): MapRecord<String, String, String> =
        StreamRecords
            .string(
                mapOf(
                    KEY_BATCH_ID to batchId.toString(),
                    KEY_ITEM_ID to itemId.toString(),
                    KEY_STAGED_FILE_PATH to stagedFilePath,
                ),
            ).withStreamKey(streamKey)

    companion object {
        const val KEY_BATCH_ID = "batchId"
        const val KEY_ITEM_ID = "itemId"
        const val KEY_STAGED_FILE_PATH = "stagedFilePath"

        fun fromRecord(record: MapRecord<String, String, String>): BatchImportStreamMessage? {
            val batchId = record.value[KEY_BATCH_ID]?.toLongOrNull() ?: return null
            val itemId = record.value[KEY_ITEM_ID]?.toLongOrNull() ?: return null
            val stagedFilePath = record.value[KEY_STAGED_FILE_PATH] ?: return null
            return BatchImportStreamMessage(batchId, itemId, stagedFilePath)
        }
    }
}

@Component
class BatchImportStreamDispatcher(
    private val stringRedisTemplate: StringRedisTemplate,
    private val properties: BatchImportProperties,
) {
    fun dispatch(
        batchId: Long,
        itemId: Long,
        stagedFilePath: String,
    ): RecordId? {
        val message = BatchImportStreamMessage(batchId, itemId, stagedFilePath)
        val record = message.toRecord(properties.stream)
        return stringRedisTemplate.opsForStream<String, String>().add(record)
    }

    fun dispatchAll(
        batchId: Long,
        items: Iterable<ImportBatchItem>,
    ) {
        for (item in items) {
            dispatch(batchId, item.id!!, item.stagedFilePath)
        }
    }
}
