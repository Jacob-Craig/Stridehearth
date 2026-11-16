package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParsedActivity
import com.jacobcraig.stridehearth.system.ParsedActivityLap
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Concurrent **Personal Record** writes through the Best Effort refresh module (issues #74, #98 and #102, ADR-0006):
 * real transactions on separate threads, interleaved deterministically. A transaction is paused before commit with a
 * latch, and the other is known to be waiting once Postgres reports its backend as waiting on a lock.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class BestEffortRefreshConcurrencyTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var bestEffortRefresh: BestEffortRefresh

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var activityBestEffortRepository: ActivityBestEffortRepository

    @Autowired
    private lateinit var personalRecordRepository: PersonalRecordRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityHistory: ActivityHistory

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    private lateinit var runner: User
    private lateinit var otherRunner: User
    private lateinit var fixtures: BestEffortFixtures
    private lateinit var pool: ExecutorService
    private lateinit var locks: PostgresLockProbe

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "race_runner@example.com", passwordHash = "x", username = "race_runner"))
        otherRunner = userRepository.save(User(email = "race_other@example.com", passwordHash = "x", username = "race_other"))
        fixtures =
            BestEffortFixtures(
                activityRepository,
                activityDetailsRepository,
                personalRecordRepository,
                bestEffortRefresh,
                transactionTemplate,
                defaultOwner = runner,
            )
        pool = Executors.newFixedThreadPool(3)
        locks = PostgresLockProbe(jdbcClient)
        fitActivityParser.acceptParsedFixtures()
    }

    @AfterEach
    fun tearDown() {
        // Interrupts any transaction still paused before commit, so it rolls back.
        pool.shutdownNow()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    /**
     * A transaction running [work] on the pool. When [pauseBeforeCommit] is set, it stops after [work] until
     * [commit] releases it, keeping every lock [work] took.
     */
    private inner class PoolTransaction<T>(
        pauseBeforeCommit: Boolean,
        work: () -> T,
    ) {
        private val paused = CountDownLatch(1)
        private val release = CountDownLatch(if (pauseBeforeCommit) 1 else 0)
        private val pid = CompletableFuture<Int>()
        private val task: Future<T> =
            pool.submit<T> {
                transactionTemplate.execute {
                    pid.complete(locks.currentBackendPid())
                    work().also {
                        paused.countDown()
                        check(release.await(30, TimeUnit.SECONDS)) { "never released" }
                    }
                }
            }

        val isDone: Boolean get() = task.isDone

        /** The transaction's Postgres backend, so another connection can watch what it waits on. */
        val backendPid: Int get() = pid.get(30, TimeUnit.SECONDS)

        /**
         * Waits until [work] has finished and the transaction is paused, rethrowing the transaction's own failure
         * if it ends without getting there, so a failing step reports its cause instead of a timeout.
         */
        fun awaitPaused() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!paused.await(100, TimeUnit.MILLISECONDS)) {
                if (task.isDone) task.get()
                check(System.nanoTime() < deadline) { "the transaction never paused" }
            }
        }

        /** Lets the transaction commit, if paused, and waits for it to end. */
        fun commit(): T {
            release.countDown()
            return task.get(30, TimeUnit.SECONDS)
        }
    }

    /** Starts [work] in a transaction on the pool and returns once it is paused before commit. */
    private fun <T> pausedBeforeCommit(work: () -> T): PoolTransaction<T> =
        PoolTransaction(pauseBeforeCommit = true, work).also { it.awaitPaused() }

    /** Starts [work] in a transaction on the pool that commits as soon as [work] is done. */
    private fun <T> startTransaction(work: () -> T): PoolTransaction<T> = PoolTransaction(pauseBeforeCommit = false, work)

    /** Polls from a separate connection until [transaction] reaches [what], failing if it ends first. */
    private fun awaitUntil(
        transaction: PoolTransaction<*>,
        what: String,
        reached: (pid: Int) -> Boolean,
    ) {
        val pid = transaction.backendPid
        locks.awaitUntil(pid, what, ended = { transaction.isDone }) { reached(pid) }
    }

    /**
     * Waits until [transaction] waits on a heavyweight lock (a row lock or an advisory lock): the observable point
     * at which a concurrent transaction is blocked.
     */
    private fun awaitBlockedOnLock(transaction: PoolTransaction<*>) =
        awaitUntil(transaction, "waiting on a lock") { pid -> locks.isWaitingOnLock(pid) }

    /**
     * Waits until [transaction] waits on an advisory lock: the point at which a Personal Record writer is queued
     * behind another writer for the same athlete and sport.
     */
    private fun awaitWaitingOnAdvisoryLock(transaction: PoolTransaction<*>) =
        awaitUntil(transaction, "waiting on an advisory lock") { advisoryLockCounts(transaction).waiting > 0 }

    /** Takes the athlete's running Personal Record lock directly, as any other writer for (athlete, RUN) would. */
    private fun holdRunningPersonalRecordLock(): PoolTransaction<Unit> =
        pausedBeforeCommit {
            jdbcClient
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", personalRecordLockKey(runner.id!!, ActivitySport.RUN))
                .query { _, _ -> }
                .list()
            Unit
        }

    /**
     * Tries to lock [sql]'s rows from a separate connection without waiting. True when they are free, false when
     * another transaction holds a conflicting row lock on any of them.
     */
    private fun rowsAreUnlocked(
        sql: String,
        id: Long,
    ): Boolean =
        try {
            jdbcClient
                .sql("$sql FOR UPDATE NOWAIT")
                .param("id", id)
                .query { _, _ -> }
                .list()
            true
        } catch (e: DataAccessException) {
            // 55P03 lock_not_available: another transaction holds a conflicting lock on one of the rows.
            if ((e.mostSpecificCause as? SQLException)?.sqlState == "55P03") false else throw e
        }

    /** How many advisory locks [transaction] holds and how many it is still waiting for. */
    private fun advisoryLockCounts(transaction: PoolTransaction<*>): AdvisoryLockCounts = locks.advisoryLockCounts(transaction.backendPid)

    /**
     * Asserts that every running Benchmark Distance a steady 5 km [holder] covers has its Personal Record held by
     * [holder]'s own Best Effort, at its elapsed time for that distance, and that no other distance has one.
     */
    private fun assertAllRecordsHeldBy(
        holder: Activity,
        holderSecondsFor5k: Double,
    ) {
        val covered = BenchmarkDistance.entries.filter { it.sport == ActivitySport.RUN && it.distanceMeters <= 5000.0 }
        val holderEfforts = activityBestEffortRepository.findByActivityId(holder.id!!).associateBy { it.benchmarkDistance }
        val records = fixtures.runningRecordRows()
        assertEquals(covered.toSet(), records.keys)
        covered.forEach { distance ->
            val record = records.getValue(distance)
            assertEquals(holder.id, record.activityId, "holder of the $distance Personal Record")
            assertEquals(holderEfforts.getValue(distance).id, record.bestEffortId, "Best Effort of the $distance Personal Record")
            assertEquals(
                holderSecondsFor5k * distance.distanceMeters / 5000.0,
                record.elapsedTimeSeconds,
                0.01,
                "elapsed time of the $distance Personal Record",
            )
        }
    }

    /** Stores a new Activity N, faster over 5 km than any seeded one, and refreshes it as the import does. */
    private fun importFasterActivity(): Activity {
        val details = steadyDetails(5000f, 1100)
        val faster = fixtures.storeActivity("Faster N", "2026-04-15T08:00:00Z", details)
        bestEffortRefresh.refresh(faster, details)
        return faster
    }

    @Test
    fun `a full recalibration that waits on a concurrent import keeps the import's faster Personal Records`() {
        fixtures.importActivity("Holder X", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val slower = fixtures.importActivity("Slower Y", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1500))

        val import = pausedBeforeCommit { importFasterActivity() }

        // Y already has Best Efforts, so refreshing it runs a full recalibration. Y, not the holder X: changing X
        // would block on the Personal Record rows the import has locked before the recalibration even started.
        val recalibration = startTransaction { bestEffortRefresh.refresh(fixtures.reload(slower)) }
        awaitBlockedOnLock(recalibration)

        val faster = import.commit()
        recalibration.commit()

        assertAllRecordsHeldBy(faster, 1100.0)
    }

    @Test
    fun `an import that waits on a concurrent full recalibration still takes the faster Personal Records`() {
        fixtures.importActivity("Holder X", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val slower = fixtures.importActivity("Slower Y", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1500))

        val recalibration = pausedBeforeCommit { bestEffortRefresh.refresh(fixtures.reload(slower)) }

        val import = startTransaction { importFasterActivity() }
        awaitBlockedOnLock(import)

        recalibration.commit()
        val faster = import.commit()

        assertAllRecordsHeldBy(faster, 1100.0)
    }

    @Test
    fun `refreshes for different athletes do not wait on each other`() {
        val mine = fixtures.importActivity("Mine", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1500))
        val theirs = fixtures.importActivity("Theirs", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1400), owner = otherRunner)

        val open = pausedBeforeCommit { bestEffortRefresh.refresh(fixtures.reload(mine)) }

        // The first transaction is still open and holds its locks; the other athlete's refresh must still commit.
        startTransaction { bestEffortRefresh.refresh(fixtures.reload(theirs)) }.commit()
        assertFalse(open.isDone)

        open.commit()
    }

    @Test
    fun `a batch refresh covering several athletes takes their locks in athlete order`() {
        val mine = fixtures.importActivity("Mine", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1500))
        val theirs = fixtures.importActivity("Theirs", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1400), owner = otherRunner)
        check(runner.id!! < otherRunner.id!!)

        // Hold the later athlete's lock in an open transaction.
        val holder = pausedBeforeCommit { bestEffortRefresh.refresh(fixtures.reload(theirs)) }

        // The later athlete is passed first, yet the batch takes the earlier athlete's lock and only then waits.
        val batch = startTransaction { bestEffortRefresh.refresh(listOf(fixtures.reload(theirs), fixtures.reload(mine))) }
        awaitBlockedOnLock(batch)
        assertEquals(AdvisoryLockCounts(held = 1, waiting = 1), advisoryLockCounts(batch))

        holder.commit()
        batch.commit()
    }

    @Test
    fun `refreshing several Activities one by one in a single transaction does not wait on its own locks`() {
        val first = steadyDetails(5000f, 1500)
        val second = steadyDetails(5000f, 1300)

        // As a multi-activity import does: each Activity is refreshed separately within one transaction.
        val imported =
            startTransaction {
                val a = fixtures.storeActivity("First", "2026-04-10T08:00:00Z", first)
                bestEffortRefresh.refresh(a, first)
                val b = fixtures.storeActivity("Second", "2026-04-11T08:00:00Z", second)
                bestEffortRefresh.refresh(b, second)
                bestEffortRefresh.refresh(fixtures.reload(a))
                b
            }.commit()

        assertAllRecordsHeldBy(imported, 1300.0)
    }

    @Test
    fun `a delete and a full recalibration queued on the same lock both commit`() {
        val deletedFirst = fixtures.importActivity("Deleted X", "2026-04-08T08:00:00Z", steadyDetails(5000f, 1500))
        val holder = fixtures.importActivity("Holder Y", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val earlierTie = fixtures.importActivity("Earlier Z", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1300))
        fixtures.importActivity("Later W", "2026-04-14T08:00:00Z", steadyDetails(5000f, 1300))
        assertTrue(fixtures.runningRecords().values.all { it.first == holder.id }, "Y holds every Personal Record")

        val lockHolder = holdRunningPersonalRecordLock()

        // X already has Best Efforts, so deleting it runs a full recalibration for (athlete, RUN).
        val recalibration = startTransaction { activityService.deleteActivity(deletedFirst.id!!, runner.id!!) }
        awaitWaitingOnAdvisoryLock(recalibration)

        val delete = startTransaction { activityService.deleteActivity(holder.id!!, runner.id!!) }
        awaitWaitingOnAdvisoryLock(delete)

        lockHolder.commit()
        assertTrue(recalibration.commit())
        assertTrue(delete.commit())

        // A full recalibration over the remaining Best Efforts: Z and W tie, and the earlier Activity Z wins.
        assertAllRecordsHeldBy(earlierTie, 1300.0)
        assertTrue(fixtures.runningRecords().values.none { it.first == holder.id }, "no Personal Record refers to Y")
    }

    @Test
    fun `a delete waiting on the Personal Record lock holds no row locks`() {
        val holder = fixtures.importActivity("Holder Y", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val holderId = holder.id!!

        val lockHolder = holdRunningPersonalRecordLock()
        val delete = startTransaction { activityService.deleteActivity(holderId, runner.id!!) }
        awaitWaitingOnAdvisoryLock(delete)

        assertTrue(rowsAreUnlocked("SELECT id FROM activity WHERE id = :id", holderId), "activity row is unlocked")
        assertTrue(
            rowsAreUnlocked("SELECT id FROM activity_best_effort WHERE activity_id = :id", holderId),
            "Best Effort rows are unlocked",
        )
        assertTrue(
            rowsAreUnlocked("SELECT id FROM user_personal_record WHERE activity_id = :id", holderId),
            "Personal Record rows are unlocked",
        )

        lockHolder.commit()
        assertTrue(delete.commit())
        assertTrue(fixtures.runningRecords().isEmpty())
    }

    /**
     * Starts [reclassify] against a held (athlete, RUN) lock and asserts that, while it waits on that lock, it
     * holds no lock on the Activity's row: its Activity History Entry is recorded after the refresh (ADR-0006).
     */
    private fun assertReclassifyWaitingOnLockLeavesActivityRowUnlocked(
        activityId: Long,
        reclassify: () -> Any?,
    ) {
        val lockHolder = holdRunningPersonalRecordLock()
        val edit = startTransaction(reclassify)
        awaitWaitingOnAdvisoryLock(edit)

        assertTrue(rowsAreUnlocked("SELECT id FROM activity WHERE id = :id", activityId), "activity row is unlocked")

        lockHolder.commit()
        edit.commit()
        assertTrue(fixtures.runningRecords().isEmpty(), "the reclassified Activity holds no running Personal Records")
    }

    @Test
    fun `an edit reclassifying an Activity holds no row lock while it waits on the Personal Record lock`() {
        val holder = fixtures.importActivity("Holder Y", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val holderId = holder.id!!

        assertReclassifyWaitingOnLockLeavesActivityRowUnlocked(holderId) {
            activityService.updateActivity(
                holderId,
                runner.id!!,
                ActivityUpdateRequestDTO(title = "Now a ride", sport = ActivitySport.RIDE),
            )
        }
        assertEquals(1, activityHistory.entriesFor(holderId).size, "the edit's Activity History Entry is recorded")
    }

    @Test
    fun `a batch edit reclassifying an Activity holds no row lock while it waits on the Personal Record lock`() {
        val holder = fixtures.importActivity("Holder Y", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val holderId = holder.id!!

        assertReclassifyWaitingOnLockLeavesActivityRowUnlocked(holderId) {
            activityService.batchUpdateActivities(listOf(holderId), runner.id!!, BatchMetadataChange.of(null, ActivitySport.RIDE)!!)
        }
        assertEquals(1, activityHistory.entriesFor(holderId).size, "the batch edit's Activity History Entry is recorded")
    }

    /**
     * Activity Y's file, with one Lap and details covering 5 km in [seconds], decoded by [parserVersion]. Every
     * version of the file has the same external id, so re-importing it finds Y.
     */
    private fun fileOfY(
        seconds: Int,
        parserVersion: ParserVersion,
    ): ParsedActivity {
        val startedAt = Instant.parse("2026-04-10T08:00:00Z")
        return ParsedActivity(
            sport = ActivitySport.RUN,
            title = "Upgraded Y",
            startedAt = startedAt,
            distance = 5.0,
            timeTakenSeconds = seconds,
            externalId = "lock-order-run",
            telemetry = steadyDetails(5000f, seconds),
            laps = listOf(ParsedActivityLap(1, startedAt, 5.0, seconds)),
            parserVersion = parserVersion,
        )
    }

    /** Imports Y with Activity Details from an older Parser Version, so that re-importing its file is an Upgrade. */
    private fun importYFromOlderParser(seconds: Int): Activity {
        val older = ParserVersion(FitActivityParser.PARSER_VERSION.value - 1)
        val outcome = activityImportService.importParsed(listOf(fileOfY(seconds, older)), runner.id!!)
        return assertIs<ImportOutcome.Created>(outcome).created.single()
    }

    /** Starts re-importing Y's file at the current Parser Version on the pool, outside any transaction, as an import must. */
    private fun startUpgradeOfY(seconds: Int): Future<ImportOutcome> =
        pool.submit<ImportOutcome> {
            activityImportService.importParsed(listOf(fileOfY(seconds, FitActivityParser.PARSER_VERSION)), runner.id!!)
        }

    /**
     * Waits until [import] waits on an advisory lock, finding its backend as the waiter other than [otherWaiters].
     * Rethrows the import's own failure if it ends first.
     */
    private fun awaitImportWaitingOnAdvisoryLock(
        import: Future<ImportOutcome>,
        otherWaiters: Set<Int> = emptySet(),
    ) {
        locks.awaitAdvisoryLockWaiter(otherWaiters, ended = { import.isDone.also { if (it) import.get() } })
    }

    @Test
    fun `an import Upgrade waiting on the Personal Record lock holds no row locks on its Activity`() {
        val upgraded = importYFromOlderParser(1500)
        val id = upgraded.id!!

        val lockHolder = holdRunningPersonalRecordLock()
        val import = startUpgradeOfY(1300)
        awaitImportWaitingOnAdvisoryLock(import)

        assertTrue(rowsAreUnlocked("SELECT id FROM activity WHERE id = :id", id), "activity row is unlocked")
        assertTrue(
            rowsAreUnlocked("SELECT activity_id FROM activity_details WHERE activity_id = :id", id),
            "Activity Details row is unlocked",
        )
        assertTrue(rowsAreUnlocked("SELECT id FROM activity_laps WHERE activity_id = :id", id), "Lap rows are unlocked")

        lockHolder.commit()
        val outcome = assertIs<ImportOutcome.Upgraded>(import.get(30, TimeUnit.SECONDS))
        assertEquals(listOf(id), outcome.upgrades.map { it.activity.id })
        assertAllRecordsHeldBy(upgraded, 1300.0)
    }

    @Test
    fun `an import Upgrade queued behind a delete of the same Activity imports the file as a new Activity`() {
        val deleted = importYFromOlderParser(1200)
        val earlierTie = fixtures.importActivity("Earlier Z", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1300))
        fixtures.importActivity("Later W", "2026-04-14T08:00:00Z", steadyDetails(5000f, 1300))
        assertTrue(fixtures.runningRecords().values.all { it.first == deleted.id }, "Y holds every Personal Record")

        val lockHolder = holdRunningPersonalRecordLock()
        val delete = startTransaction { activityService.deleteActivity(deleted.id!!, runner.id!!) }
        awaitWaitingOnAdvisoryLock(delete)
        val import = startUpgradeOfY(1400)
        awaitImportWaitingOnAdvisoryLock(import, otherWaiters = setOf(delete.backendPid))

        lockHolder.commit()
        assertTrue(delete.commit())
        val created = assertIs<ImportOutcome.Created>(import.get(30, TimeUnit.SECONDS)).created.single()

        assertNotEquals(deleted.id, created.id, "the file is imported as a new Activity")
        // A full recalibration over the remaining Best Efforts: Z and W tie, and the earlier Activity Z wins over
        // both W and the new, slower Activity.
        assertAllRecordsHeldBy(earlierTie, 1300.0)
    }
}
