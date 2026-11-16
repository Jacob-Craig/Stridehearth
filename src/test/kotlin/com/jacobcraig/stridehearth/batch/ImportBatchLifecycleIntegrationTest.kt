package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityDetailsChange
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.ActivityUpgrade
import com.jacobcraig.stridehearth.activities.DetailsChange
import com.jacobcraig.stridehearth.activities.ImportOutcome
import com.jacobcraig.stridehearth.activities.LapsChange
import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.BackfilledLapsDTO
import com.jacobcraig.stridehearth.activities.history.DetailsUpgrade
import com.jacobcraig.stridehearth.activities.history.LapsUpgrade
import com.jacobcraig.stridehearth.activities.history.ParserVersionUpgradedDetailsDTO
import com.jacobcraig.stridehearth.activities.history.UpgradedChangeDTO
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The **Import Batch** lifecycle module on real Postgres, where its compare-and-set and row-lock guarantees live. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "stridehearth.storage.temp-dir=build/temp-test-batch-lifecycle",
        "spring.jpa.properties.hibernate.generate_statistics=true",
    ],
)
class ImportBatchLifecycleIntegrationTest {
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
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    private lateinit var athlete: User

    @Autowired
    private lateinit var activityHistory: ActivityHistory

    private val lapsBackfilled = ActivityDetailsChange.Changed(details = null, laps = LapsChange.Backfilled(3))
    private val lapsBackfilledChange = ActivityChange.Upgraded(details = null, laps = LapsUpgrade.Backfilled(3))

    private val detailsUpgraded =
        ActivityDetailsChange.Changed(DetailsChange.Upgraded(from = ParserVersion(1), to = ParserVersion(2)), laps = null)
    private val detailsUpgradedChange = ActivityChange.Upgraded(DetailsUpgrade.ParserVersionUpgraded(1, 2), laps = null)

    /** An item's stored error message and decoded typed upgrades. */
    private fun messagesOf(item: ImportBatchItem): Pair<String?, List<ActivityChange.Upgraded>?> =
        reload(item).let { it.errorMessage to it.upgrades?.let(activityHistory::decodeItemUpgrades) }

    @BeforeEach
    fun setUp() {
        athlete = userRepository.save(User(email = "lifecycle@example.com", passwordHash = "x", username = "lifecycle"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        importBatchItemRepository.deleteAll()
        importBatchRepository.deleteAll()
        userRepository.deleteAll()
        File("build/temp-test-batch-lifecycle").deleteRecursively()
    }

    private fun batch(
        status: ImportBatchStatus = ImportBatchStatus.PROCESSING,
        vararg itemStatuses: ImportBatchItemStatus,
    ): ImportBatch {
        val batch = importBatchRepository.save(ImportBatch(user = athlete, status = status, totalFiles = itemStatuses.size))
        itemStatuses.forEachIndexed { i, itemStatus ->
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = batch,
                    originalFilename = "file-$i.fit",
                    stagedFilePath = "/staged/file-$i.fit",
                    status = itemStatus,
                ),
            )
        }
        return batch
    }

    private fun statusOf(batch: ImportBatch) = importBatchRepository.findByIdOrNull(batch.id!!)!!.status

    @Test
    fun `finalise completes a batch whose items are all recorded`() {
        val batch = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.SUCCESS, ImportBatchItemStatus.FAILED)

        assertTrue(lifecycle.finalise(batch.id!!))

        assertEquals(ImportBatchStatus.COMPLETED, statusOf(batch))
    }

    @Test
    fun `finalise leaves a batch PROCESSING while any item is PENDING or PROCESSING`() {
        val waiting = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.SUCCESS, ImportBatchItemStatus.PENDING)
        val inProgress = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.DUPLICATE, ImportBatchItemStatus.PROCESSING)

        assertFalse(lifecycle.finalise(waiting.id!!))
        assertFalse(lifecycle.finalise(inProgress.id!!))

        assertEquals(ImportBatchStatus.PROCESSING, statusOf(waiting))
        assertEquals(ImportBatchStatus.PROCESSING, statusOf(inProgress))
    }

    @Test
    fun `finalise never completes a Cancelled or Interrupted Import Batch`() {
        val cancelled = batch(ImportBatchStatus.CANCELLED, ImportBatchItemStatus.SUCCESS)
        val interrupted = batch(ImportBatchStatus.INTERRUPTED, ImportBatchItemStatus.SUCCESS)

        assertFalse(lifecycle.finalise(cancelled.id!!))
        assertFalse(lifecycle.finalise(interrupted.id!!))

        assertEquals(ImportBatchStatus.CANCELLED, statusOf(cancelled))
        assertEquals(ImportBatchStatus.INTERRUPTED, statusOf(interrupted))
    }

    @Test
    fun `two concurrent finalise calls complete the batch once and exactly one wins`() {
        val batch = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.SUCCESS)
        val start = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)

        val results =
            List(2) {
                pool.submit<Boolean> {
                    start.await(5, TimeUnit.SECONDS)
                    lifecycle.finalise(batch.id!!)
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, results.count { it }, "exactly one finaliser wins the mutex")
        assertEquals(ImportBatchStatus.COMPLETED, statusOf(batch))
    }

    @Test
    fun `interruptActive marks every PENDING and PROCESSING batch INTERRUPTED and leaves finished batches alone`() {
        val pending = batch(ImportBatchStatus.PENDING, ImportBatchItemStatus.PENDING)
        val processing = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PROCESSING)
        val completed = batch(ImportBatchStatus.COMPLETED, ImportBatchItemStatus.SUCCESS)

        assertEquals(2, lifecycle.interruptActive())

        assertEquals(ImportBatchStatus.INTERRUPTED, statusOf(pending))
        assertEquals(ImportBatchStatus.INTERRUPTED, statusOf(processing))
        assertEquals(ImportBatchStatus.COMPLETED, statusOf(completed))
        val interruptedItem = importBatchItemRepository.findByBatchId(processing.id!!).single()
        assertEquals(ImportBatchItemStatus.PROCESSING, interruptedItem.status, "its in-flight item is left Stranded")
    }

    private fun itemsOf(batch: ImportBatch) = importBatchItemRepository.findByBatchId(batch.id!!).sortedBy { it.id }

    private fun reload(batch: ImportBatch) = importBatchRepository.findByIdOrNull(batch.id!!)!!

    private fun reload(item: ImportBatchItem) = importBatchItemRepository.findByIdOrNull(item.id!!)!!

    private var activitySeq = 0

    /** Saves an Activity, as the import does inside its persist transaction. */
    private fun newActivity(): Activity =
        activityRepository.save(
            Activity(
                user = athlete,
                title = "Run ${++activitySeq}",
                sport = ActivitySport.RUN,
                source = ActivitySource.FIT_UPLOAD,
                externalId = "lifecycle-${System.nanoTime()}",
                startedAt = Instant.parse("2026-03-01T08:00:00Z").plusSeconds(activitySeq * 3600L),
                distance = 5.0,
                timeTakenSeconds = 1800,
            ),
        )

    /** Persists the file's Activities and records the item's outcome in one transaction, as the import does. */
    private fun importAndRecord(
        item: ImportBatchItem,
        outcome: () -> ImportOutcome,
    ) {
        transactionTemplate.executeWithoutResult { lifecycle.recordOutcome(item.id!!, outcome()) }
    }

    @Test
    fun `each Import Outcome records its item status and counters, and MIXED counts as both imported and upgraded`() {
        val batch = batch(ImportBatchStatus.PROCESSING, *Array(4) { ImportBatchItemStatus.PROCESSING })
        val (created, upgraded, mixed, duplicate) = itemsOf(batch)

        importAndRecord(created) { ImportOutcome.Created(created = listOf(newActivity())) }
        importAndRecord(upgraded) { ImportOutcome.Upgraded(upgrades = listOf(ActivityUpgrade(newActivity(), lapsBackfilled))) }
        importAndRecord(mixed) {
            ImportOutcome.Mixed(created = listOf(newActivity()), upgrades = listOf(ActivityUpgrade(newActivity(), lapsBackfilled)))
        }
        importAndRecord(duplicate) { ImportOutcome.Duplicate(duplicates = listOf(newActivity())) }

        assertEquals(ImportBatchItemStatus.SUCCESS, reload(created).status)
        assertEquals(ImportBatchItemStatus.UPGRADED, reload(upgraded).status)
        assertEquals(ImportBatchItemStatus.MIXED, reload(mixed).status)
        assertEquals(ImportBatchItemStatus.DUPLICATE, reload(duplicate).status)
        val after = reload(batch)
        assertEquals(4, after.processedFiles)
        assertEquals(2, after.successfulFiles, "SUCCESS and MIXED")
        assertEquals(2, after.upgradedFiles, "UPGRADED and MIXED")
        assertEquals(1, after.duplicateFiles)
        assertEquals(0, after.failedFiles)
    }

    /** The Activities each item of [batch] links to, by item id. */
    private fun linksOf(batch: ImportBatch): Map<Long, Set<Long>> =
        importBatchItemRepository.findByBatchId(batch.id!!).associate { item -> item.id!! to item.activities.map { it.id!! }.toSet() }

    @Test
    fun `an item links every Activity its file resolved to - created, upgraded and duplicate`() {
        val batch = batch(ImportBatchStatus.PROCESSING, *Array(3) { ImportBatchItemStatus.PROCESSING })
        val (created, mixed, duplicate) = itemsOf(batch)
        val (new, alsoKnown) = newActivity() to newActivity()
        val (mixedNew, mixedUpgraded, mixedKnown) = Triple(newActivity(), newActivity(), newActivity())
        val (firstKnown, secondKnown) = newActivity() to newActivity()

        importAndRecord(created) { ImportOutcome.Created(created = listOf(new), duplicates = listOf(alsoKnown)) }
        importAndRecord(mixed) {
            ImportOutcome.Mixed(
                created = listOf(mixedNew),
                upgrades = listOf(ActivityUpgrade(mixedUpgraded, lapsBackfilled)),
                duplicates = listOf(mixedKnown),
            )
        }
        importAndRecord(duplicate) { ImportOutcome.Duplicate(duplicates = listOf(firstKnown, secondKnown)) }

        val links = linksOf(batch)
        assertEquals(setOf(new.id, alsoKnown.id), links[created.id])
        assertEquals(setOf(mixedNew.id, mixedUpgraded.id, mixedKnown.id), links[mixed.id])
        assertEquals(setOf(firstKnown.id, secondKnown.id), links[duplicate.id])
    }

    @Test
    fun `typed upgrades are kept only for UPGRADED and MIXED items, and the error message only for FAILED ones`() {
        val batch = batch(ImportBatchStatus.PROCESSING, *Array(4) { ImportBatchItemStatus.PROCESSING })
        val (upgraded, mixed, duplicate, failed) = itemsOf(batch)

        importAndRecord(
            upgraded,
        ) { ImportOutcome.Upgraded(upgrades = listOf(ActivityUpgrade(newActivity(), detailsUpgraded))) }
        importAndRecord(mixed) {
            ImportOutcome.Mixed(created = listOf(newActivity()), upgrades = listOf(ActivityUpgrade(newActivity(), lapsBackfilled)))
        }
        importAndRecord(duplicate) { ImportOutcome.Duplicate(duplicates = listOf(newActivity())) }
        lifecycle.fail(failed.id!!, "FIT file failed integrity check")

        assertEquals(null to listOf(detailsUpgradedChange), messagesOf(upgraded))
        assertEquals(null to listOf(lapsBackfilledChange), messagesOf(mixed))
        assertEquals(null to null, messagesOf(duplicate))
        assertEquals("FIT file failed integrity check" to null, messagesOf(failed))
        listOf(upgraded, mixed, duplicate, failed).forEach { assertEquals(null, reload(it).upgradeMessage, "no legacy text is written") }
    }

    @Test
    fun `a second record of the same item is rejected and rolls its transaction back`() {
        val item = itemsOf(batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PROCESSING)).single()
        importAndRecord(item) { ImportOutcome.Duplicate(duplicates = listOf(newActivity())) }

        assertThrows<ItemNoLongerRecordable> {
            importAndRecord(item) { ImportOutcome.Created(created = listOf(newActivity())) }
        }

        assertEquals(1, activityRepository.findByUserId(athlete.id!!).size, "the second import's Activity rolled back")
        assertEquals(ImportBatchItemStatus.DUPLICATE, reload(item).status)
        assertEquals(1, reload(item.batch).processedFiles)
    }

    @Test
    fun `a record after the batch was cancelled rolls the Activities back and leaves the item Stranded`() {
        val batch = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PROCESSING)
        val item = itemsOf(batch).single()
        assertTrue(lifecycle.cancel(batch.id!!, athlete.id!!))

        assertThrows<ItemNoLongerRecordable> {
            importAndRecord(item) { ImportOutcome.Created(created = listOf(newActivity())) }
        }

        assertEquals(0, activityRepository.findByUserId(athlete.id!!).size)
        assertEquals(ImportBatchItemStatus.PROCESSING, reload(item).status)
        val after = reload(batch)
        assertEquals(ImportBatchStatus.CANCELLED, after.status)
        assertEquals(0, after.processedFiles)
    }

    @Test
    fun `a cancel issued while an item transaction holds the batch waits, and the item counts toward the Cancelled batch`() {
        val batch = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PROCESSING)
        val item = itemsOf(batch).single()
        val recorded = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val importing =
            pool.submit {
                transactionTemplate.executeWithoutResult {
                    lifecycle.recordOutcome(item.id!!, ImportOutcome.Created(created = listOf(newActivity())))
                    recorded.countDown()
                    release.await(10, TimeUnit.SECONDS) // hold the batch row until the cancel is waiting on it
                }
            }
        assertTrue(recorded.await(10, TimeUnit.SECONDS))
        val cancelling = pool.submit<Boolean> { lifecycle.cancel(batch.id!!, athlete.id!!) }
        Thread.sleep(500)
        assertFalse(cancelling.isDone, "the cancel waits for the item's transaction")
        release.countDown()
        importing.get(10, TimeUnit.SECONDS)
        assertTrue(cancelling.get(10, TimeUnit.SECONDS))
        pool.shutdown()

        val after = reload(batch)
        assertEquals(ImportBatchStatus.CANCELLED, after.status)
        assertEquals(1, after.processedFiles)
        assertEquals(1, after.successfulFiles)
        assertEquals(ImportBatchItemStatus.SUCCESS, reload(item).status)
        assertEquals(1, activityRepository.findByUserId(athlete.id!!).size)
    }

    @Test
    fun `fail records a PENDING or PROCESSING item as FAILED with its reason`() {
        val batch = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PENDING, ImportBatchItemStatus.PROCESSING)
        val (queued, inProgress) = itemsOf(batch)

        assertTrue(lifecycle.fail(queued.id!!, "Exceeded maximum delivery attempts (3)"))
        assertTrue(lifecycle.fail(inProgress.id!!, "FIT file failed integrity check"))

        assertEquals(ImportBatchItemStatus.FAILED, reload(queued).status)
        assertEquals("FIT file failed integrity check", reload(inProgress).errorMessage)
        val after = reload(batch)
        assertEquals(2, after.processedFiles)
        assertEquals(2, after.failedFiles)
    }

    @Test
    fun `fail records nothing for a Stranded item or an item already recorded`() {
        val cancelled = itemsOf(batch(ImportBatchStatus.CANCELLED, ImportBatchItemStatus.PROCESSING)).single()
        val interrupted = itemsOf(batch(ImportBatchStatus.INTERRUPTED, ImportBatchItemStatus.PENDING)).single()
        val recorded = itemsOf(batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.SUCCESS)).single()

        assertFalse(lifecycle.fail(cancelled.id!!, "boom"))
        assertFalse(lifecycle.fail(interrupted.id!!, "boom"))
        assertFalse(lifecycle.fail(recorded.id!!, "boom"))

        assertEquals(ImportBatchItemStatus.PROCESSING, reload(cancelled).status)
        assertEquals(ImportBatchItemStatus.PENDING, reload(interrupted).status)
        assertEquals(ImportBatchItemStatus.SUCCESS, reload(recorded).status)
        assertEquals(0, reload(cancelled.batch).failedFiles)
        assertEquals(0, reload(interrupted.batch).failedFiles)
        assertEquals(0, reload(recorded.batch).failedFiles)
    }

    @Test
    fun `recordOutcome refuses to run outside the import's transaction`() {
        val item = itemsOf(batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PROCESSING)).single()

        assertThrows<IllegalTransactionStateException> {
            lifecycle.recordOutcome(item.id!!, ImportOutcome.Duplicate(duplicates = listOf(newActivity())))
        }
    }

    @Test
    fun `cancelling a finished batch, or another athlete's batch, changes nothing`() {
        val finished = batch(ImportBatchStatus.COMPLETED, ImportBatchItemStatus.SUCCESS)
        val running = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PENDING)
        val stranger = userRepository.save(User(email = "stranger@example.com", passwordHash = "x", username = "stranger"))

        assertFalse(lifecycle.cancel(finished.id!!, athlete.id!!))
        assertFalse(lifecycle.cancel(running.id!!, stranger.id!!))

        assertEquals(ImportBatchStatus.COMPLETED, statusOf(finished))
        assertEquals(ImportBatchStatus.PROCESSING, statusOf(running))
    }

    /** A finished batch with one item of every recorded status, including a failure with no reason. */
    private fun finishedBatchOfEveryOutcome(): ImportBatch {
        val batch = batch(ImportBatchStatus.PROCESSING, *Array(6) { ImportBatchItemStatus.PROCESSING })
        val (success, upgraded, mixed, duplicate, failed) = itemsOf(batch)
        val unexplained = itemsOf(batch)[5]
        importAndRecord(success) { ImportOutcome.Created(created = listOf(newActivity())) }
        importAndRecord(
            upgraded,
        ) { ImportOutcome.Upgraded(upgrades = listOf(ActivityUpgrade(newActivity(), detailsUpgraded))) }
        importAndRecord(mixed) {
            ImportOutcome.Mixed(created = listOf(newActivity()), upgrades = listOf(ActivityUpgrade(newActivity(), lapsBackfilled)))
        }
        importAndRecord(duplicate) { ImportOutcome.Duplicate(duplicates = listOf(newActivity())) }
        lifecycle.fail(failed.id!!, "FIT file failed integrity check")
        lifecycle.fail(unexplained.id!!, null)
        lifecycle.finalise(batch.id!!)
        return batch
    }

    @Test
    fun `the polled summary and the history entry of a batch are the same`() {
        val batch = finishedBatchOfEveryOutcome()

        val summary = lifecycle.summary(batch.id!!, athlete.id!!)!!

        assertEquals(listOf(summary), lifecycle.history(athlete.id!!))
        assertEquals(ImportBatchStatus.COMPLETED, summary.status)
        assertEquals(
            listOf(6, 2, 2, 1, 2),
            with(summary) { listOf(processedFiles, successfulFiles, upgradedFiles, duplicateFiles, failedFiles) },
        )
        assertEquals(
            listOf(
                ImportBatchItemErrorDTO("file-4.fit", "FIT file failed integrity check"),
                ImportBatchItemErrorDTO("file-5.fit", "Processing failed"),
            ),
            summary.itemErrors,
        )
        assertEquals(
            listOf(
                ImportBatchItemUpgradeDTO("file-1.fit", listOf(UpgradedChangeDTO(ParserVersionUpgradedDetailsDTO(1, 2), laps = null))),
                ImportBatchItemUpgradeDTO("file-2.fit", listOf(UpgradedChangeDTO(details = null, laps = BackfilledLapsDTO(3)))),
            ),
            summary.itemUpgrades,
        )
    }

    @Test
    fun `a summary of another athlete's batch is not found`() {
        val batch = batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PENDING)
        val stranger = userRepository.save(User(email = "stranger@example.com", passwordHash = "x", username = "stranger"))

        assertEquals(null, lifecycle.summary(batch.id!!, stranger.id!!))
        assertEquals(emptyList<ImportBatchSummaryDTO>(), lifecycle.history(stranger.id!!))
    }

    @Test
    fun `history issues the same number of queries however many batches the athlete has`() {
        finishedBatchOfEveryOutcome()
        val oneBatch = statementsDuring { lifecycle.history(athlete.id!!) }
        repeat(3) { finishedBatchOfEveryOutcome() }

        val fourBatches = statementsDuring { assertEquals(4, lifecycle.history(athlete.id!!).size) }

        assertEquals(oneBatch, fourBatches)
    }

    private fun statementsDuring(block: () -> Unit): Long {
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.clear()
        block()
        return statistics.prepareStatementCount
    }

    @Test
    fun `claim moves a PENDING item to PROCESSING and hands over its owner and staged file`() {
        val item = itemsOf(batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PENDING)).single()

        val claim = lifecycle.claim(item.id!!, redelivered = false)

        assertEquals(ItemClaim.Claimed(ownerId = athlete.id!!, stagedFilePath = item.stagedFilePath), claim)
        assertEquals(ImportBatchItemStatus.PROCESSING, reload(item).status)
    }

    @Test
    fun `a redelivered message takes over a PROCESSING item from a dead or slow worker`() {
        val item = itemsOf(batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PROCESSING)).single()

        assertEquals(ItemClaim.Claimed(athlete.id!!, item.stagedFilePath), lifecycle.claim(item.id!!, redelivered = true))
    }

    @Test
    fun `a first delivery of a PROCESSING item is a reconciler duplicate and is skipped`() {
        val item = itemsOf(batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PROCESSING)).single()

        assertEquals(ItemClaim.Skip(ItemClaim.SkipReason.OWNED_ELSEWHERE), lifecycle.claim(item.id!!, redelivered = false))
    }

    @Test
    fun `an item already recorded is skipped, however it is delivered`() {
        val item = itemsOf(batch(ImportBatchStatus.PROCESSING, ImportBatchItemStatus.DUPLICATE)).single()

        assertEquals(ItemClaim.Skip(ItemClaim.SkipReason.ALREADY_RECORDED), lifecycle.claim(item.id!!, redelivered = false))
        assertEquals(ItemClaim.Skip(ItemClaim.SkipReason.ALREADY_RECORDED), lifecycle.claim(item.id!!, redelivered = true))
    }

    @Test
    fun `an item of a Cancelled or Interrupted batch is skipped and left Stranded`() {
        val cancelled = itemsOf(batch(ImportBatchStatus.CANCELLED, ImportBatchItemStatus.PENDING)).single()
        val interrupted = itemsOf(batch(ImportBatchStatus.INTERRUPTED, ImportBatchItemStatus.PROCESSING)).single()

        assertEquals(ItemClaim.Skip(ItemClaim.SkipReason.STRANDED), lifecycle.claim(cancelled.id!!, redelivered = false))
        assertEquals(ItemClaim.Skip(ItemClaim.SkipReason.STRANDED), lifecycle.claim(interrupted.id!!, redelivered = true))
        assertEquals(ImportBatchItemStatus.PENDING, reload(cancelled).status)
    }

    @Test
    fun `stalled PENDING items of active batches are handed out for redispatch once per threshold`() {
        val longAgo = Instant.now().minusSeconds(120)

        fun batchCreated(
            at: Instant,
            status: ImportBatchStatus,
            itemStatus: ImportBatchItemStatus,
        ): ImportBatchItem {
            val batch = importBatchRepository.save(ImportBatch(user = athlete, status = status, totalFiles = 1, createdAt = at))
            return importBatchItemRepository.save(
                ImportBatchItem(batch = batch, originalFilename = "f.fit", stagedFilePath = "/staged/${batch.id}.fit", status = itemStatus),
            )
        }
        val stalled = batchCreated(longAgo, ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PENDING)
        batchCreated(Instant.now(), ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PENDING) // fresh
        batchCreated(longAgo, ImportBatchStatus.PROCESSING, ImportBatchItemStatus.PROCESSING) // owned by a worker
        batchCreated(longAgo, ImportBatchStatus.CANCELLED, ImportBatchItemStatus.PENDING) // Stranded

        val tickets = lifecycle.claimStalledForRedispatch(Duration.ofSeconds(60))

        assertEquals(listOf(RedispatchTicket(stalled.batch.id!!, stalled.id!!, stalled.stagedFilePath)), tickets)
        assertEquals(emptyList<RedispatchTicket>(), lifecycle.claimStalledForRedispatch(Duration.ofSeconds(60)))
    }
}
