package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityDetailsRepository
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivityService
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.ActivityUpdateRequestDTO
import com.jacobcraig.stridehearth.activities.DERIVED_DATA_VERSION
import com.jacobcraig.stridehearth.activities.DerivedDataRefresh
import com.jacobcraig.stridehearth.activities.PostgresLockProbe
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
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
import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The derived-data recompute worker: the **Backfill** of Activities without a derived-data row and the
 * **Recompute** of rows an older algorithm version wrote, one committed, row-locked transaction per page. Every
 * drain is scoped to this class's athletes, because other test classes share the database.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DerivedDataRecomputeWorkerTest {
    @Autowired
    private lateinit var worker: DerivedDataRecomputeWorker

    @MockitoSpyBean
    private lateinit var derivedDataRefresh: DerivedDataRefresh

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var runner: User
    private lateinit var otherRunner: User
    private lateinit var pool: ExecutorService
    private lateinit var locks: PostgresLockProbe

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "gap_backfill@example.com", passwordHash = "x", username = "gap_backfill"))
        otherRunner = userRepository.save(User(email = "gap_backfill2@example.com", passwordHash = "x", username = "gap_backfill2"))
        pool = Executors.newFixedThreadPool(1)
        locks = PostgresLockProbe(jdbcClient)
    }

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        doCallRealMethod().whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** The Mockito spy behind the refresh's transactional proxy, stubbed directly (see BestEffortBackfillPagingTest). */
    private val refreshSpy: DerivedDataRefresh get() = AopTestUtils.getUltimateTargetObject(derivedDataRefresh)

    private fun drain(
        pageSize: Int = DerivedDataRecomputeWorker.PAGE_SIZE,
        maxPages: Int = Int.MAX_VALUE,
    ) = worker.drain(pageSize, maxPages, onlyUserIds = listOf(runner.id!!, otherRunner.id!!))

    private fun climb(n: Int) =
        FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + it * 3.0 / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { 3.0f },
            distance = Array(n) { it * 3.0f },
            altitude = Array(n) { 100.0f + 0.05f * it * 3.0f },
        )

    /**
     * An Activity stored with Activity Details (unless [withDetails] is false) but, like one imported before derived
     * data existed, no derived-data row.
     */
    private fun historical(
        sport: ActivitySport = ActivitySport.RUN,
        owner: User = runner,
        n: Int = 120,
        withDetails: Boolean = true,
    ): Activity {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Historical",
                    sport = sport,
                    startedAt = Instant.parse("2025-05-01T08:00:00Z"),
                    distance = 0.36,
                    timeTakenSeconds = n,
                    user = owner,
                    source = if (withDetails) ActivitySource.FIT_UPLOAD else ActivitySource.MANUAL,
                ),
            )
        if (withDetails) activityDetailsRepository.upsert(activity.id!!, climb(n), ParserVersion(1))
        return activity
    }

    private data class Row(
        val version: Int,
        val gapLength: Int?,
    )

    private fun row(activity: Activity): Row? =
        jdbcTemplate
            .query(
                "SELECT algorithm_version, cardinality(gap_speed) AS gap_length FROM activity_derived_metrics WHERE activity_id = ?",
                { rs, _ -> Row(rs.getInt("algorithm_version"), rs.getObject("gap_length") as Int?) },
                activity.id,
            ).singleOrNull()

    private fun gapLength(activity: Activity): Int? = row(activity)?.gapLength

    private fun computedAt(activity: Activity): Any? =
        jdbcTemplate.queryForObject("SELECT computed_at FROM activity_derived_metrics WHERE activity_id = ?", Any::class.java, activity.id)

    private fun storedGap(activity: Activity): List<Float?>? =
        jdbcTemplate.queryForObject(
            "SELECT gap_speed FROM activity_derived_metrics WHERE activity_id = ?",
            { rs, _ -> (rs.getArray(1)?.array as? Array<*>)?.map { (it as? Number)?.toFloat() } },
            activity.id,
        )

    private val current = Row(DERIVED_DATA_VERSION.toInt(), gapLength = null)

    @Test
    fun `writes a row for every athlete's Activities of every sport, and a rerun processes nothing`() {
        val run = historical()
        val walk = historical(sport = ActivitySport.WALK)
        val othersHike = historical(sport = ActivitySport.HIKE, owner = otherRunner)
        val ride = historical(sport = ActivitySport.RIDE)
        val manual = historical(withDetails = false)

        assertEquals(RecomputeResult(activitiesRecomputed = 5, activitiesFailed = 0), drain(pageSize = 2))

        assertEquals(current.copy(gapLength = 120), row(run))
        assertEquals(current.copy(gapLength = 120), row(walk))
        assertEquals(current.copy(gapLength = 120), row(othersHike))
        assertEquals(current, row(ride))
        assertEquals(current, row(manual))
        val all = listOf(run, walk, othersHike, ride, manual)
        val stamps = all.map(::computedAt)

        assertEquals(RecomputeResult(activitiesRecomputed = 0, activitiesFailed = 0), drain(pageSize = 2))
        assertEquals(stamps, all.map(::computedAt))
    }

    @Test
    fun `refills rows that were deleted`() {
        val run = historical()
        drain()
        val computed = storedGap(run)
        jdbcTemplate.update("DELETE FROM activity_derived_metrics WHERE activity_id = ?", run.id)

        assertEquals(RecomputeResult(1, 0), drain())

        assertEquals(computed, storedGap(run))
    }

    @Test
    fun `recomputes a row stored by an older algorithm version`() {
        val run = historical()
        jdbcTemplate.update(
            "INSERT INTO activity_derived_metrics (activity_id, algorithm_version, gap_speed) VALUES (?, 0, '{9}')",
            run.id,
        )

        assertEquals(RecomputeResult(1, 0), drain())

        assertEquals(current.copy(gapLength = 120), row(run))
    }

    @Test
    fun `stops after maxPages pages, and the next drain continues`() {
        val first = historical()
        val second = historical()

        assertEquals(RecomputeResult(1, 0), drain(pageSize = 1, maxPages = 1))
        assertEquals(120, gapLength(first))
        assertNull(row(second))

        assertEquals(RecomputeResult(1, 0), drain(pageSize = 1, maxPages = 1))
        assertEquals(120, gapLength(second))
    }

    @Test
    fun `a page waits for a concurrent Upgrade's row lock, then computes from the Activity Details it committed`() {
        val run = historical(n = 120)

        val drained: Future<RecomputeResult> =
            transactionTemplate.execute {
                // The lock an Upgrade's offer takes before it stores new Activity Details.
                activityDetailsRepository.lockActivity(run.id!!)
                val drain = pool.submit<RecomputeResult> { drain() }
                locks.awaitRowLockWaiter(excluding = setOf(locks.currentBackendPid()), ended = { drain.isDone })
                activityDetailsRepository.upsert(run.id!!, climb(200), ParserVersion(2))
                drain
            }!!

        assertEquals(RecomputeResult(1, 0), drained.get(30, TimeUnit.SECONDS))
        assertEquals(200, gapLength(run))
    }

    @Test
    fun `a page waits for a concurrent edit from run to ride and leaves the ride without GAP`() {
        val run = historical()

        val drained: Future<RecomputeResult> =
            transactionTemplate.execute {
                // The edit's refresh flushes its UPDATE, so this transaction now holds the Activity's row lock.
                activityService.updateActivity(
                    run.id!!,
                    runner.id!!,
                    ActivityUpdateRequestDTO(title = "A ride", sport = ActivitySport.RIDE),
                )
                val drain = pool.submit<RecomputeResult> { drain() }
                locks.awaitRowLockWaiter(excluding = setOf(locks.currentBackendPid()), ended = { drain.isDone })
                drain
            }!!

        assertEquals(RecomputeResult(1, 0), drained.get(30, TimeUnit.SECONDS))
        assertEquals(current, row(run))
    }

    @Test
    fun `a page skips an Activity deleted while it waited for the lock`() {
        val run = historical()

        val drained: Future<RecomputeResult> =
            transactionTemplate.execute {
                activityService.deleteActivity(run.id!!, runner.id!!)
                val drain = pool.submit<RecomputeResult> { drain() }
                locks.awaitRowLockWaiter(excluding = setOf(locks.currentBackendPid()), ended = { drain.isDone })
                drain
            }!!

        assertEquals(RecomputeResult(0, 0), drained.get(30, TimeUnit.SECONDS))
        assertFalse(activityRepository.existsById(run.id!!))
    }

    @Test
    fun `an Activity that fails is rolled back and skipped, the scan goes on, and the next drain tries it again`() {
        val first = historical()
        val poison = historical()
        val third = historical()
        // The poison Activity's refresh writes its row, then fails, so its transaction must roll the write back.
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (invocation.getArgument<Collection<Activity>>(0).any { it.id == poison.id }) throw IllegalStateException("poison")
            result
        }.whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())

        assertEquals(RecomputeResult(activitiesRecomputed = 2, activitiesFailed = 1), drain(pageSize = 2))

        assertEquals(120, gapLength(first))
        assertNull(row(poison))
        assertEquals(120, gapLength(third))

        assertEquals(RecomputeResult(activitiesRecomputed = 0, activitiesFailed = 1), drain(pageSize = 2))

        doCallRealMethod().whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())
        assertEquals(RecomputeResult(activitiesRecomputed = 1, activitiesFailed = 0), drain(pageSize = 2))
        assertEquals(120, gapLength(poison))
    }

    @Test
    fun `a page that loses a lock race is retried`() {
        val run = historical()
        val calls = AtomicInteger()
        doAnswer { invocation ->
            if (calls.incrementAndGet() == 1) throw CannotAcquireLockException("deadlock")
            invocation.callRealMethod()
        }.whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())

        assertEquals(RecomputeResult(1, 0), drain())

        assertEquals(2, calls.get())
        assertEquals(120, gapLength(run))
    }

    @Test
    fun `a page that keeps losing its lock race ends the drain, keeping earlier pages`() {
        val first = historical()
        val second = historical()
        val calls = AtomicInteger()
        doAnswer { invocation ->
            if (invocation.getArgument<Collection<Activity>>(0).any { it.id == second.id }) {
                calls.incrementAndGet()
                throw CannotAcquireLockException("deadlock")
            }
            invocation.callRealMethod()
        }.whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())

        assertThrows<CannotAcquireLockException> { drain(pageSize = 1) }

        assertEquals(DerivedDataRecomputeWorker.LOCK_ATTEMPTS, calls.get())
        assertEquals(120, gapLength(first))
        assertNull(row(second))
    }

    @Test
    fun `refuses to run inside a caller's transaction`() {
        assertThrows<IllegalTransactionStateException> {
            transactionTemplate.execute { worker.drain() }
        }
    }
}
