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
import jakarta.persistence.EntityManagerFactory
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
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.CannotCreateTransactionException
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

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

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    private lateinit var runner: User
    private lateinit var otherRunner: User
    private lateinit var pool: ExecutorService
    private lateinit var locks: PostgresLockProbe

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "gap_backfill@example.com", passwordHash = "x", username = "gap_backfill"))
        otherRunner = userRepository.save(User(email = "gap_backfill2@example.com", passwordHash = "x", username = "gap_backfill2"))
        // Their rollups count as current, so the Activities this class stores by SQL leave the rollup stage idle: these
        // tests count Activities, and DailyLoadRollupIntegrationTest covers the rollup stage.
        listOf(runner, otherRunner).forEach {
            jdbcTemplate.update("INSERT INTO athlete_analytics_state (user_id, rollup_version) VALUES (?, ?)", it.id, ROLLUP_VERSION)
        }
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
        now: Instant = Instant.now(),
    ) = worker.drain(pageSize, maxPages, onlyUserIds = listOf(runner.id!!, otherRunner.id!!), now = now)

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
    fun `backfills Run metrics for a run whose row was written before they existed, and a rerun processes nothing`() {
        val run = historical()
        // As #32 (version 1) left it: GAP stored, the Run metric columns NULL.
        jdbcTemplate.update(
            "INSERT INTO activity_derived_metrics (activity_id, algorithm_version, gap_speed) VALUES (?, 1, '{3}')",
            run.id,
        )

        assertEquals(RecomputeResult(1, 0), drain())

        val (version, movingSeconds, ngp) =
            jdbcTemplate.queryForObject(
                "SELECT algorithm_version, moving_seconds, ngp_mps FROM activity_derived_metrics WHERE activity_id = ?",
                { rs, _ -> Triple(rs.getInt(1), rs.getObject(2) as Int?, rs.getObject(3) as Double?) },
                run.id,
            )!!
        assertEquals(DERIVED_DATA_VERSION.toInt(), version)
        assertEquals(120, movingSeconds)
        assertNotNull(ngp)
        assertEquals(RecomputeResult(0, 0), drain())
    }

    @Test
    fun `fills the conditions of a row written before they existed from the stored temperature`() {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Warm ride",
                    sport = ActivitySport.RIDE,
                    startedAt = Instant.parse("2025-05-01T08:00:00Z"),
                    distance = 0.36,
                    timeTakenSeconds = 120,
                    user = runner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        activityDetailsRepository.upsert(activity.id!!, climb(120).copy(temperature = Array(120) { 31.toShort() }), ParserVersion(1))
        // As the version before conditions left it: everything else stored, the condition columns NULL.
        jdbcTemplate.update(
            "INSERT INTO activity_derived_metrics (activity_id, algorithm_version) VALUES (?, ?)",
            activity.id,
            DERIVED_DATA_VERSION - 1,
        )

        assertEquals(RecomputeResult(1, 0), drain())

        val (version, device, ambient) =
            jdbcTemplate.queryForObject(
                "SELECT algorithm_version, device_temperature_c, ambient_temperature_c FROM activity_derived_metrics WHERE activity_id = ?",
                { rs, _ -> Triple(rs.getInt(1), (rs.getObject(2) as Number?)?.toDouble(), (rs.getObject(3) as Number?)?.toDouble()) },
                activity.id,
            )!!
        assertEquals(DERIVED_DATA_VERSION.toInt(), version)
        assertEquals(31.0, device)
        assertEquals(25.0, ambient)
    }

    @Test
    fun `recomputes a run whose GAP and Run metrics were written before grade was smoothed`() {
        val run = historical()
        // As #33 (version 2) left it, with GAP from the ±10 m grade.
        jdbcTemplate.update(
            "INSERT INTO activity_derived_metrics (activity_id, algorithm_version, gap_speed, moving_seconds, ngp_mps) " +
                "VALUES (?, 2, '{3.2}', 1, 3.2)",
            run.id,
        )

        assertEquals(RecomputeResult(1, 0), drain())

        assertEquals(DERIVED_DATA_VERSION.toInt(), row(run)?.version)
        assertEquals(120, gapLength(run))
        assertEquals(RecomputeResult(0, 0), drain())
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
    fun `an Activity that fails is rolled back and skipped, the scan goes on, and it is tried again after the back-off`() {
        val first = historical()
        val poison = historical()
        val third = historical()
        val poisonAttempts = AtomicInteger()
        // The poison Activity's refresh writes its row, then fails, so its transaction must roll the write back.
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (invocation.getArgument<Collection<Activity>>(0).any { it.id == poison.id }) {
                poisonAttempts.incrementAndGet()
                throw IllegalStateException("poison")
            }
            result
        }.whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())
        val failedAt = Instant.now()

        assertEquals(RecomputeResult(activitiesRecomputed = 2, activitiesFailed = 1), drain(pageSize = 2, now = failedAt))

        assertEquals(120, gapLength(first))
        assertNull(row(poison))
        assertEquals(120, gapLength(third))
        // Once as part of its page, once on its own.
        assertEquals(2, poisonAttempts.get())

        assertEquals(
            RecomputeResult(activitiesRecomputed = 0, activitiesFailed = 0),
            drain(pageSize = 2, now = failedAt + DerivedDataRecomputeWorker.FAILED_RETRY_AFTER.minusSeconds(1)),
        )
        assertEquals(2, poisonAttempts.get())

        val retriedAt = failedAt + DerivedDataRecomputeWorker.FAILED_RETRY_AFTER
        assertEquals(RecomputeResult(activitiesRecomputed = 0, activitiesFailed = 1), drain(pageSize = 2, now = retriedAt))
        assertEquals(4, poisonAttempts.get())

        doCallRealMethod().whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())
        assertEquals(
            RecomputeResult(activitiesRecomputed = 0, activitiesFailed = 0),
            drain(pageSize = 2, now = retriedAt + DerivedDataRecomputeWorker.FAILED_RETRY_AFTER.minusSeconds(1)),
        )
        assertEquals(
            RecomputeResult(activitiesRecomputed = 1, activitiesFailed = 0),
            drain(pageSize = 2, now = retriedAt + DerivedDataRecomputeWorker.FAILED_RETRY_AFTER),
        )
        assertEquals(120, gapLength(poison))
    }

    @Test
    fun `an Activity backed off after failing does not hide the Activities after it on the same page`() {
        val poison = historical()
        doAnswer { invocation ->
            if (invocation.getArgument<Collection<Activity>>(0).any { it.id == poison.id }) throw IllegalStateException("poison")
            invocation.callRealMethod()
        }.whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())
        val failedAt = Instant.now()
        assertEquals(RecomputeResult(activitiesRecomputed = 0, activitiesFailed = 1), drain(pageSize = 1, now = failedAt))

        val later = historical()

        assertEquals(RecomputeResult(activitiesRecomputed = 1, activitiesFailed = 0), drain(pageSize = 1, now = failedAt))
        assertEquals(120, gapLength(later))
        assertNull(row(poison))
    }

    @Test
    fun `Activities that keep failing do not stop later drains reaching the Activities after them`() {
        val poisoned = List(3) { historical() }
        val healthy = historical()
        val poisonIds = poisoned.map { it.id }.toSet()
        doAnswer { invocation ->
            if (invocation.getArgument<Collection<Activity>>(0).any { it.id in poisonIds }) throw IllegalStateException("poison")
            invocation.callRealMethod()
        }.whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())

        // One run covers two pages: more failing Activities than that sit before the healthy one.
        assertEquals(RecomputeResult(activitiesRecomputed = 0, activitiesFailed = 2), drain(pageSize = 1, maxPages = 2))
        assertNull(row(healthy))

        // The next run resumes after them rather than retrying them: the third failure, then the healthy Activity.
        assertEquals(RecomputeResult(activitiesRecomputed = 1, activitiesFailed = 1), drain(pageSize = 1, maxPages = 2))
        assertEquals(120, gapLength(healthy))
    }

    @Test
    fun `Activities backed off after failing use up no pages, so more of them than a run's pages still let stale rollups be rebuilt`() {
        val poisoned = List(3) { historical() }
        val poisonIds = poisoned.map { it.id }.toSet()
        doAnswer { invocation ->
            if (invocation.getArgument<Collection<Activity>>(0).any { it.id in poisonIds }) throw IllegalStateException("poison")
            invocation.callRealMethod()
        }.whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())
        jdbcTemplate.update("UPDATE athlete_analytics_state SET rollup_version = 0 WHERE user_id = ?", runner.id)
        val failedAt = Instant.now()

        // Two pages a run, one Activity a page: the first run spends both pages on failures.
        assertEquals(RecomputeResult(activitiesRecomputed = 0, activitiesFailed = 2), drain(pageSize = 1, maxPages = 2, now = failedAt))

        // The second spends one on the third failure; wrapping around over the two backed off costs no page, so the
        // run finishes its pass and reaches the stale rollups.
        assertEquals(
            RecomputeResult(activitiesRecomputed = 0, activitiesFailed = 1, rollupsRebuilt = 1),
            drain(pageSize = 1, maxPages = 2, now = failedAt),
        )
        assertEquals(
            ROLLUP_VERSION,
            jdbcTemplate.queryForObject("SELECT rollup_version FROM athlete_analytics_state WHERE user_id = ?", Int::class.java, runner.id),
        )
    }

    @Test
    fun `a drain that resumes part-way wraps around once and stops where it started`() {
        val first = historical()
        val second = historical()
        val third = historical()

        assertEquals(RecomputeResult(1, 0), drain(pageSize = 1, maxPages = 1))
        assertEquals(120, gapLength(first))

        // Unbounded: from after the first to the end, then from the start up to where it began; nothing twice.
        jdbcTemplate.update("DELETE FROM activity_derived_metrics WHERE activity_id = ?", first.id)
        assertEquals(RecomputeResult(3, 0), drain())
        listOf(first, second, third).forEach { assertEquals(120, gapLength(it)) }
        assertEquals(RecomputeResult(0, 0), drain())
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
    fun `a database failure ends the drain without backing any Activity off, and the next drain resumes there`() {
        val first = historical()
        val second = historical()
        val now = Instant.now()
        val outages =
            listOf(
                QueryTimeoutException("statement timeout"),
                DataAccessResourceFailureException("connection lost"),
                CannotCreateTransactionException("no connection"),
            )

        for (outage in outages) {
            val attempts = AtomicInteger()
            doAnswer { invocation ->
                if (invocation.getArgument<Collection<Activity>>(0).any { it.id == second.id }) {
                    attempts.incrementAndGet()
                    throw outage
                }
                invocation.callRealMethod()
            }.whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())

            assertSame(outage, assertThrows<RuntimeException> { drain(pageSize = 1, now = now) })

            // Not retried one Activity at a time, as an Activity that cannot be analysed would be.
            assertEquals(1, attempts.get())
            assertNull(row(second))
        }
        assertEquals(120, gapLength(first))

        // Not backed off: once the database is back, the next drain (even at the same instant) computes it.
        doCallRealMethod().whenever(refreshSpy).refresh(any<Collection<Activity>>(), any())
        assertEquals(RecomputeResult(activitiesRecomputed = 1, activitiesFailed = 0), drain(pageSize = 1, now = now))
        assertEquals(120, gapLength(second))
    }

    @Test
    fun `updates are flushed in primary-key order, so a batch edit locks Activities in the order a page does`() {
        assertEquals("true", entityManagerFactory.properties["hibernate.order_updates"]?.toString())
    }

    @Test
    fun `refuses to run inside a caller's transaction`() {
        assertThrows<IllegalTransactionStateException> {
            transactionTemplate.execute { worker.drain() }
        }
    }
}
