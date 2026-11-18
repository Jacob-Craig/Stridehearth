package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivityService
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.ActivityUpdateRequestDTO
import com.jacobcraig.stridehearth.activities.ProtectedMetrics
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **Daily Load** and **Performance Management Chart** rollup (#36), driven through the real import, edit and delete
 * paths: it recomputes from the earliest affected athlete-local day forward and leaves earlier rows untouched, and the
 * result always equals a from-scratch rebuild. Drains are scoped to this class's athletes.
 *
 * Loads: with resting 50, max 190 and LTHR 170 bpm, a run at 170 bpm scores an HRSS of 100 per hour.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DailyLoadRollupIntegrationTest {
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
    private lateinit var athleteTimeZone: AthleteTimeZone

    @MockitoSpyBean
    private lateinit var dailyLoadRollup: DailyLoadRollup

    @Autowired
    private lateinit var worker: DerivedDataRecomputeWorker

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var runner: User
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "daily_load@example.com", passwordHash = "x", username = "daily_load"))
        otherRunner = userRepository.save(User(email = "daily_load2@example.com", passwordHash = "x", username = "daily_load2"))
        listOf(runner, otherRunner).forEach(::heartRateThresholds)
        drain()
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun drain() = worker.drain(onlyUserIds = listOf(runner.id!!, otherRunner.id!!))

    private fun heartRateThresholds(owner: User) {
        athleteThresholds.record(owner.id!!, ThresholdKind.RESTING_HR, LocalDate.parse("2026-01-01"), 50.0)
        athleteThresholds.record(owner.id!!, ThresholdKind.MAX_HR, LocalDate.parse("2026-01-01"), 190.0)
        athleteThresholds.record(owner.id!!, ThresholdKind.LTHR, LocalDate.parse("2026-01-01"), 170.0)
    }

    /** [minutes] at 170 bpm: an HRSS of 100 x minutes / 60. */
    private fun at170(minutes: Int): FitTelemetry {
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

    private fun import(
        startedAt: String,
        minutes: Int = 60,
        owner: User = runner,
    ): Activity {
        val externalId = "daily-$startedAt-$minutes"
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "Daily load fixture",
                    startedAt = Instant.parse(startedAt),
                    distance = minutes * 0.18,
                    timeTakenSeconds = minutes * 60,
                    externalId = externalId,
                    telemetry = at170(minutes),
                ),
            ),
            owner.id!!,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(owner.id!!, ActivitySource.FIT_UPLOAD, externalId)!!
    }

    private fun march(day: Int) = "2026-03-%02dT08:00:00Z".format(day)

    private fun day(text: String) = LocalDate.parse(text)

    private fun loads(owner: User = runner): List<DailyLoad> = dailyLoadRollup.findDailyLoads(owner.id!!, LocalDate.MIN, LocalDate.MAX)

    private fun metrics(owner: User = runner): List<DailyMetrics> = dailyLoadRollup.findMetrics(owner.id!!, LocalDate.MIN, LocalDate.MAX)

    /** Each stored chart row's day and the transaction that wrote it, which changes whenever the row is rewritten. */
    private fun writers(owner: User = runner): Map<LocalDate, String> =
        jdbcTemplate
            .query(
                "SELECT day, xmin::text AS writer FROM athlete_daily_metrics WHERE user_id = ? ORDER BY day",
                { rs, _ -> rs.getObject("day", LocalDate::class.java) to rs.getString("writer") },
                owner.id,
            ).toMap()

    /** What a from-scratch rebuild of [owner]'s rollups stores, rolled back so the stored rows stay as they were. */
    private fun rebuilt(owner: User = runner): Pair<List<DailyLoad>, List<DailyMetrics>> =
        transactionTemplate.execute { status ->
            dailyLoadRollup.recomputeFrom(owner.id!!, from = null)
            (loads(owner) to metrics(owner)).also { status.setRollbackOnly() }
        }!!

    private fun assertMatchesRebuild(owner: User = runner) {
        assertEquals(rebuilt(owner), loads(owner) to metrics(owner))
    }

    @Test
    fun `an import stores its day's load and the chart from the first scored day to the last Activity`() {
        import(march(10), minutes = 60)
        import(march(10), minutes = 30)
        import(march(12), minutes = 90)

        assertEquals(listOf(day("2026-03-10"), day("2026-03-12")), loads().map { it.day })
        val tenth = loads().first()
        assertEquals(150.0, tenth.trainingLoad, 0.01)
        assertEquals(2, tenth.activityCount)
        assertEquals(0, tenth.unscoredActivityCount)
        assertEquals(listOf(day("2026-03-10"), day("2026-03-11"), day("2026-03-12")), metrics().map { it.day })
        val eleventh = metrics()[1].metrics
        assertEquals(0.0, metrics()[0].metrics.tsb)
        assertEquals(150 * (1 - kotlin.math.exp(-1.0 / 42)) * kotlin.math.exp(-1.0 / 42), eleventh.ctl, 0.01)
        assertMatchesRebuild()
    }

    @Test
    fun `importing an Activity dated before existing ones changes only that day and later days`() {
        listOf(10, 20, 30).forEach { import(march(it), minutes = 30 + it) }
        val before = metrics()
        val writersBefore = writers()

        import(march(15), minutes = 75)

        val cutoff = day("2026-03-15")
        assertEquals(before.filter { it.day < cutoff }, metrics().filter { it.day < cutoff })
        assertEquals(
            writersBefore.filterKeys { it < cutoff },
            writers().filterKeys { it < cutoff },
            "rows before the day are not rewritten",
        )
        assertTrue(writers().filterKeys { it >= cutoff }.all { (day, writer) -> writer != writersBefore[day] })
        assertEquals(125.0, loads().single { it.day == cutoff }.trainingLoad, 0.01)
        assertMatchesRebuild()
    }

    @Test
    fun `importing an Activity before the first scored day moves the first day`() {
        listOf(10, 20, 30).forEach { import(march(it)) }

        import(march(5), minutes = 45)

        assertEquals(day("2026-03-05"), metrics().first().day)
        assertMatchesRebuild()
    }

    @Test
    fun `deleting an Activity recomputes the chart as it was without it`() {
        listOf(10, 20, 30).forEach { import(march(it), minutes = 30 + it) }
        val snapshot = loads() to metrics()
        val extra = import(march(15), minutes = 75)

        activityService.deleteActivity(extra.id!!, runner.id!!)

        assertEquals(snapshot, loads() to metrics(), "the day's load row and every later day are back")
    }

    @Test
    fun `deleting the only scored Activity empties the chart`() {
        val only = import(march(10))

        activityService.deleteActivity(only.id!!, runner.id!!)

        assertEquals(emptyList(), loads())
        assertEquals(emptyList(), metrics())
    }

    @Test
    fun `deleting the first or the last Activity recomputes correctly`() {
        val first = import(march(10))
        import(march(20))
        val last = import(march(30))

        activityService.deleteActivity(last.id!!, runner.id!!)
        assertEquals(day("2026-03-20"), metrics().last().day)
        assertMatchesRebuild()

        activityService.deleteActivity(first.id!!, runner.id!!)
        assertEquals(listOf(day("2026-03-20")), metrics().map { it.day })
        assertMatchesRebuild()
    }

    @Test
    fun `a batch delete across several days gives the same chart as single deletes`() {
        val batch = listOf(10, 15, 20, 25, 30).map { import(march(it), minutes = 20 + it) }
        val single = listOf(10, 15, 20, 25, 30).map { import(march(it), minutes = 20 + it, owner = otherRunner) }

        activityService.batchDeleteActivities(listOf(batch[1].id!!, batch[3].id!!), runner.id!!)
        activityService.deleteActivity(single[1].id!!, otherRunner.id!!)
        activityService.deleteActivity(single[3].id!!, otherRunner.id!!)

        assertEquals(loads(otherRunner) to metrics(otherRunner), loads() to metrics())
        assertMatchesRebuild()
    }

    @Test
    fun `days are the athlete's local dates`() {
        athleteTimeZone.set(runner.id!!, "Europe/London")
        drain()

        import("2026-07-10T23:30:00Z", owner = runner)
        import("2026-07-10T23:30:00Z", owner = otherRunner)

        assertEquals(listOf(day("2026-07-11")), loads().map { it.day }, "23:30Z is 00:30 the next day in British Summer Time")
        assertEquals(listOf(day("2026-07-10")), loads(otherRunner).map { it.day }, "an athlete without a zone counts in UTC")
    }

    @Test
    fun `changing the time zone rebuilds the rollups through the recompute worker`() {
        athleteTimeZone.set(runner.id!!, "Europe/London")
        drain()
        import("2026-07-10T23:30:00Z")
        import("2026-07-20T12:00:00Z")

        athleteTimeZone.set(runner.id!!, "UTC")
        val result = drain()

        assertEquals(1, result.rollupsRebuilt)
        assertEquals(listOf(day("2026-07-10"), day("2026-07-20")), loads().map { it.day })
        assertEquals(day("2026-07-10"), metrics().first().day)
        assertMatchesRebuild()
    }

    @Test
    fun `stale rollups are left alone before commit and rebuilt in full by the worker`() {
        import(march(10))
        jdbcTemplate.update("UPDATE athlete_analytics_state SET rollup_version = 0 WHERE user_id = ?", runner.id)

        import(march(20))
        assertEquals(listOf(day("2026-03-10")), loads().map { it.day }, "the stale rollup was skipped")

        assertEquals(1, drain().rollupsRebuilt)
        assertEquals(listOf(day("2026-03-10"), day("2026-03-20")), loads().map { it.day })
        assertMatchesRebuild()
        assertEquals(0, drain().rollupsRebuilt, "a current rollup is not rebuilt again")
    }

    /** Leaves [owner] as if their Activities had been inserted by SQL: no state row and no rollup rows. */
    private fun forgetRollups(owner: User = runner) {
        jdbcTemplate.update("DELETE FROM athlete_daily_metrics WHERE user_id = ?", owner.id)
        jdbcTemplate.update("DELETE FROM athlete_daily_load WHERE user_id = ?", owner.id)
        jdbcTemplate.update("DELETE FROM athlete_analytics_state WHERE user_id = ?", owner.id)
    }

    @Test
    fun `the worker builds the rollups of an athlete with Activities but no state row`() {
        listOf(10, 20).forEach { import(march(it)) }
        forgetRollups()

        assertEquals(1, drain().rollupsRebuilt)
        assertEquals(listOf(day("2026-03-10"), day("2026-03-20")), loads().map { it.day })
        assertMatchesRebuild()
        assertEquals(0, drain().rollupsRebuilt, "the rebuild recorded the rollups as current")
    }

    @Test
    fun `a time-zone change committed during the worker's rebuild of an athlete without a state row stays stale`() {
        import("2026-07-10T23:30:00Z")
        forgetRollups()
        // The zone change commits after the rebuild has read the old zone and before it records its version: with no
        // state row, the rebuild holds no lock the change waits on.
        val changeZone = AtomicBoolean(true)
        val pool = Executors.newSingleThreadExecutor()
        try {
            doAnswer { invocation ->
                invocation.callRealMethod().also {
                    if (changeZone.getAndSet(false)) {
                        pool.submit { athleteTimeZone.set(runner.id!!, "Europe/London") }.get(30, TimeUnit.SECONDS)
                    }
                }
            }.whenever(dailyLoadRollup).recomputeFrom(eq(runner.id!!), anyOrNull())

            assertEquals(1, drain().rollupsRebuilt)
        } finally {
            pool.shutdownNow()
        }

        assertEquals(listOf(day("2026-07-10")), loads().map { it.day }, "the rebuild used the zone it read")
        assertEquals(
            0,
            jdbcTemplate.queryForObject("SELECT rollup_version FROM athlete_analytics_state WHERE user_id = ?", Int::class.java, runner.id),
        )
        assertEquals(1, drain().rollupsRebuilt, "the zone change's stale mark stood")
        assertEquals(listOf(day("2026-07-11")), loads().map { it.day })
        assertMatchesRebuild()
    }

    @Test
    fun `the worker leaves an athlete with neither Activities nor a state row alone`() {
        forgetRollups()

        assertEquals(0, drain().rollupsRebuilt)
    }

    @Test
    fun `a Threshold change that creates the state row of an athlete with Activities rebuilds their rollups in full`() {
        listOf(10, 20, 30).forEach { import(march(it)) }
        forgetRollups()

        athleteThresholds.record(runner.id!!, ThresholdKind.LTHR, day("2026-03-20"), 160.0)
        drain()

        assertEquals(listOf(day("2026-03-10"), day("2026-03-20"), day("2026-03-30")), loads().map { it.day })
        assertEquals(day("2026-03-10"), metrics().first().day)
        assertMatchesRebuild()
    }

    @Test
    fun `one athlete whose rollups cannot be rebuilt does not hold back the others`() {
        import(march(10))
        import(march(12), owner = otherRunner)
        jdbcTemplate.update("UPDATE athlete_analytics_state SET rollup_version = 0 WHERE user_id IN (?, ?)", runner.id, otherRunner.id)
        jdbcTemplate.update("DELETE FROM athlete_daily_load WHERE user_id = ?", otherRunner.id)
        // An athlete id below the other's, with a zone their rebuild cannot use.
        assertTrue(runner.id!! < otherRunner.id!!)
        jdbcTemplate.update("UPDATE app_user SET time_zone = 'Not/A_Zone' WHERE id = ?", runner.id)

        val result = drain()

        assertEquals(1, result.rollupsRebuilt)
        assertEquals(1, result.rollupsFailed)
        assertEquals(listOf(day("2026-03-12")), loads(otherRunner).map { it.day })
        assertEquals(RecomputeResult(0, 0), drain(), "the failed athlete is backed off, not retried every run")
    }

    @Test
    fun `a Threshold change that alters loads updates the chart from its date`() {
        listOf(10, 20, 30).forEach { import(march(it)) }
        val before = metrics()

        athleteThresholds.record(runner.id!!, ThresholdKind.LTHR, day("2026-03-20"), 160.0)
        drain()

        val cutoff = day("2026-03-20")
        assertEquals(before.filter { it.day < cutoff }, metrics().filter { it.day < cutoff })
        assertTrue(loads().single { it.day == cutoff }.trainingLoad > 100.0, "a lower LTHR scores the same run higher")
        assertTrue(metrics().single { it.day == cutoff }.metrics.ctl > before.single { it.day == cutoff }.metrics.ctl)
        assertMatchesRebuild()
    }

    @Test
    fun `editing a manual Activity's start moves its count between days`() {
        import(march(10))
        val manual =
            activityService.createManualActivity(
                runner.id!!,
                "Manual",
                ActivitySport.RUN,
                ProtectedMetrics(Instant.parse(march(12)), 5.0, 1800, null, null, null),
            )
        assertEquals(1, loads().single { it.day == day("2026-03-12") }.unscoredActivityCount)

        activityService.updateActivity(
            manual.id,
            runner.id!!,
            ActivityUpdateRequestDTO(
                title = "Manual",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse(march(14)),
                distance = 5.0,
                timeTakenSeconds = 1800,
            ),
        )

        assertEquals(listOf(day("2026-03-10"), day("2026-03-14")), loads().map { it.day })
        val fourteenth = loads().last()
        assertEquals(DailyLoad(day("2026-03-14"), 0.0, 1, 1), fourteenth)
        assertEquals(day("2026-03-14"), metrics().last().day, "an unscored day still extends the chart")
        assertMatchesRebuild()
    }

    @Test
    fun `an athlete's rollups never include another athlete's Activities`() {
        import(march(10), owner = otherRunner)
        import(march(12))

        assertEquals(listOf(day("2026-03-12")), loads().map { it.day })
        assertEquals(listOf(day("2026-03-10")), loads(otherRunner).map { it.day })
    }
}
