package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivityService
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.ActivityUpdateRequestDTO
import com.jacobcraig.stridehearth.activities.ProtectedMetrics
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.analytics.FitnessDay
import com.jacobcraig.stridehearth.athlete.Vo2maxFixtures.importRun
import com.jacobcraig.stridehearth.system.ActivitySport
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The **Marathon Shape** columns of the Effective VO2max rollup (#44), driven through the real import, delete and
 * edit paths: they rebuild from the earliest affected athlete-local day forward, leave earlier rows untouched, and
 * always equal a from-scratch rebuild.
 *
 * The 40-minute run on 1 March (9.6 km) gives an Effective VO2max of 55.540 from then on, so the weekly target is
 * 55.540^1.135 = 95.528 km and the target long run 12·ln(55.540/4) − 6 = 25.570 km. Until the 28th, the volume is
 * averaged over 4 weeks.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MarathonShapeIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var fitnessRollup: FitnessRollup

    @Autowired
    private lateinit var athleteThresholds: AthleteThresholds

    @Autowired
    private lateinit var worker: DerivedDataRecomputeWorker

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var runner: User
    private lateinit var sequential: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "shape@example.com", passwordHash = "x", username = "shape"))
        sequential = userRepository.save(User(email = "shape_seq@example.com", passwordHash = "x", username = "shape_seq"))
        listOf(runner, sequential).forEach {
            athleteThresholds.record(it.id!!, ThresholdKind.MAX_HR, LocalDate.parse("2026-01-01"), 190.0)
            athleteThresholds.record(it.id!!, ThresholdKind.RESTING_HR, LocalDate.parse("2026-01-01"), 50.0)
        }
        worker.drain(onlyUserIds = listOf(runner.id!!, sequential.id!!))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun march(day: Int): LocalDate = LocalDate.of(2026, 3, day)

    private fun importRun(
        day: LocalDate,
        details: FitTelemetry = Vo2maxFixtures.fast(),
        owner: User = runner,
        tag: String = "",
    ): Long = activityImportService.importRun(activityRepository, owner.id!!, day, details, tag)

    /** Imports a Run of [km] without Activity Details at 08:00 UTC on [day]. */
    private fun importPlain(
        day: LocalDate,
        km: Double,
        owner: User = runner,
    ): Long {
        val externalId = "shape-$day-$km"
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "Long run",
                    startedAt = day.atTime(8, 0).toInstant(ZoneOffset.UTC),
                    distance = km,
                    timeTakenSeconds = (km * 360).toInt(),
                    externalId = externalId,
                ),
            ),
            owner.id!!,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(owner.id!!, ActivitySource.FIT_UPLOAD, externalId)!!.id!!
    }

    /** The 9.6 km qualifying run on the 1st and a short 1.8 km run on the 20th: rows from the 1st to the 20th. */
    private fun seed(owner: User = runner) {
        importRun(march(1), owner = owner)
        importRun(march(20), Vo2maxFixtures.telemetry(600, 3.0f, 140), owner = owner, tag = "short")
    }

    private fun days(owner: User = runner): List<FitnessDay> = fitnessRollup.findDays(owner.id!!, LocalDate.MIN, LocalDate.MAX)

    private fun pct(day: LocalDate): Double = assertNotNull(days().single { it.day == day }.marathonShape).pct

    private fun writers(): Map<LocalDate, String> =
        jdbcTemplate
            .query(
                "SELECT day, xmin::text AS writer FROM athlete_daily_fitness WHERE user_id = ? ORDER BY day",
                { rs, _ -> rs.getObject("day", LocalDate::class.java) to rs.getString("writer") },
                runner.id,
            ).toMap()

    /** The transaction that last wrote [runner]'s Daily Load row of [day]. */
    private fun dailyLoadWriter(day: LocalDate): String =
        jdbcTemplate.queryForObject(
            "SELECT xmin::text FROM athlete_daily_load WHERE user_id = ? AND day = ?",
            String::class.java,
            runner.id,
            day,
        )!!

    private fun rebuilt(owner: User = runner): List<FitnessDay> =
        transactionTemplate.execute { status ->
            fitnessRollup.recomputeFrom(owner.id!!, from = null)
            days(owner).also { status.setRollbackOnly() }
        }!!

    @Test
    fun `each day with an Effective VO2max stores its Marathon Shape`() {
        seed()

        val day = days().single { it.day == march(9) }
        val shape = assertNotNull(day.marathonShape)
        // 9.6 km over 4 weeks: 2.4 km a week, 2.4 / 95.528 = 0.02512, so 100 · ⅔ · 0.02512 = 1.675.
        assertEquals(2.4, shape.weeklyKm, 1e-9)
        assertEquals(0.02512, shape.volumeScore, 0.0001)
        assertEquals(0.0, shape.longRunScore)
        assertEquals(1.675, shape.pct, 0.003)
        assertEquals(rebuilt(), days())
    }

    @Test
    fun `a 20 km import raises its day and every later day, leaves earlier rows untouched, and its delete restores them`() {
        seed()
        val before = days()
        val writersBefore = writers()

        val longRun = importPlain(march(10), 20.0)

        // On the 10th: 29.6 km over 4 weeks is 7.4 km a week, and the 20 km run scores ((20 − 13) / 12.570)² = 0.3101
        // points: 100 · (⅔ · 7.4 / 95.528 + ⅓ · 0.3101 / 5.5) = 7.044.
        assertEquals(7.044, pct(march(10)), 0.01)
        // On the 20th: 31.4 km is 7.85 km a week, and the run, 10 days old, scores 0.3101 · 60/70.
        assertEquals(7.089, pct(march(20)), 0.01)
        val cutoff = march(10)
        assertEquals(before.filter { it.day < cutoff }, days().filter { it.day < cutoff })
        assertEquals(
            writersBefore.filterKeys { it < cutoff },
            writers().filterKeys { it < cutoff },
            "rows before the day are not rewritten",
        )
        days().filter { it.day >= cutoff }.forEach { day ->
            val old = before.single { it.day == day.day }
            assert(assertNotNull(day.marathonShape).pct > assertNotNull(old.marathonShape).pct) { "${day.day} is raised" }
        }
        assertEquals(rebuilt(), days())

        activityService.deleteActivity(longRun, runner.id!!)

        assertEquals(before, days())
    }

    @Test
    fun `editing a manual Run's distance recomputes its day and every later day`() {
        seed()
        val manual =
            activityService.createManualActivity(
                runner.id!!,
                "Long run",
                ActivitySport.RUN,
                ProtectedMetrics(march(10).atTime(8, 0).toInstant(ZoneOffset.UTC), 20.0, 7200, null, null, null),
            )
        assertEquals(7.044, pct(march(10)), 0.01)
        val dailyLoadWriter = dailyLoadWriter(march(10))

        activityService.updateActivity(
            manual.id,
            runner.id!!,
            ActivityUpdateRequestDTO(
                title = "Long run",
                sport = ActivitySport.RUN,
                startedAt = march(10).atTime(8, 0).toInstant(ZoneOffset.UTC),
                distance = 25.0,
                timeTakenSeconds = 9000,
            ),
        )

        // 34.6 km over 4 weeks is 8.65 km a week, and ((25 − 13) / 12.570)² = 0.9113 points:
        // 100 · (⅔ · 8.65 / 95.528 + ⅓ · 0.9113 / 5.5) = 11.560.
        assertEquals(11.560, pct(march(10)), 0.01)
        assertEquals(rebuilt(), days())
        assert(dailyLoadWriter(march(10)) != dailyLoadWriter) { "the edit marks every daily rollup, the Daily Load included" }
    }

    @Test
    fun `concurrent imports give the same rows as the same imports one after another`() {
        val files: List<(User) -> Unit> =
            listOf(
                { owner -> importRun(march(1), owner = owner) },
                { owner -> importRun(march(20), Vo2maxFixtures.telemetry(600, 3.0f, 140), owner = owner, tag = "short") },
                { owner -> importPlain(march(5), 18.0, owner) },
                { owner -> importPlain(march(12), 30.0, owner) },
                { owner -> importPlain(march(15), 8.0, owner) },
            )
        files.forEach { it(sequential) }

        val pool = Executors.newFixedThreadPool(files.size)
        try {
            val start = CountDownLatch(1)
            val tasks = files.map { file -> pool.submit { start.await().also { file(runner) } } }
            start.countDown()
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val result = days()
        assertEquals(days(sequential), result)
        assertEquals(rebuilt(), result)
    }

    @Test
    fun `stale rollups are rebuilt in full by the worker, Marathon Shape included`() {
        seed()
        val before = days()
        jdbcTemplate.update("DELETE FROM athlete_daily_fitness WHERE user_id = ?", runner.id)
        jdbcTemplate.update("UPDATE athlete_analytics_state SET rollup_version = ? WHERE user_id = ?", ROLLUP_VERSION - 1, runner.id)

        assertEquals(1, worker.drain(onlyUserIds = listOf(runner.id!!)).rollupsRebuilt)
        assertEquals(before, days())
    }

    @Test
    fun `a day without an Effective VO2max has no Marathon Shape`() {
        importRun(march(1))
        importPlain(march(31), 10.0)

        // The run of the 1st leaves the 30-day window on the 31st.
        val last = days().single { it.day == march(31) }
        assertNull(last.effectiveVo2max)
        assertNull(last.marathonShape)
    }
}
