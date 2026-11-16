package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.ImportOutcome
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.InvalidActivityFileException
import com.jacobcraig.stridehearth.users.User
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.InputStreamSource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.PendingMessage
import org.springframework.data.redis.connection.stream.PendingMessages
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The worker's orchestration of one delivery: claim, import, then record or fail, then finalise before
 * ack. Only [ImportBatchLifecycle], the import and Redis are mocked; the lifecycle's own guarantees are
 * tested on Postgres in [ImportBatchLifecycleIntegrationTest].
 */
class BatchImportWorkerTest {
    @TempDir
    lateinit var tempDir: Path

    private val activityImportService: ActivityImportService = mock()
    private val batchStorageService: BatchStorage = mock()
    private val stringRedisTemplate: StringRedisTemplate = mock()
    private val streamOperations: StreamOperations<String, String, String> = mock()
    private val lifecycle: ImportBatchLifecycle = mock()
    private val properties = BatchImportProperties()

    private lateinit var worker: BatchImportWorker

    private val testUser = User(id = 1L, email = "tester@example.com", username = "tester", passwordHash = "hash")

    private val itemId = 20L

    private lateinit var stagedFilePath: String

    @BeforeEach
    fun setUp() {
        whenever(stringRedisTemplate.opsForStream<String, String>()).thenReturn(streamOperations)
        stagedFilePath =
            tempDir
                .resolve("test.fit")
                .toFile()
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
                .absolutePath
        whenever(lifecycle.claim(itemId, redelivered = false)).thenReturn(ItemClaim.Claimed(testUser.id!!, stagedFilePath))

        worker =
            BatchImportWorker(
                activityImportService = activityImportService,
                lifecycle = lifecycle,
                batchStorageService = batchStorageService,
                stringRedisTemplate = stringRedisTemplate,
                properties = properties,
            )
    }

    private fun process() =
        worker.processRecord(
            MapRecord
                .create(
                    properties.stream,
                    mapOf("batchId" to "10", "itemId" to itemId.toString(), "stagedFilePath" to stagedFilePath),
                ).withId(RecordId.of("1-0")),
        )

    /** The import module parses the staged file and hands its outcome to the worker's recorder. */
    private fun importProduces(outcome: ImportOutcome) {
        doAnswer { invocation ->
            invocation.getArgument<(ImportOutcome) -> Unit>(3).invoke(outcome)
            outcome
        }.whenever(activityImportService).import(any<InputStreamSource>(), eq(testUser.id!!), eq(ActivitySource.FIT_UPLOAD), any())
    }

    private fun importThrows(exception: Exception) {
        whenever(activityImportService.import(any<InputStreamSource>(), eq(testUser.id!!), eq(ActivitySource.FIT_UPLOAD), any()))
            .thenThrow(exception)
    }

    private fun activity(id: Long) =
        Activity(
            id = id,
            user = testUser,
            title = "Run $id",
            sport = ActivitySport.RUN,
            startedAt = Instant.parse("2026-03-01T08:00:00Z"),
            distance = 5.0,
            timeTakenSeconds = 1800,
            source = ActivitySource.FIT_UPLOAD,
        )

    /** Redis reports the message as delivered [count] times. */
    private fun deliveredTimes(count: Long) {
        val pending =
            PendingMessages(
                properties.group,
                listOf(PendingMessage(RecordId.of("1-0"), Consumer.from(properties.group, "worker"), Duration.ZERO, count)),
            )
        whenever(streamOperations.pending(eq(properties.stream), eq(properties.group), any<Range<*>>(), eq(1L))).thenReturn(pending)
    }

    private fun verifyFinalisedThenAcknowledged() {
        inOrder(lifecycle, streamOperations) {
            verify(lifecycle).finalise(10L)
            verify(streamOperations).acknowledge(properties.stream, properties.group, RecordId.of("1-0"))
        }
    }

    @Test
    fun `a claimed file's Import Outcome is recorded inside the import, and its staged file deleted`() {
        val outcome = ImportOutcome.Created(created = listOf(activity(100L)))
        importProduces(outcome)

        process()

        verify(lifecycle).recordOutcome(itemId, outcome)
        verify(lifecycle, never()).fail(any(), any())
        verify(batchStorageService).deleteItemFile(stagedFilePath)
    }

    @Test
    fun `an invalid activity file fails the item with the parser's message`() {
        importThrows(InvalidActivityFileException("FIT file failed integrity check"))

        process()

        verify(lifecycle).fail(itemId, "FIT file failed integrity check")
    }

    @Test
    fun `an integrity violation other than the duplicate race fails the item`() {
        importThrows(DataIntegrityViolationException("violates check constraint \"activity_distance_check\""))

        process()

        verify(lifecycle).fail(itemId, "violates check constraint \"activity_distance_check\"")
    }

    @Test
    fun `an item that can no longer be recorded is neither failed nor retried`() {
        doAnswer { throw ItemNoLongerRecordable("the batch was cancelled") }
            .whenever(activityImportService)
            .import(any<InputStreamSource>(), eq(testUser.id!!), eq(ActivitySource.FIT_UPLOAD), any())

        process()

        verify(lifecycle, never()).fail(any(), any())
        verifyFinalisedThenAcknowledged()
    }

    @Test
    fun `a settled delivery finalises its batch before acknowledging the message`() {
        importProduces(ImportOutcome.Created(created = listOf(activity(130L))))

        process()

        verifyFinalisedThenAcknowledged()
    }

    @Test
    fun `a skipped delivery still finalises its batch and then acknowledges the message`() {
        whenever(lifecycle.claim(itemId, redelivered = false)).thenReturn(ItemClaim.Skip(ItemClaim.SkipReason.STRANDED))

        process()

        verify(activityImportService, never()).import(any<InputStreamSource>(), any(), any(), any())
        verify(batchStorageService).deleteItemFile(stagedFilePath)
        verifyFinalisedThenAcknowledged()
    }

    @Test
    fun `a reconciler duplicate leaves the live owner's staged file in place`() {
        whenever(lifecycle.claim(itemId, redelivered = false)).thenReturn(ItemClaim.Skip(ItemClaim.SkipReason.OWNED_ELSEWHERE))

        process()

        verify(batchStorageService, never()).deleteItemFile(any())
        verifyFinalisedThenAcknowledged()
    }

    @Test
    fun `a delivery whose claim fails is left unacknowledged for the reclaimer`() {
        whenever(lifecycle.claim(itemId, redelivered = false)).thenThrow(IllegalStateException("database unavailable"))

        process()

        verify(lifecycle, never()).finalise(any())
        verify(streamOperations, never()).acknowledge(properties.stream, properties.group, RecordId.of("1-0"))
    }

    @Test
    fun `a delivery whose delivery count cannot be read is left unacknowledged for the reclaimer`() {
        whenever(streamOperations.pending(eq(properties.stream), eq(properties.group), any<Range<*>>(), eq(1L)))
            .thenThrow(IllegalStateException("redis unavailable"))

        process()

        verify(lifecycle, never()).claim(any(), any())
        verify(lifecycle, never()).finalise(any())
        verify(streamOperations, never()).acknowledge(properties.stream, properties.group, RecordId.of("1-0"))
    }

    @Test
    fun `a delivery past the attempt limit fails its item, finalises, routes to the DLQ, and only then acknowledges`() {
        deliveredTimes(properties.maxDeliveryAttempts)

        process()

        verify(lifecycle, never()).claim(any(), any())
        verify(activityImportService, never()).import(any<InputStreamSource>(), any(), any(), any())
        verify(batchStorageService).deleteItemFile(stagedFilePath)
        inOrder(lifecycle, streamOperations) {
            verify(lifecycle).fail(itemId, "Exceeded maximum delivery attempts (${properties.maxDeliveryAttempts})")
            verify(lifecycle).finalise(10L)
            verify(streamOperations).add(any<MapRecord<String, String, String>>())
            verify(streamOperations).acknowledge(properties.stream, properties.group, RecordId.of("1-0"))
        }
    }

    @Test
    fun `a DLQ delivery whose failure cannot be recorded is neither added to the DLQ nor acknowledged`() {
        deliveredTimes(properties.maxDeliveryAttempts)
        whenever(lifecycle.fail(any(), any())).thenThrow(IllegalStateException("database unavailable"))

        assertThrows<IllegalStateException> { process() }

        verify(streamOperations, never()).add(any<MapRecord<String, String, String>>())
        verify(streamOperations, never()).acknowledge(properties.stream, properties.group, RecordId.of("1-0"))
    }
}
