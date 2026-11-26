package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityDerivedMetricsRepository
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivityService
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.analytics.FitnessDay
import com.jacobcraig.stridehearth.analytics.Vo2maxResult
import com.jacobcraig.stridehearth.analytics.Vo2maxUnavailableReason
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-Run **VO2max Estimate** as stored, and the **Effective VO2max** rollup (#42), driven through the real
 * import, delete and Threshold paths: it rebuilds from the earliest affected athlete-local day forward, leaves earlier
 * rows untouched, and always equals a from-scratch rebuild.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FitnessRollupIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var derivedMetricsRepository: ActivityDerivedMetricsRepository

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
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "fitness@example.com", passwordHash = "x", username = "fitness"))
        otherRunner = userRepository.save(User(email = "fitness2@example.com", passwordHash = "x", username = "fitness2"))
        drain()
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun drain() = worker.drain(onlyUserIds = listOf(runner.id!!, otherRunner.id!!))

    private fun heartRateThresholds(
        owner: User = runner,
        resting: Boolean = true,
    ) {
        athleteThresholds.record(owner.id!!, ThresholdKind.MAX_HR, LocalDate.parse("2026-01-01"), 190.0)
        if (resting) athleteThresholds.record(owner.id!!, ThresholdKind.RESTING_HR, LocalDate.parse("2026-01-01"), 50.0)
    }

    private fun march(day: Int): LocalDate = LocalDate.of(2026, 3, day)

    private fun import(
        day: LocalDate,
        details: FitTelemetry = Vo2maxFixtures.fast(),
        owner: User = runner,
        tag: String = "",
    ): Long = activityImportService.importRun(activityRepository, owner.id!!, day, details, tag)

    private fun days(owner: User = runner): List<FitnessDay> = fitnessRollup.findDays(owner.id!!, LocalDate.MIN, LocalDate.MAX)

    private fun effective(owner: User = runner) = days(owner).associate { it.day to it.effectiveVo2max }

    /** Each stored row's day and the transaction that wrote it, which changes whenever the row is rewritten. */
    private fun writers(): Map<LocalDate, String> =
        jdbcTemplate
            .query(
                "SELECT day, xmin::text AS writer FROM athlete_daily_fitness WHERE user_id = ? ORDER BY day",
                { rs, _ -> rs.getObject("day", LocalDate::class.java) to rs.getString("writer") },
                runner.id,
            ).toMap()

    /** What a from-scratch rebuild stores, rolled back so the stored rows stay as they were. */
    private fun rebuilt(): List<FitnessDay> =
        transactionTemplate.execute { status ->
            fitnessRollup.recomputeFrom(runner.id!!, from = null)
            days().also { status.setRollbackOnly() }
        }!!

    private fun assertMatchesRebuild() = assertEquals(rebuilt(), days())

    @Test
    fun `a qualifying run stores its estimate, and the rows run from its day to the last Activity's`() {
        heartRateThresholds()
        val run = import(march(10))
        import(march(12), Vo2maxFixtures.telemetry(600, 3.0f, 140))

        val estimate = assertIs<Vo2maxResult.Estimate>(derivedMetricsRepository.findVo2max(run))
        assertEquals(Vo2maxFixtures.FAST_RAW, estimate.raw, 0.05)
        assertEquals(listOf(march(10), march(11), march(12)), days().map { it.day })
        days().forEach {
            assertEquals(Vo2maxFixtures.FAST_RAW, assertNotNull(it.effectiveVo2max), 0.05)
            assertEquals(1.0, it.correctionFactor)
        }
        assertMatchesRebuild()
    }

    @Test
    fun `a run without Heart Rate Thresholds stores why, and recording Resting HR fills its estimate through the worker`() {
        heartRateThresholds(resting = false)
        val run = import(march(10))
        assertEquals(Vo2maxResult.Unavailable(Vo2maxUnavailableReason.NO_HEART_RATE_THRESHOLDS), derivedMetricsRepository.findVo2max(run))
        assertEquals(emptyList(), days())

        athleteThresholds.record(runner.id!!, ThresholdKind.RESTING_HR, LocalDate.parse("2026-01-01"), 50.0)
        assertTrue(drain().thresholdDependentRecomputed >= 1)

        assertEquals(Vo2maxFixtures.FAST_RAW, assertIs<Vo2maxResult.Estimate>(derivedMetricsRepository.findVo2max(run)).raw, 0.05)
        assertEquals(Vo2maxFixtures.FAST_RAW, assertNotNull(effective()[march(10)]), 0.05)
    }

    @Test
    fun `a ride has no estimate and no reason`() {
        heartRateThresholds()
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = ActivitySport.RIDE,
                    title = "Ride",
                    startedAt = march(10).atTime(8, 0).toInstant(ZoneOffset.UTC),
                    distance = 9.6,
                    timeTakenSeconds = 2400,
                    externalId = "vo2max-ride",
                    telemetry = Vo2maxFixtures.fast(),
                ),
            ),
            runner.id!!,
        )
        val ride = activityRepository.findByUserIdAndSourceAndExternalId(runner.id!!, ActivitySource.FIT_UPLOAD, "vo2max-ride")!!

        assertNull(derivedMetricsRepository.findVo2max(ride.id!!))
        assertEquals(emptyList(), days())
    }

    @Test
    fun `a Run without Activity Details stores why it has no estimate, and a Ride without them stores none`() {
        heartRateThresholds()
        listOf(ActivitySport.RUN, ActivitySport.RIDE).forEach { sport ->
            activityImportService.importParsed(
                listOf(
                    parsedActivity(
                        sport = sport,
                        title = "Manual $sport",
                        startedAt = march(10).atTime(8, 0).toInstant(ZoneOffset.UTC),
                        distance = 9.6,
                        timeTakenSeconds = 2400,
                        externalId = "vo2max-manual-$sport",
                    ),
                ),
                runner.id!!,
            )
        }
        val run = activityRepository.findByUserIdAndSourceAndExternalId(runner.id!!, ActivitySource.FIT_UPLOAD, "vo2max-manual-RUN")!!
        val ride = activityRepository.findByUserIdAndSourceAndExternalId(runner.id!!, ActivitySource.FIT_UPLOAD, "vo2max-manual-RIDE")!!

        assertEquals(Vo2maxResult.Unavailable(Vo2maxUnavailableReason.NO_ACTIVITY_DETAILS), derivedMetricsRepository.findVo2max(run.id!!))
        assertNull(derivedMetricsRepository.findVo2max(ride.id!!))
        // The Threshold-dependent recompute path writes the same reason.
        athleteThresholds.record(runner.id!!, ThresholdKind.RESTING_HR, LocalDate.parse("2026-01-01"), 52.0)
        drain()
        assertEquals(Vo2maxResult.Unavailable(Vo2maxUnavailableReason.NO_ACTIVITY_DETAILS), derivedMetricsRepository.findVo2max(run.id!!))
        assertEquals(emptyList(), days())
    }

    @Test
    fun `the 30-day mean weights each run by its Moving Time and drops a run after 30 days`() {
        heartRateThresholds()
        import(march(1))
        import(march(5), Vo2maxFixtures.easy())
        import(march(31), Vo2maxFixtures.telemetry(600, 3.0f, 140))

        // Equal Moving Times: (55.540 + 53.158) / 2 = 54.349 from the 5th; the 1st drops out on the 31st.
        assertEquals(Vo2maxFixtures.FAST_RAW, assertNotNull(effective()[march(4)]), 0.05)
        assertEquals((Vo2maxFixtures.FAST_RAW + Vo2maxFixtures.EASY_RAW) / 2, assertNotNull(effective()[march(30)]), 0.05)
        assertEquals(Vo2maxFixtures.EASY_RAW, assertNotNull(effective()[march(31)]), 0.05)
        assertMatchesRebuild()
    }

    @Test
    fun `a 5K race too short for an estimate of its own calibrates the Correction Factor`() {
        heartRateThresholds()
        import(march(1))
        import(march(5), Vo2maxFixtures.easy())
        val race = import(march(8), Vo2maxFixtures.race())

        val day = days().single { it.day == march(8) }
        val source = assertNotNull(day.correctionSource)
        assertEquals(race, source.activityId)
        assertEquals(Vo2maxFixtures.RACE_VDOT, source.vdot, 0.3)
        // CF = VDOT / 54.349 = 1.133, so the Effective VO2max that day is the race's VDOT.
        assertEquals(source.vdot / ((Vo2maxFixtures.FAST_RAW + Vo2maxFixtures.EASY_RAW) / 2), day.correctionFactor, 0.002)
        assertEquals(source.vdot, assertNotNull(day.effectiveVo2max), 0.05)
        assertEquals(1.0, days().single { it.day == march(7) }.correctionFactor)
        assertMatchesRebuild()
    }

    @Test
    fun `deleting a run rebuilds from its day forward only`() {
        heartRateThresholds()
        import(march(1))
        val easy = import(march(10), Vo2maxFixtures.easy())
        import(march(20), Vo2maxFixtures.telemetry(600, 3.0f, 140))
        val writersBefore = writers()

        activityService.deleteActivity(easy, runner.id!!)

        val cutoff = march(10)
        assertEquals(
            writersBefore.filterKeys { it < cutoff },
            writers().filterKeys { it < cutoff },
            "rows before the day are not rewritten",
        )
        assertEquals(Vo2maxFixtures.FAST_RAW, assertNotNull(effective()[march(15)]), 0.05)
        assertMatchesRebuild()
    }

    @Test
    fun `importing an older run rewrites only the rows from its day on`() {
        heartRateThresholds()
        import(march(1))
        import(march(20), Vo2maxFixtures.telemetry(600, 3.0f, 140))
        val writersBefore = writers()
        val before = days()

        import(march(10), Vo2maxFixtures.easy())

        val cutoff = march(10)
        assertEquals(before.filter { it.day < cutoff }, days().filter { it.day < cutoff })
        assertEquals(
            writersBefore.filterKeys { it < cutoff },
            writers().filterKeys { it < cutoff },
            "rows before the day are not rewritten",
        )
        assertEquals((Vo2maxFixtures.FAST_RAW + Vo2maxFixtures.EASY_RAW) / 2, assertNotNull(effective()[march(10)]), 0.05)
        assertMatchesRebuild()
    }

    @Test
    fun `stale rollups are rebuilt in full by the worker`() {
        heartRateThresholds()
        import(march(10))
        jdbcTemplate.update("DELETE FROM athlete_daily_fitness WHERE user_id = ?", runner.id)
        jdbcTemplate.update("UPDATE athlete_analytics_state SET rollup_version = ? WHERE user_id = ?", ROLLUP_VERSION - 1, runner.id)

        assertEquals(1, drain().rollupsRebuilt)
        assertEquals(listOf(march(10)), days().map { it.day })
    }

    @Test
    fun `an athlete's Effective VO2max never counts another athlete's runs`() {
        heartRateThresholds(owner = otherRunner)
        heartRateThresholds()
        import(march(10), owner = otherRunner)

        assertEquals(emptyList(), days())
        assertTrue(days(otherRunner).isNotEmpty())
    }
}
