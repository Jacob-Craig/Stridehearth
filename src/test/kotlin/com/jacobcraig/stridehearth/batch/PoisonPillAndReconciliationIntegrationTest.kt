package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.TestPropertySource
import java.io.File
import java.time.Instant

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "stridehearth.storage.temp-dir=build/temp-test-batches-recon",
    ],
)
class PoisonPillAndReconciliationIntegrationTest {
    @Autowired
    private lateinit var worker: BatchImportWorker

    @Autowired
    private lateinit var reconciler: BatchImportReconciliationService

    @Autowired
    private lateinit var reclaimer: BatchImportReclaimer

    @Autowired
    private lateinit var importBatchRepository: ImportBatchRepository

    @Autowired
    private lateinit var importBatchItemRepository: ImportBatchItemRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var stringRedisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var properties: BatchImportProperties

    @Value("\${stridehearth.storage.temp-dir:./temp-batches}")
    private lateinit var tempDirProperty: String

    private lateinit var testUser: User

    @BeforeEach
    fun setUp() {
        testUser =
            userRepository.save(
                User(email = "recon_test@example.com", passwordHash = "x", username = "recon_test"),
            )
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        importBatchItemRepository.deleteAll()
        importBatchRepository.deleteAll()
        userRepository.deleteAll()
        File(tempDirProperty).deleteRecursively()
    }

    @Test
    fun `worker idempotency check verifies item state before processing to prevent duplicate execution`() {
        val batch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.PROCESSING,
                    totalFiles = 1,
                    processedFiles = 1,
                    successfulFiles = 1,
                ),
            )

        val activity =
            activityRepository.save(
                Activity(
                    user = testUser,
                    title = "Initial Run",
                    source = ActivitySource.FIT_UPLOAD,
                    sport = ActivitySport.RUN,
                    startedAt = Instant.now(),
                    distance = 5000.0,
                    timeTakenSeconds = 1200,
                    externalId = "unique-ext-123",
                ),
            )

        val item =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = batch,
                    originalFilename = "run.fit",
                    stagedFilePath = "/path/does/not/exist/run.fit",
                    status = ImportBatchItemStatus.SUCCESS,
                    activities = listOf(activity),
                    processedAt = Instant.now(),
                ),
            )

        val initialActivityCount = activityRepository.count()
        val initialItem = importBatchItemRepository.findByIdOrNull(item.id!!)!!

        // Duplicate delivery of stream record for already completed item
        val duplicateRecord =
            MapRecord
                .create(
                    properties.stream,
                    mapOf(
                        "batchId" to batch.id!!.toString(),
                        "itemId" to item.id!!.toString(),
                        "stagedFilePath" to item.stagedFilePath,
                    ),
                ).withId(RecordId.of("1000-0"))

        worker.onMessage(duplicateRecord)

        val itemAfter = importBatchItemRepository.findByIdOrNull(item.id!!)!!
        assertEquals(ImportBatchItemStatus.SUCCESS, itemAfter.status)
        assertEquals(initialItem.processedAt, itemAfter.processedAt)
        assertEquals(initialActivityCount, activityRepository.count())

        val batchAfter = importBatchRepository.findByIdOrNull(batch.id!!)!!
        assertEquals(1, batchAfter.processedFiles)
        assertEquals(1, batchAfter.successfulFiles)
        assertEquals(0, batchAfter.failedFiles)
    }

    @Test
    fun `worker idempotency check with existing file does not re-process or increment batch counters`() {
        val sampleFit = ClassPathResource("runs/sample.fit").file
        val stagedFile = File(tempDirProperty, "test-idempotency.fit")
        stagedFile.parentFile.mkdirs()
        sampleFit.copyTo(stagedFile, overwrite = true)

        val batch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.PROCESSING,
                    totalFiles = 1,
                    processedFiles = 1,
                    successfulFiles = 1,
                ),
            )

        val item =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = batch,
                    originalFilename = "sample.fit",
                    stagedFilePath = stagedFile.absolutePath,
                    status = ImportBatchItemStatus.SUCCESS,
                    processedAt = Instant.now(),
                ),
            )

        val initialActivityCount = activityRepository.count()

        val record =
            MapRecord
                .create(
                    properties.stream,
                    mapOf(
                        "batchId" to batch.id!!.toString(),
                        "itemId" to item.id!!.toString(),
                        "stagedFilePath" to stagedFile.absolutePath,
                    ),
                ).withId(RecordId.of("1001-0"))

        worker.onMessage(record)

        // Activity count should NOT increase
        assertEquals(initialActivityCount, activityRepository.count())

        val itemAfter = importBatchItemRepository.findByIdOrNull(item.id!!)!!
        assertEquals(ImportBatchItemStatus.SUCCESS, itemAfter.status)

        val batchAfter = importBatchRepository.findByIdOrNull(batch.id!!)!!
        assertEquals(1, batchAfter.processedFiles)
        assertEquals(1, batchAfter.successfulFiles)
        assertEquals(0, batchAfter.failedFiles)
    }

    @Test
    fun `scheduled reconciliation poller queries PENDING items older than 60s and re-enqueues them`() {
        // Clear Redis stream
        stringRedisTemplate.delete(properties.stream)

        // Stalled batch created 75 seconds ago
        val stalledBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.PROCESSING,
                    totalFiles = 1,
                    createdAt = Instant.now().minusSeconds(75),
                ),
            )

        val stalledItem =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = stalledBatch,
                    originalFilename = "stalled.fit",
                    stagedFilePath = "/path/to/stalled.fit",
                    status = ImportBatchItemStatus.PENDING,
                ),
            )

        // Fresh batch created 10 seconds ago
        val freshBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.PROCESSING,
                    totalFiles = 1,
                    createdAt = Instant.now().minusSeconds(10),
                ),
            )

        val freshItem =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = freshBatch,
                    originalFilename = "fresh.fit",
                    stagedFilePath = "/path/to/fresh.fit",
                    status = ImportBatchItemStatus.PENDING,
                ),
            )

        val reEnqueued = reconciler.reconcilePendingItems()
        assertEquals(1, reEnqueued, "Only the stalled item older than 60s should be re-enqueued")

        // Verify the stream contains the re-enqueued item
        val streamRecords =
            stringRedisTemplate.opsForStream<String, String>().read(
                org.springframework.data.redis.connection.stream.StreamOffset
                    .fromStart(properties.stream),
            )

        assertEquals(1, streamRecords.size)
        val record = streamRecords[0]
        assertEquals(stalledBatch.id.toString(), record.value["batchId"])
        assertEquals(stalledItem.id.toString(), record.value["itemId"])
        assertEquals(stalledItem.stagedFilePath, record.value["stagedFilePath"])
    }

    @Test
    fun `Redis Stream consumer group uses XAUTOCLAIM with a 60-second idle threshold to reclaim pending messages from dead workers`() {
        stringRedisTemplate.delete(properties.stream)
        stringRedisTemplate.delete(properties.dlqStream)

        try {
            stringRedisTemplate.opsForStream<String, String>().createGroup(properties.stream, properties.group)
        } catch (_: Exception) {
        }

        val batch =
            importBatchRepository.save(
                ImportBatch(user = testUser, status = ImportBatchStatus.PROCESSING, totalFiles = 1),
            )
        val item =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = batch,
                    originalFilename = "run.fit",
                    stagedFilePath = "/path/to/reclaim.fit",
                    status = ImportBatchItemStatus.PENDING,
                ),
            )

        val recordId =
            stringRedisTemplate.opsForStream<String, String>().add(
                org.springframework.data.redis.connection.stream.StreamRecords
                    .string(
                        mapOf(
                            "batchId" to batch.id.toString(),
                            "itemId" to item.id.toString(),
                            "stagedFilePath" to item.stagedFilePath,
                        ),
                    ).withStreamKey(properties.stream),
            )!!

        // Consumer "dead-worker" reads the message (places it into PEL for dead-worker)
        val deadWorkerRead =
            stringRedisTemplate.opsForStream<String, String>().read(
                org.springframework.data.redis.connection.stream.Consumer
                    .from(properties.group, "dead-worker"),
                org.springframework.data.redis.connection.stream.StreamOffset.create(
                    properties.stream,
                    org.springframework.data.redis.connection.stream.ReadOffset
                        .lastConsumed(),
                ),
            )
        assertEquals(1, deadWorkerRead.size)

        // Case 1: Fresh message (idle time < 60s) should NOT be claimed by 60s idle threshold
        val claimedFresh = reclaimer.reclaimPendingMessages()
        assertEquals(0, claimedFresh, "Should not claim messages idle for less than 60s")

        // Case 2: Set message idle time to 70s (> 60s idle threshold) in Redis PEL
        stringRedisTemplate.execute { connection ->
            connection.execute(
                "XCLAIM",
                properties.stream.toByteArray(),
                properties.group.toByteArray(),
                "dead-worker".toByteArray(),
                "0".toByteArray(),
                recordId.value.toByteArray(),
                "IDLE".toByteArray(),
                "70000".toByteArray(),
            )
        }

        val claimedStalled = reclaimer.reclaimPendingMessages()
        assertEquals(1, claimedStalled, "Should claim message idle for > 60s using XAUTOCLAIM")
    }

    @Test
    fun `messages failing delivery 3 times are acknowledged, routed to import-batches-dlq, and marked FAILED`() {
        stringRedisTemplate.delete(properties.stream)
        stringRedisTemplate.delete(properties.dlqStream)

        try {
            stringRedisTemplate.opsForStream<String, String>().createGroup(properties.stream, properties.group)
        } catch (_: Exception) {
        }

        val batch =
            importBatchRepository.save(
                ImportBatch(user = testUser, status = ImportBatchStatus.PROCESSING, totalFiles = 1),
            )
        val item =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = batch,
                    originalFilename = "poison-pill.fit",
                    stagedFilePath = "/path/to/poison-pill.fit",
                    status = ImportBatchItemStatus.PENDING,
                ),
            )

        val recordId =
            stringRedisTemplate.opsForStream<String, String>().add(
                org.springframework.data.redis.connection.stream.StreamRecords
                    .string(
                        mapOf(
                            "batchId" to batch.id.toString(),
                            "itemId" to item.id.toString(),
                            "stagedFilePath" to item.stagedFilePath,
                        ),
                    ).withStreamKey(properties.stream),
            )!!

        // Simulate 3 delivery attempts in Redis PEL
        // Attempt 1: read by worker-1
        stringRedisTemplate.opsForStream<String, String>().read(
            org.springframework.data.redis.connection.stream.Consumer
                .from(properties.group, "worker-1"),
            org.springframework.data.redis.connection.stream.StreamOffset.create(
                properties.stream,
                org.springframework.data.redis.connection.stream.ReadOffset
                    .lastConsumed(),
            ),
        )

        // Attempt 2: claim by worker-2 with RETRYCOUNT 2
        stringRedisTemplate.execute { connection ->
            connection.execute(
                "XCLAIM",
                properties.stream.toByteArray(),
                properties.group.toByteArray(),
                "worker-2".toByteArray(),
                "0".toByteArray(),
                recordId.value.toByteArray(),
                "RETRYCOUNT".toByteArray(),
                "2".toByteArray(),
            )
        }

        // Attempt 3: claim by worker-3 with RETRYCOUNT 3 and IDLE 70000ms
        stringRedisTemplate.execute { connection ->
            connection.execute(
                "XCLAIM",
                properties.stream.toByteArray(),
                properties.group.toByteArray(),
                "worker-3".toByteArray(),
                "0".toByteArray(),
                recordId.value.toByteArray(),
                "RETRYCOUNT".toByteArray(),
                "3".toByteArray(),
                "IDLE".toByteArray(),
                "70000".toByteArray(),
            )
        }

        // Verify Redis delivery count is 3
        val pendingInfo =
            stringRedisTemplate.opsForStream<String, String>().pending(
                properties.stream,
                properties.group,
                org.springframework.data.domain.Range
                    .closed(recordId.value, recordId.value),
                1,
            )
        assertEquals(3, pendingInfo.first().totalDeliveryCount)

        // Reclaimer runs: detects delivery attempts reached 3 -> ACKs, routes to DLQ, marks item FAILED
        reclaimer.reclaimPendingMessages()

        // 1. Message must be acknowledged and removed from main stream PEL
        val pendingAfter =
            stringRedisTemplate.opsForStream<String, String>().pending(
                properties.stream,
                properties.group,
                org.springframework.data.domain.Range
                    .closed(recordId.value, recordId.value),
                1,
            )
        assertTrue(pendingAfter.isEmpty(), "Message must be acknowledged and removed from PEL")

        // 2. Message routed to DLQ stream
        val dlqRecords =
            stringRedisTemplate.opsForStream<String, String>().read(
                org.springframework.data.redis.connection.stream.StreamOffset
                    .fromStart(properties.dlqStream),
            )
        assertEquals(1, dlqRecords.size, "Poison pill must be routed to DLQ stream")
        assertEquals(batch.id.toString(), dlqRecords[0].value["batchId"])
        assertEquals(item.id.toString(), dlqRecords[0].value["itemId"])

        // 3. ImportBatchItem in DB marked FAILED
        val itemAfter = importBatchItemRepository.findByIdOrNull(item.id!!)!!
        assertEquals(ImportBatchItemStatus.FAILED, itemAfter.status)
        assertNotNull(itemAfter.errorMessage)
        assertNotNull(itemAfter.processedAt)

        // 4. Batch failure counter incremented
        val batchAfter = importBatchRepository.findByIdOrNull(batch.id!!)!!
        assertEquals(1, batchAfter.failedFiles)
    }
}
