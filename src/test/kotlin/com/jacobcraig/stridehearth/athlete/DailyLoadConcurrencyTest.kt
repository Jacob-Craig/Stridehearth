package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivityService
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.PostgresLockProbe
import com.jacobcraig.stridehearth.activities.ProtectedMetrics
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParsedActivity
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The daily rollup under concurrent writers for one athlete (#36, ADR-0016): concurrent imports, including a file
 * holding a Ride and a Run against a concurrent Run import, and a concurrent delete, never deadlock, and leave the
 * same Daily Loads and chart as the same changes made one after another.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DailyLoadConcurrencyTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var athleteThresholds: AthleteThresholds

    @Autowired
    private lateinit var dailyLoadRollup: DailyLoadRollup

    @Autowired
    private lateinit var athleteTimeZone: AthleteTimeZone

    @Autowired
    private lateinit var stateRepository: AthleteAnalyticsStateRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    private lateinit var concurrent: User
    private lateinit var sequential: User
    private lateinit var pool: ExecutorService
    private lateinit var locks: PostgresLockProbe

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        concurrent = userRepository.save(User(email = "load_race@example.com", passwordHash = "x", username = "load_race"))
        sequential = userRepository.save(User(email = "load_seq@example.com", passwordHash = "x", username = "load_seq"))
        listOf(concurrent, sequential).forEach { owner ->
            athleteThresholds.record(owner.id!!, ThresholdKind.RESTING_HR, LocalDate.parse("2026-01-01"), 50.0)
            athleteThresholds.record(owner.id!!, ThresholdKind.MAX_HR, LocalDate.parse("2026-01-01"), 190.0)
            athleteThresholds.record(owner.id!!, ThresholdKind.LTHR, LocalDate.parse("2026-01-01"), 170.0)
        }
        pool = Executors.newFixedThreadPool(8)
        locks = PostgresLockProbe(jdbcClient)
    }

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun telemetry(minutes: Int): FitTelemetry {
        val n = minutes * 60 + 1
        return FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + it * 3.0 / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { 3.0f },
            distance = Array(n) { it * 3.0f },
            altitude = Array(n) { 30.0f },
            heartRate = Array(n) { 170.toShort() },
        )
    }

    private fun session(
        startedAt: String,
        minutes: Int,
        sport: ActivitySport = ActivitySport.RUN,
    ): ParsedActivity =
        parsedActivity(
            sport = sport,
            title = "Race fixture",
            startedAt = Instant.parse(startedAt),
            distance = minutes * 0.18,
            timeTakenSeconds = minutes * 60,
            externalId = "race-$startedAt-$sport",
            telemetry = telemetry(minutes),
        )

    /** One file per entry: a multi-session Ride plus Run file first, then single Runs on days before and after it. */
    private val files: List<List<ParsedActivity>> =
        listOf(
            listOf(session("2026-04-10T07:00:00Z", 50, ActivitySport.RIDE), session("2026-04-10T09:00:00Z", 40)),
            listOf(session("2026-04-10T18:00:00Z", 30)),
        ) + listOf(2, 5, 14, 18, 22, 27).map { listOf(session("2026-04-%02dT08:00:00Z".format(it), 20 + 3 * it)) }

    /** Seeded first: the Activity a concurrent delete removes. */
    private val deleted = session("2026-04-12T08:00:00Z", 60)

    private fun deletedActivityId(owner: User): Long =
        activityRepository.findByUserIdAndSourceAndExternalId(owner.id!!, ActivitySource.FIT_UPLOAD, deleted.externalId!!)!!.id!!

    private fun snapshot(owner: User) =
        dailyLoadRollup.findDailyLoads(owner.id!!, LocalDate.MIN, LocalDate.MAX) to
            dailyLoadRollup.findMetrics(owner.id!!, LocalDate.MIN, LocalDate.MAX)

    private fun rebuilt(owner: User) =
        transactionTemplate.execute { status ->
            dailyLoadRollup.recomputeFrom(owner.id!!, from = null)
            snapshot(owner).also { status.setRollbackOnly() }
        }!!

    @Test
    fun `a concurrent Import Batch and delete give the same result as a sequential one`() {
        listOf(concurrent, sequential).forEach { activityImportService.importParsed(listOf(deleted), it.id!!) }

        // Sequential: one change after another.
        files.forEach { activityImportService.importParsed(it, sequential.id!!) }
        activityService.deleteActivity(deletedActivityId(sequential), sequential.id!!)

        // Concurrent: every file and the delete released at once.
        val start = CountDownLatch(1)
        val toDelete = deletedActivityId(concurrent)
        val tasks: List<Future<*>> =
            files.map { file ->
                pool.submit {
                    start.await()
                    activityImportService.importParsed(file, concurrent.id!!)
                }
            } +
                pool.submit {
                    start.await()
                    activityService.deleteActivity(toDelete, concurrent.id!!)
                }
        start.countDown()
        tasks.forEach { it.get(60, TimeUnit.SECONDS) }

        val result = snapshot(concurrent)
        assertEquals(snapshot(sequential), result)
        assertEquals(rebuilt(concurrent), result)
        assertEquals(LocalDate.parse("2026-04-02"), result.second.first().day)
        assertEquals(LocalDate.parse("2026-04-27"), result.second.last().day)
        assertEquals(26, result.second.size, "one chart row per day")
        assertEquals(3, result.first.single { it.day == LocalDate.parse("2026-04-10") }.activityCount)
    }

    @Test
    fun `an import waits on the rollup lock last, holding its other locks, and its rollup then sees the holder's commit`() {
        activityImportService.importParsed(listOf(session("2026-04-02T08:00:00Z", 60)), concurrent.id!!)
        val release = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val holderPid = java.util.concurrent.CompletableFuture<Int>()
        val holder =
            pool.submit {
                transactionTemplate.execute {
                    holderPid.complete(locks.currentBackendPid())
                    stateRepository.lockRollups(concurrent.id!!)
                    holding.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            }
        assertTrue(holding.await(30, TimeUnit.SECONDS))

        val import = pool.submit { activityImportService.importParsed(listOf(session("2026-04-05T08:00:00Z", 30)), concurrent.id!!) }
        val waiter = locks.awaitAdvisoryLockWaiter(excluding = setOf(holderPid.get()), ended = { import.isDone })

        val counts = locks.advisoryLockCounts(waiter)
        assertEquals(1, counts.waiting)
        assertTrue(counts.held >= 2, "the Personal Record and scoring locks are already held: $counts")
        assertFalse(import.isDone)

        release.countDown()
        holder.get(30, TimeUnit.SECONDS)
        import.get(30, TimeUnit.SECONDS)
        assertEquals(rebuilt(concurrent), snapshot(concurrent))
        assertEquals(2, snapshot(concurrent).first.size)
    }

    @Test
    fun `a manual Activity written during the first time-zone change of an athlete without Activities is rolled up in the new zone`() {
        // The change finds no Activities, so it leaves the rollups current rather than stale: the manual Activity, which
        // scores no Training Load and so takes no scoring lock to score, must still wait for it before rolling up.
        val release = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val holderPid = java.util.concurrent.CompletableFuture<Int>()
        val change =
            pool.submit {
                transactionTemplate.execute {
                    holderPid.complete(locks.currentBackendPid())
                    athleteTimeZone.set(concurrent.id!!, "Europe/London")
                    holding.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                }
            }
        assertTrue(holding.await(30, TimeUnit.SECONDS))

        val manual =
            pool.submit {
                activityService.createManualActivity(
                    concurrent.id!!,
                    "Manual",
                    ActivitySport.RUN,
                    ProtectedMetrics(Instant.parse("2026-07-10T23:30:00Z"), 5.0, 1800, null, null, null),
                )
            }
        locks.awaitAdvisoryLockWaiter(excluding = setOf(holderPid.get()), ended = { manual.isDone })

        release.countDown()
        change.get(30, TimeUnit.SECONDS)
        manual.get(30, TimeUnit.SECONDS)
        assertEquals(listOf(LocalDate.parse("2026-07-11")), snapshot(concurrent).first.map { it.day }, "23:30Z is the 11th in London")
        assertEquals(ROLLUP_VERSION, stateRepository.findRollupVersion(concurrent.id!!))
    }
}
