package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **Best Effort** backfill runs one transaction per page (issue #99): each page commits on its own, so the
 * backfill holds one page's Personal Record locks at a time (ADR-0006), and a failing page leaves earlier pages
 * committed. Pages are observed by spying on the Best Effort refresh, which the backfill calls once per page.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class BestEffortBackfillPagingTest {
    @Autowired
    private lateinit var bestEffortBackfillService: BestEffortBackfillService

    @MockitoSpyBean
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
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    private lateinit var runner: User
    private lateinit var fixtures: BestEffortFixtures
    private lateinit var pool: ExecutorService
    private lateinit var locks: PostgresLockProbe

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "paging_runner@example.com", passwordHash = "x", username = "paging_runner"))
        fixtures =
            BestEffortFixtures(
                activityRepository,
                activityDetailsRepository,
                personalRecordRepository,
                bestEffortRefresh,
                transactionTemplate,
                defaultOwner = runner,
            )
        pool = Executors.newFixedThreadPool(2)
        locks = PostgresLockProbe(jdbcClient)
    }

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    /**
     * The Mockito spy behind the refresh's transactional proxy. Stubbing goes through it directly: a call on the
     * proxy outside a transaction fails the refresh's MANDATORY propagation before Mockito sees it.
     */
    private val refreshSpy: BestEffortRefresh get() = AopTestUtils.getUltimateTargetObject(bestEffortRefresh)

    /**
     * Runs [answer] in place of the backfill's [page]th call to refresh a page of candidates (1-based); [answer]
     * calls `refresh()` to run the real refresh. Every other page refreshes as usual.
     */
    private fun onPage(
        page: Int,
        answer: (refresh: () -> Any?) -> Any?,
    ) {
        val calls = AtomicInteger()
        doAnswer { invocation ->
            if (calls.incrementAndGet() == page) answer { invocation.callRealMethod() } else invocation.callRealMethod()
        }.whenever(refreshSpy).refresh(any<Collection<Activity>>())
    }

    @Test
    fun `the backfill releases a page's Personal Record locks before it starts the next page`() {
        // Already holds Best Efforts, so it is no candidate; refreshing it recalibrates (runner, RUN).
        val imported = fixtures.importActivity("Imported", "2025-04-01T08:00:00Z", steadyDetails(5000f, 1500))
        val first = fixtures.storeActivity("Candidate A", "2025-05-01T08:00:00Z", steadyDetails(5000f, 1400))
        val second = fixtures.storeActivity("Candidate B", "2025-06-01T08:00:00Z", steadyDetails(5000f, 1300))
        check(first.id!! < second.id!!)

        // Page 2 stops before it refreshes anything, reporting its backend, until the test lets it go on.
        val backfillPid = CompletableFuture<Int>()
        val betweenPages = CountDownLatch(1)
        val resume = CountDownLatch(1)
        onPage(2) { refresh ->
            backfillPid.complete(locks.currentBackendPid())
            betweenPages.countDown()
            check(resume.await(30, TimeUnit.SECONDS)) { "page 2 was never resumed" }
            refresh()
        }

        val backfill = pool.submit<BackfillResult> { bestEffortBackfillService.backfillForUser(runner.id!!, pageSize = 1) }
        try {
            assertTrue(betweenPages.await(30, TimeUnit.SECONDS), "the backfill never reached page 2")
            val pid = backfillPid.get()

            // Page 1 has committed, so the backfill holds none of its locks and another writer for (runner, RUN)
            // commits at once. Were both pages one transaction, this refresh would wait for the whole backfill.
            assertEquals(0, locks.advisoryLockCounts(pid).held, "advisory locks held by the backfill between pages")
            pool
                .submit<List<ActivityBestEffort>> { fixtures.inTransaction { bestEffortRefresh.refresh(fixtures.reload(imported)) } }
                .get(10, TimeUnit.SECONDS)

            // Page 2 takes the lock afresh: with it held elsewhere, page 2 waits, then completes once it is free.
            fixtures.inTransaction {
                bestEffortRefresh.refresh(fixtures.reload(imported))
                resume.countDown()
                locks.awaitUntil(pid, "page 2 waiting on a lock") { locks.isWaitingOnLock(pid) }
            }
        } finally {
            resume.countDown()
        }

        assertEquals(BackfillResult(activitiesProcessed = 2, bestEffortsCreated = 8), backfill.get(30, TimeUnit.SECONDS))
        assertEquals(second.id, fixtures.runningRecords().getValue(BenchmarkDistance.FIVE_KILOMETERS).first)
    }

    @Test
    fun `a failing page rolls back on its own, keeps earlier pages, and a rerun completes the rest`() {
        val first = fixtures.storeActivity("Page 1", "2025-05-01T08:00:00Z", steadyDetails(5000f, 1500))
        val second = fixtures.storeActivity("Page 2", "2025-06-01T08:00:00Z", steadyDetails(5000f, 1400))
        val third = fixtures.storeActivity("Page 3", "2025-07-01T08:00:00Z", steadyDetails(5000f, 1300))
        // Page 2 fails after its refresh has written its Best Efforts and Personal Records.
        onPage(2) { refresh ->
            refresh()
            throw IllegalStateException("page 2 fails")
        }

        val failure = assertThrows<IllegalStateException> { bestEffortBackfillService.backfillForUser(runner.id!!, pageSize = 1) }
        assertEquals("page 2 fails", failure.message)

        // Page 1 is committed, with the Personal Records its Best Efforts earn; page 2 rolled back its writes, and
        // page 3 never ran.
        assertEquals(4, activityBestEffortRepository.findByActivityId(first.id!!).size)
        assertEquals(0, activityBestEffortRepository.findByActivityId(second.id!!).size)
        assertEquals(0, activityBestEffortRepository.findByActivityId(third.id!!).size)
        assertEquals(
            mapOf(
                BenchmarkDistance.FOUR_HUNDRED_METERS to (first.id!! to 120.0),
                BenchmarkDistance.ONE_KILOMETER to (first.id!! to 300.0),
                BenchmarkDistance.ONE_MILE to (first.id!! to 482.8032),
                BenchmarkDistance.FIVE_KILOMETERS to (first.id!! to 1500.0),
            ),
            fixtures.roundedRunningRecords(),
        )

        // With the failure gone, a rerun picks up where the failed run stopped.
        doCallRealMethod().whenever(refreshSpy).refresh(any<Collection<Activity>>())
        val rerun = bestEffortBackfillService.backfillForUser(runner.id!!, pageSize = 1)

        assertEquals(BackfillResult(activitiesProcessed = 2, bestEffortsCreated = 8), rerun)
        assertEquals(
            mapOf(
                BenchmarkDistance.FOUR_HUNDRED_METERS to (third.id!! to 104.0),
                BenchmarkDistance.ONE_KILOMETER to (third.id!! to 260.0),
                BenchmarkDistance.ONE_MILE to (third.id!! to 418.4294),
                BenchmarkDistance.FIVE_KILOMETERS to (third.id!! to 1300.0),
            ),
            fixtures.roundedRunningRecords(),
        )
    }

    @Test
    fun `after a full run, Personal Records equal those of a full recalibration`() {
        // Same pace over 5 km: the tie goes to the earlier start, though that Activity has the higher id.
        val laterStart = fixtures.storeActivity("Later Start", "2025-06-01T08:00:00Z", steadyDetails(5000f, 1500))
        val earlierStart = fixtures.storeActivity("Earlier Start", "2025-05-01T08:00:00Z", steadyDetails(5000f, 1500))
        val fastKilometer = fixtures.storeActivity("Fast 1k", "2025-07-01T08:00:00Z", steadyDetails(1000f, 240))
        val slowTen = fixtures.storeActivity("Slow 10k", "2025-08-01T08:00:00Z", steadyDetails(10000f, 3200))
        check(laterStart.id!! < earlierStart.id!!)

        val result = bestEffortBackfillService.backfillForUser(runner.id!!, pageSize = 1)

        assertEquals(BackfillResult(activitiesProcessed = 4, bestEffortsCreated = 15), result)
        val expected =
            mapOf(
                BenchmarkDistance.FOUR_HUNDRED_METERS to (fastKilometer.id!! to 96.0),
                BenchmarkDistance.ONE_KILOMETER to (fastKilometer.id!! to 240.0),
                BenchmarkDistance.ONE_MILE to (earlierStart.id!! to 482.8032),
                BenchmarkDistance.FIVE_KILOMETERS to (earlierStart.id!! to 1500.0),
                BenchmarkDistance.TEN_KILOMETERS to (slowTen.id!! to 3200.0),
            )
        assertEquals(expected, fixtures.roundedRunningRecords())

        // Refreshing an Activity that already holds Best Efforts recalibrates the athlete's running records in full.
        fixtures.inTransaction { bestEffortRefresh.refresh(fixtures.reload(laterStart)) }
        assertEquals(expected, fixtures.roundedRunningRecords())
    }
}
