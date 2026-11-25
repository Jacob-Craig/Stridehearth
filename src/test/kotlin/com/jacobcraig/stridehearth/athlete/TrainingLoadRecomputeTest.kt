package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.DerivedDataRefresh
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Recomputing **Training Load** after an athlete's **Thresholds** or time zone change: the change writes a durable
 * request, and the recompute worker's [DerivedDataRecomputeWorker.drain] recomputes the athlete's Activities on or
 * after the affected date, then clears the request unless a newer one arrived meanwhile. Every drain is scoped to this
 * class's athletes, because other test classes share the database.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class TrainingLoadRecomputeTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @MockitoSpyBean
    private lateinit var derivedDataRefresh: DerivedDataRefresh

    @Autowired
    private lateinit var worker: DerivedDataRecomputeWorker

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var athleteThresholds: AthleteThresholds

    @Autowired
    private lateinit var athleteTimeZone: AthleteTimeZone

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var runner: User
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "load_recompute@example.com", passwordHash = "x", username = "load_recompute"))
        otherRunner = userRepository.save(User(email = "load_recompute2@example.com", passwordHash = "x", username = "load_recompute2"))
    }

    @AfterEach
    fun tearDown() {
        doCallRealMethod().whenever(refreshSpy).refreshThresholdDependent(any())
        doCallRealMethod().whenever(refreshSpy).refresh(any<Activity>(), anyOrNull(), anyOrNull(), anyOrNull())
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private val refreshSpy: DerivedDataRefresh get() = AopTestUtils.getUltimateTargetObject(derivedDataRefresh)

    private fun drain() = worker.drain(onlyUserIds = listOf(runner.id!!, otherRunner.id!!))

    private fun threshold(
        kind: ThresholdKind,
        value: Double,
        from: String = "2026-01-01",
        owner: User = runner,
    ) = athleteThresholds.record(owner.id!!, kind, LocalDate.parse(from), value)

    /** Resting 50, max 190, LTHR 170 from 2026-01-01: one hour at 170 bpm is an HRSS of 100. */
    private fun heartRateThresholds(owner: User = runner) {
        threshold(ThresholdKind.RESTING_HR, 50.0, owner = owner)
        threshold(ThresholdKind.MAX_HR, 190.0, owner = owner)
        threshold(ThresholdKind.LTHR, 170.0, owner = owner)
    }

    private fun hourAt170(): FitTelemetry {
        val n = 3601
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

    private fun import(
        startedAt: String,
        owner: User = runner,
    ): Activity {
        val externalId = "load-$startedAt"
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "Load fixture",
                    startedAt = Instant.parse(startedAt),
                    distance = 10.8,
                    timeTakenSeconds = 3600,
                    externalId = externalId,
                    telemetry = hourAt170(),
                ),
            ),
            owner.id!!,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(owner.id!!, ActivitySource.FIT_UPLOAD, externalId)!!
    }

    private fun load(activity: Activity): Pair<Double?, String?> =
        jdbcTemplate.queryForObject(
            "SELECT training_load, training_load_unavailable_reason FROM activity_derived_metrics WHERE activity_id = ?",
            { rs, _ -> (rs.getObject(1) as Double?) to rs.getString(2) },
            activity.id,
        )!!

    private fun computedAt(activity: Activity): Any? =
        jdbcTemplate.queryForObject("SELECT computed_at FROM activity_derived_metrics WHERE activity_id = ?", Any::class.java, activity.id)

    private data class Request(
        val from: LocalDate?,
        val pending: Boolean,
    )

    private fun request(owner: User = runner): Request? =
        jdbcTemplate
            .query(
                "SELECT recompute_from, recompute_requested_at FROM athlete_analytics_state WHERE user_id = ?",
                { rs, _ -> Request(rs.getObject(1, LocalDate::class.java), rs.getObject(2) != null) },
                owner.id,
            ).singleOrNull()

    /** HRSS of an hour at 170 bpm with resting 50, max 190 and [lthr]. */
    private fun hrssAt170(lthr: Double): Double {
        val hrr = 120.0 / 140
        val hrrLt = (lthr - 50) / 140
        return 100 * hrr * exp(1.92 * hrr) / (hrrLt * exp(1.92 * hrrLt))
    }

    @Test
    fun `recording a Threshold writes a request from its date, or from the beginning for the earliest entry`() {
        threshold(ThresholdKind.LTHR, 170.0, from = "2026-01-01")
        assertEquals(Request(from = null, pending = true), request())

        drain()
        assertEquals(Request(from = null, pending = false), request())

        threshold(ThresholdKind.LTHR, 160.0, from = "2026-03-01")
        assertEquals(Request(LocalDate.parse("2026-03-01"), pending = true), request())

        threshold(ThresholdKind.LTHR, 165.0, from = "2026-02-01")
        assertEquals(Request(LocalDate.parse("2026-02-01"), pending = true), request(), "the earlier date wins")

        threshold(ThresholdKind.LTHR, 165.0, from = "2026-04-01")
        assertEquals(Request(LocalDate.parse("2026-02-01"), pending = true), request(), "a later date keeps the earlier one")

        threshold(ThresholdKind.MAX_HR, 190.0, from = "2026-05-01")
        assertEquals(Request(from = null, pending = true), request(), "a first entry of a kind recomputes everything")
    }

    @Test
    fun `changing the time zone requests a recompute of everything`() {
        athleteTimeZone.set(runner.id!!, "Europe/London")

        assertEquals(Request(from = null, pending = true), request())
    }

    @Test
    fun `a drain recomputes only the Activities on or after the affected date, then clears the request`() {
        heartRateThresholds()
        val february = import("2026-02-01T08:00:00Z")
        val april = import("2026-04-01T08:00:00Z")
        drain()
        val februaryComputedAt = computedAt(february)

        threshold(ThresholdKind.LTHR, 150.0, from = "2026-03-01")
        assertEquals(100.0, load(april).first!!, 0.01, "nothing changes before the drain")

        drain()

        assertEquals(100.0, load(february).first!!, 0.01)
        assertEquals(februaryComputedAt, computedAt(february), "an Activity before the affected date is not recomputed")
        assertEquals(hrssAt170(150.0), load(april).first!!, 0.01)
        assertEquals(false, request()!!.pending)
    }

    @Test
    fun `the earliest entry recomputes every Activity`() {
        val january = import("2025-01-01T08:00:00Z")
        val june = import("2026-06-01T08:00:00Z")
        assertEquals(null to "NO_THRESHOLDS", load(january))

        heartRateThresholds()
        drain()

        assertEquals(100.0, load(january).first!!, 0.01, "the earliest entry applies backwards")
        assertEquals(100.0, load(june).first!!, 0.01)
    }

    @Test
    fun `another athlete's Activities are untouched`() {
        heartRateThresholds(owner = otherRunner)
        val theirs = import("2026-02-01T08:00:00Z", owner = otherRunner)
        drain()
        val theirComputedAt = computedAt(theirs)

        heartRateThresholds()
        drain()

        assertEquals(theirComputedAt, computedAt(theirs))
        assertNull(request(otherRunner)!!.from)
        assertEquals(false, request(otherRunner)!!.pending)
    }

    @Test
    fun `a request arriving during the drain survives it`() {
        heartRateThresholds()
        val april = import("2026-04-01T08:00:00Z")
        drain()
        threshold(ThresholdKind.LTHR, 150.0, from = "2026-03-01")
        val requiresNew =
            TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
        doAnswer { invocation ->
            // Another LTHR change commits while the drain is recomputing with LTHR 150.
            requiresNew.execute { threshold(ThresholdKind.LTHR, 160.0, from = "2026-03-15") }
            invocation.callRealMethod()
        }.whenever(refreshSpy).refreshThresholdDependent(any())

        drain()

        assertEquals(Request(LocalDate.parse("2026-03-01"), pending = true), request(), "the newer request is kept")
        doCallRealMethod().whenever(refreshSpy).refreshThresholdDependent(any())

        drain()

        // LTHR 160 from 2026-03-15 applies on 1 April.
        assertEquals(hrssAt170(160.0), load(april).first!!, 0.01)
        assertEquals(false, request()!!.pending)
    }

    @Test
    fun `a request larger than one drain's pages resumes where the last drain stopped`() {
        val activities = listOf("2026-02-01", "2026-02-02", "2026-02-03").map { import("${it}T08:00:00Z") }
        heartRateThresholds()
        val scope = listOf(runner.id!!, otherRunner.id!!)

        assertEquals(RecomputeResult(0, 0, thresholdDependentRecomputed = 2), worker.drain(pageSize = 1, maxPages = 2, onlyUserIds = scope))
        assertEquals(true, request()!!.pending)
        assertEquals(listOf(100.0, 100.0), activities.take(2).map { Math.round(load(it).first!! * 100) / 100.0 })
        assertEquals(null to "NO_THRESHOLDS", load(activities[2]))

        assertEquals(RecomputeResult(0, 0, thresholdDependentRecomputed = 1), worker.drain(pageSize = 1, maxPages = 2, onlyUserIds = scope))
        assertEquals(100.0, load(activities[2]).first!!, 0.01)
        assertEquals(false, request()!!.pending)
    }

    @Test
    fun `a request is kept while any of its Activities failed, and served once their back-off has passed`() {
        heartRateThresholds()
        val april = import("2026-04-01T08:00:00Z")
        drain()
        threshold(ThresholdKind.LTHR, 150.0, from = "2026-03-01")
        val scope = listOf(runner.id!!, otherRunner.id!!)
        val failedAt = Instant.now()
        doThrow(IllegalStateException("cannot analyse")).whenever(refreshSpy).refreshThresholdDependent(any())

        assertEquals(RecomputeResult(0, 1, 0), worker.drain(onlyUserIds = scope, now = failedAt))
        assertEquals(true, request()!!.pending, "a request with a failed Activity stays pending")

        doCallRealMethod().whenever(refreshSpy).refreshThresholdDependent(any())
        assertEquals(RecomputeResult(0, 0, 0), worker.drain(onlyUserIds = scope, now = failedAt.plusSeconds(60)))
        assertEquals(true, request()!!.pending, "still pending while the Activity is backed off")
        assertEquals(100.0, load(april).first!!, 0.01)

        val afterBackOff = failedAt + DerivedDataRecomputeWorker.FAILED_RETRY_AFTER + java.time.Duration.ofMinutes(1)
        assertEquals(RecomputeResult(0, 0, 1), worker.drain(onlyUserIds = scope, now = afterBackOff))
        assertEquals(hrssAt170(150.0), load(april).first!!, 0.01)
        assertEquals(false, request()!!.pending)
    }

    @Test
    fun `a request held only by backed-off Activities is not logged again on every drain`(output: CapturedOutput) {
        heartRateThresholds()
        import("2026-04-01T08:00:00Z")
        drain()
        threshold(ThresholdKind.LTHR, 150.0, from = "2026-03-01")
        val scope = listOf(runner.id!!, otherRunner.id!!)
        val failedAt = Instant.now()
        val keptPending = "Training Load recompute request for athlete ${runner.id} kept pending"
        doThrow(IllegalStateException("cannot analyse")).whenever(refreshSpy).refreshThresholdDependent(any())

        worker.drain(onlyUserIds = scope, now = failedAt)
        assertEquals(1, output.all.split(keptPending).size - 1, "the drain that skipped the Activity says so")

        repeat(3) { worker.drain(onlyUserIds = scope, now = failedAt.plusSeconds(15L * (it + 1))) }
        assertEquals(true, request()!!.pending)
        assertEquals(1, output.all.split(keptPending).size - 1, "drains that only pass over the backed-off Activity stay quiet")
    }

    @Test
    fun `a row of an older version is left to the version Recompute rather than having its Training Load replaced`() {
        heartRateThresholds()
        val april = import("2026-04-01T08:00:00Z")
        drain()
        jdbcTemplate.update("UPDATE activity_derived_metrics SET algorithm_version = 2 WHERE activity_id = ?", april.id)
        threshold(ThresholdKind.LTHR, 150.0, from = "2026-03-01")

        assertEquals(RecomputeResult(activitiesRecomputed = 1, activitiesFailed = 0, thresholdDependentRecomputed = 0), drain())
        assertEquals(hrssAt170(150.0), load(april).first!!, 0.01)
    }

    @Test
    fun `a Threshold change waits for an Activity write that already read the Thresholds, so its request covers it`() {
        heartRateThresholds()
        drain()
        val scored = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { invocation ->
            invocation.callRealMethod()
            // The import has scored its Training Load against LTHR 170 and has not committed yet.
            scored.countDown()
            release.await(30, TimeUnit.SECONDS)
            null
        }.whenever(refreshSpy).refresh(any<Activity>(), anyOrNull(), anyOrNull(), anyOrNull())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val importing = executor.submit<Activity> { import("2026-04-01T08:00:00Z") }
            check(scored.await(30, TimeUnit.SECONDS)) { "the import never scored its Training Load" }
            val changing = executor.submit<ThresholdEntry> { threshold(ThresholdKind.LTHR, 150.0, from = "2026-03-01") }
            try {
                changing.get(2, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                // Expected: the change waits for the import to commit.
            }
            // A drain now, before the import commits, must not consume a request that misses the import.
            drain()
            release.countDown()
            val april = importing.get(30, TimeUnit.SECONDS)
            changing.get(30, TimeUnit.SECONDS)

            drain()

            assertEquals(hrssAt170(150.0), load(april).first!!, 0.01)
            assertEquals(false, request()!!.pending)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
