package com.jacobcraig.stridehearth.batch

import io.lettuce.core.Consumer
import io.lettuce.core.XAutoClaimArgs
import io.lettuce.core.api.async.RedisStreamAsyncCommands
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class BatchImportReclaimer(
    private val stringRedisTemplate: StringRedisTemplate,
    private val properties: BatchImportProperties,
    private val worker: BatchImportWorker,
) {
    private val logger = LoggerFactory.getLogger(BatchImportReclaimer::class.java)

    @Scheduled(fixedDelayString = "\${stridehearth.import.reclaim-interval-ms:10000}")
    fun reclaimPendingMessages(): Int {
        var totalClaimedOrHandled = 0
        var startId = "0-0"
        val consumerName = "reclaimer"

        do {
            val claimedRecords = mutableListOf<MapRecord<String, String, String>>()
            var nextStartId = "0-0"

            try {
                stringRedisTemplate.execute { connection ->
                    val nativeConn = connection.nativeConnection
                    if (nativeConn is RedisStreamAsyncCommands<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val streamCommands = nativeConn as RedisStreamAsyncCommands<ByteArray, ByteArray>
                        val args =
                            XAutoClaimArgs.Builder
                                .xautoclaim(
                                    Consumer.from(properties.group.toByteArray(), consumerName.toByteArray()),
                                    properties.idleThreshold,
                                    startId,
                                ).count(100)

                        val result = streamCommands.xautoclaim(properties.stream.toByteArray(), args).get()
                        nextStartId = result.id ?: "0-0"
                        for (msg in result.messages) {
                            val map = msg.body.mapKeys { String(it.key) }.mapValues { String(it.value) }
                            claimedRecords.add(MapRecord.create(properties.stream, map).withId(RecordId.of(msg.id)))
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("XAUTOCLAIM execution failed", e)
                break
            }

            if (claimedRecords.isEmpty()) {
                break
            }

            for (record in claimedRecords) {
                totalClaimedOrHandled++
                handleClaimedRecord(record)
            }

            startId = nextStartId
        } while (startId != "0-0" && startId.isNotEmpty())

        return totalClaimedOrHandled
    }

    private fun handleClaimedRecord(record: MapRecord<String, String, String>) {
        try {
            worker.processRecord(record)
        } catch (e: Exception) {
            logger.error("Error processing reclaimed message {}", record.id, e)
        }
    }
}
