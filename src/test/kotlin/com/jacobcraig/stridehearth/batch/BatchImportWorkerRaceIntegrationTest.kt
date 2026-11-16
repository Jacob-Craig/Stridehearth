package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How the worker keeps an **Import Batch** exact when two workers hold one item, a cancel lands while a
 * file is being persisted, or a worker dies at the wrong moment.
 *
 * These tests drive [BatchImportWorker.processRecord] directly against real Postgres and Redis. Each test
 * deletes the import stream first, which also stops this context's live stream listeners, so only the
 * deliveries a test makes reach the worker.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "stridehearth.storage.temp-dir=build/temp-test-worker-races",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.jacobcraig.stridehearth.batch.BatchCounterUpdateHook",
    ],
)
class BatchImportWorkerRaceIntegrationTest {
    @Autowired
    private lateinit var worker: BatchImportWorker

    @Autowired
    private lateinit var reclaimer: BatchImportReclaimer

    @MockitoSpyBean
    private lateinit var batchStorage: BatchStorage

    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var importBatchService: ImportBatchService

    @Autowired
    private lateinit var lifecycle: ImportBatchLifecycle

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

    @Value("\${stridehearth.storage.temp-dir}")
    private lateinit var tempDir: String

    private lateinit var runner: User

    private val sampleFitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()

    /** Thrown to stand in for the worker process dying; an [Error], so nothing in the worker swallows it. */
    private class SimulatedCrash : Error("simulated worker crash")

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "worker_race@example.com", passwordHash = "x", username = "worker_race"))
        stringRedisTemplate.delete(properties.stream)
        Thread.sleep(300) // let the live listeners see the stream vanish and stop
        stringRedisTemplate.opsForStream<String, String>().createGroup(properties.stream, ReadOffset.from("0"), properties.group)
    }

    @AfterEach
    fun tearDown() {
        BatchCounterUpdateHook.hook = null
        activityRepository.deleteAll()
        importBatchItemRepository.deleteAll()
        importBatchRepository.deleteAll()
        userRepository.deleteAll()
        stringRedisTemplate.delete(properties.stream)
        File(tempDir).deleteRecursively()
    }

    private fun batchOfOneSampleFile(
        fileBytes: ByteArray = sampleFitBytes,
        itemStatus: ImportBatchItemStatus = ImportBatchItemStatus.PENDING,
    ): Pair<ImportBatch, ImportBatchItem> {
        val batch = importBatchRepository.save(ImportBatch(user = runner, status = ImportBatchStatus.PROCESSING, totalFiles = 1))
        val stagedPath = batchStorage.stageFile(batch.id!!, "sample.fit", fileBytes.inputStream())
        val item =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = batch,
                    originalFilename = "sample.fit",
                    stagedFilePath = stagedPath.toString(),
                    status = itemStatus,
                ),
            )
        return batch to item
    }

    /** Enqueues the item's message and has [consumer] read it, so it sits in that consumer's pending list. */
    private fun deliverTo(
        consumer: String,
        batch: ImportBatch,
        item: ImportBatchItem,
    ): MapRecord<String, String, String> {
        val streams = stringRedisTemplate.opsForStream<String, String>()
        streams.add(
            StreamRecords
                .string(
                    mapOf(
                        "batchId" to batch.id.toString(),
                        "itemId" to item.id.toString(),
                        "stagedFilePath" to item.stagedFilePath,
                    ),
                ).withStreamKey(properties.stream),
        )
        return streams
            .read(Consumer.from(properties.group, consumer), StreamOffset.create(properties.stream, ReadOffset.lastConsumed()))!!
            .single()
    }

    /** Hands the pending message to another consumer, as the reclaimer does, raising its delivery count to 2. */
    private fun redeliver(recordId: RecordId) {
        stringRedisTemplate.execute { connection ->
            connection.execute(
                "XCLAIM",
                properties.stream.toByteArray(),
                properties.group.toByteArray(),
                "reclaimer".toByteArray(),
                "0".toByteArray(),
                recordId.value.toByteArray(),
                "RETRYCOUNT".toByteArray(),
                "2".toByteArray(),
            )
        }
    }

    private fun awaitItemStatus(
        item: ImportBatchItem,
        status: ImportBatchItemStatus,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (importBatchItemRepository.findByIdOrNull(item.id!!)!!.status != status) {
            check(System.currentTimeMillis() < deadline) { "item ${item.id} never reached $status" }
            Thread.sleep(20)
        }
    }

    private fun messageFor(
        batch: ImportBatch,
        item: ImportBatchItem,
    ): MapRecord<String, String, String> =
        MapRecord
            .create(
                properties.stream,
                mapOf("batchId" to batch.id.toString(), "itemId" to item.id.toString(), "stagedFilePath" to item.stagedFilePath),
            ).withId(RecordId.of("1-0"))

    /** Ages a pending message past the reclaim idle threshold, as if its consumer died long ago. */
    private fun makeReclaimable(recordId: RecordId) {
        stringRedisTemplate.execute { connection ->
            connection.execute(
                "XCLAIM",
                properties.stream.toByteArray(),
                properties.group.toByteArray(),
                "dead-worker".toByteArray(),
                "0".toByteArray(),
                recordId.value.toByteArray(),
                "IDLE".toByteArray(),
                (properties.idleThreshold.toMillis() + 10_000).toString().toByteArray(),
                "JUSTID".toByteArray(), // leaves the delivery count alone
            )
        }
    }

    @Test
    fun `a worker that crashes after recording the last file but before acking leaves the batch COMPLETED once its message is reclaimed`() {
        val (batch, item) = batchOfOneSampleFile()
        val record = deliverTo("doomed-worker", batch, item)
        var crashed = false
        doAnswer { invocation ->
            if (!crashed) {
                crashed = true
                throw SimulatedCrash()
            }
            invocation.callRealMethod()
        }.whenever(batchStorage).deleteItemFile(any())

        assertThrows<SimulatedCrash> { worker.processRecord(record) }
        assertEquals(ImportBatchItemStatus.SUCCESS, importBatchItemRepository.findByIdOrNull(item.id!!)!!.status)

        makeReclaimable(record.id)
        reclaimer.reclaimPendingMessages()

        val after = importBatchRepository.findByIdOrNull(batch.id!!)!!
        assertEquals(ImportBatchStatus.COMPLETED, after.status)
        assertEquals(1, after.processedFiles)
        assertEquals(1, after.successfulFiles)
    }

    /**
     * A slow worker claims the item; while it is still parsing, the reclaimer hands its message to a second
     * worker. Both finish parsing together, and the first to count holds its transaction open long enough
     * for the second to try to count too.
     */
    private fun twoWorkersHold(
        batch: ImportBatch,
        item: ImportBatchItem,
    ) {
        val bothParsing = CyclicBarrier(2)
        doAnswer { invocation ->
            bothParsing.await(10, TimeUnit.SECONDS)
            invocation.callRealMethod()
        }.whenever(fitActivityParser).parse(any())
        val firstToCount = AtomicBoolean(true)
        BatchCounterUpdateHook.hook = { if (firstToCount.getAndSet(false)) Thread.sleep(1_500) }
        val pool = Executors.newFixedThreadPool(2)

        val record = deliverTo("slow-worker", batch, item)
        val slowWorker = pool.submit { worker.processRecord(record) }
        awaitItemStatus(item, ImportBatchItemStatus.PROCESSING)
        redeliver(record.id)
        val takeover = pool.submit { worker.processRecord(record) }
        slowWorker.get(30, TimeUnit.SECONDS)
        takeover.get(30, TimeUnit.SECONDS)
        pool.shutdown()
    }

    @Test
    fun `two workers holding the same corrupt item record its failure once`() {
        val (batch, item) = batchOfOneSampleFile("NOT A VALID FIT FILE".toByteArray())

        twoWorkersHold(batch, item)

        val after = importBatchRepository.findByIdOrNull(batch.id!!)!!
        assertEquals(1, after.processedFiles, "the file is counted once")
        assertEquals(1, after.failedFiles)
        assertEquals(ImportBatchItemStatus.FAILED, importBatchItemRepository.findByIdOrNull(item.id!!)!!.status)
    }

    @Test
    fun `two workers holding the same item record its Duplicate outcome once`() {
        activityImportService.import(ByteArrayResource(sampleFitBytes), runner.id!!) // the file is already imported
        val (batch, item) = batchOfOneSampleFile()

        twoWorkersHold(batch, item)

        val after = importBatchRepository.findByIdOrNull(batch.id!!)!!
        assertEquals(1, after.processedFiles, "the file is counted once")
        assertEquals(1, after.duplicateFiles)
        assertEquals(ImportBatchItemStatus.DUPLICATE, importBatchItemRepository.findByIdOrNull(item.id!!)!!.status)
        assertEquals(1, activityRepository.findByUserId(runner.id!!).size)
    }

    @Test
    fun `a cancel that commits while a file is being persisted keeps the file out of the totals and rolls its Activities back`() {
        val (batch, item) = batchOfOneSampleFile()
        val cancelled = AtomicBoolean(false)
        BatchCounterUpdateHook.hook = {
            // The athlete's cancel commits in the moment between the worker's checks and its count.
            if (!cancelled.getAndSet(true)) {
                val cancel = Thread { importBatchService.cancelBatch(batch.id!!, runner.id!!) }
                cancel.start()
                cancel.join(5_000)
            }
        }

        worker.processRecord(messageFor(batch, item))

        val after = importBatchRepository.findByIdOrNull(batch.id!!)!!
        assertEquals(ImportBatchStatus.CANCELLED, after.status)
        assertEquals(0, after.processedFiles, "nothing is counted after the cancel")
        assertEquals(0, after.successfulFiles)
        assertEquals(0, activityRepository.findByUserId(runner.id!!).size, "the file's Activities roll back")
        val stranded = importBatchItemRepository.findByIdOrNull(item.id!!)!!
        assertEquals(ImportBatchItemStatus.PROCESSING, stranded.status, "the item is left Stranded, never recorded")
    }

    private fun isPending(recordId: RecordId): Boolean =
        stringRedisTemplate
            .opsForStream<String, String>()
            .pending(properties.stream, properties.group, Range.closed(recordId.value, recordId.value), 1)
            .isEmpty
            .not()

    @Test
    fun `a reconciler duplicate delivered while its item is PROCESSING is skipped and acknowledged, leaving the owner's file alone`() {
        // Another worker claimed the item and is still parsing it; the reconciler re-enqueued it anyway.
        val (batch, item) = batchOfOneSampleFile(itemStatus = ImportBatchItemStatus.PROCESSING)
        val duplicate = deliverTo("worker", batch, item)

        worker.processRecord(duplicate)

        assertFalse(isPending(duplicate.id), "the duplicate message is acknowledged")
        assertTrue(File(item.stagedFilePath).exists(), "the owner still needs its staged file")
        assertEquals(ImportBatchItemStatus.PROCESSING, importBatchItemRepository.findByIdOrNull(item.id!!)!!.status)
        assertEquals(0, activityRepository.findByUserId(runner.id!!).size, "the file is not processed a second time")
        assertEquals(0, importBatchRepository.findByIdOrNull(batch.id!!)!!.processedFiles)
    }

    @Test
    fun `a message for an Interrupted batch is skipped, its staged file deleted, and the message acknowledged`() {
        val (batch, item) = batchOfOneSampleFile()
        // Unlike a cancel, interrupting leaves the batch directory alone, so only the worker can delete the file.
        lifecycle.interruptActive()
        val record = deliverTo("worker", batch, item)
        assertTrue(File(item.stagedFilePath).exists())

        worker.processRecord(record)

        assertFalse(isPending(record.id), "the message is acknowledged")
        assertFalse(File(item.stagedFilePath).exists(), "the worker deletes the Stranded item's staged file")
        assertEquals(ImportBatchItemStatus.PENDING, importBatchItemRepository.findByIdOrNull(item.id!!)!!.status)
        assertEquals(0, importBatchRepository.findByIdOrNull(batch.id!!)!!.processedFiles)
    }
}
