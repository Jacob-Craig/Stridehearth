package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivityService
import com.jacobcraig.stridehearth.activities.ActivitySource
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **Heat Acclimation** and **Altitude Acclimation** rollup (#43), driven through the real import and delete paths:
 * it rebuilds from the earliest affected athlete-local day forward, leaves earlier rows untouched, and always equals a
 * from-scratch rebuild.
 *
 * Every percentage is a whole number of 100/42 steps: an exposed day adds 3 steps (100/14) and an unexposed one takes
 * away 2 (100/21).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AcclimationRollupIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var acclimationRollup: AcclimationRollup

    @Autowired
    private lateinit var athleteTimeZone: AthleteTimeZone

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
        runner = userRepository.save(User(email = "acclimation@example.com", passwordHash = "x", username = "acclimation"))
        otherRunner = userRepository.save(User(email = "acclimation2@example.com", passwordHash = "x", username = "acclimation2"))
        drain()
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun drain() = worker.drain(onlyUserIds = listOf(runner.id!!, otherRunner.id!!))

    /** [minutes] of 1 Hz Activity Details whose watch reads [deviceC] °C, at [altitudeM] m. */
    private fun details(
        minutes: Int,
        deviceC: Int,
        altitudeM: Float,
    ): FitTelemetry {
        val n = minutes * 60 + 1
        return FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + it * 3.0 / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { 3.0f },
            distance = Array(n) { it * 3.0f },
            altitude = Array(n) { altitudeM },
            temperature = Array(n) { deviceC.toShort() },
        )
    }

    /**
     * Imports a run started at [startedAt] lasting [minutes]. The default watch reading of 36 °C is an Ambient
     * Temperature of 30 °C: heat exposure. The default altitude of 30 m is no altitude exposure.
     */
    private fun import(
        startedAt: String,
        minutes: Int = 20,
        deviceC: Int = 36,
        altitudeM: Float = 30f,
        owner: User = runner,
    ): Activity {
        val externalId = "acclimation-$startedAt-$minutes-$deviceC-$altitudeM"
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "Acclimation fixture",
                    startedAt = Instant.parse(startedAt),
                    distance = minutes * 0.18,
                    timeTakenSeconds = minutes * 60,
                    externalId = externalId,
                    telemetry = details(minutes, deviceC, altitudeM),
                ),
            ),
            owner.id!!,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(owner.id!!, ActivitySource.FIT_UPLOAD, externalId)!!
    }

    private fun march(day: Int) = "2026-03-%02dT08:00:00Z".format(day)

    private fun day(text: String) = LocalDate.parse(text)

    private fun steps(n: Int) = n * 100.0 / 42

    private fun days(owner: User = runner): List<AcclimationRow> = acclimationRollup.findDays(owner.id!!, LocalDate.MIN, LocalDate.MAX)

    private fun heat(owner: User = runner) = days(owner).associate { it.day to it.heatPct }

    /** Each stored row's day and the transaction that wrote it, which changes whenever the row is rewritten. */
    private fun writers(): Map<LocalDate, String> =
        jdbcTemplate
            .query(
                "SELECT day, xmin::text AS writer FROM athlete_acclimation_daily WHERE user_id = ? ORDER BY day",
                { rs, _ -> rs.getObject("day", LocalDate::class.java) to rs.getString("writer") },
                runner.id,
            ).toMap()

    /** What a from-scratch rebuild stores, rolled back so the stored rows stay as they were. */
    private fun rebuilt(): List<AcclimationRow> =
        transactionTemplate.execute { status ->
            acclimationRollup.recomputeFrom(runner.id!!, from = null)
            days().also { status.setRollbackOnly() }
        }!!

    private fun assertMatchesRebuild() = assertEquals(rebuilt(), days())

    @Test
    fun `a 20-minute run at 30 degrees marks its day heat-exposed, and the next Activity's day decays`() {
        import(march(10))
        import(march(12), deviceC = 20)

        assertEquals(
            listOf(
                AcclimationRow(day("2026-03-10"), steps(3), 0.0, heatExposed = true, altitudeExposed = false),
                AcclimationRow(day("2026-03-11"), steps(1), 0.0, heatExposed = false, altitudeExposed = false),
                AcclimationRow(day("2026-03-12"), 0.0, 0.0, heatExposed = false, altitudeExposed = false),
            ),
            days(),
        )
        assertMatchesRebuild()
    }

    @Test
    fun `a 19-minute hot run and a run at 22 degrees are no exposure, so there are no rows`() {
        import(march(10), minutes = 19)
        import(march(11), deviceC = 28)

        assertEquals(emptyList(), days(), "28 °C on the watch is 22 °C ambient, which is not above 22")
    }

    @Test
    fun `a run above 800 m builds Altitude Acclimation on its own`() {
        import(march(10), deviceC = 20, altitudeM = 900f)
        import(march(11), deviceC = 36, altitudeM = 900f)

        assertEquals(listOf(0.0, steps(3)), days().map { it.heatPct })
        assertEquals(listOf(steps(3), steps(6)), days().map { it.altitudePct })
        assertEquals(listOf(true, true), days().map { it.altitudeExposed })
        assertMatchesRebuild()
    }

    @Test
    fun `deleting the only exposed run removes every row`() {
        val hot = import(march(10))
        import(march(12), deviceC = 20)

        activityService.deleteActivity(hot.id!!, runner.id!!)

        assertEquals(emptyList(), days())
    }

    @Test
    fun `deleting the last Activity ends the rows at the Activity before it`() {
        import(march(10))
        val last = import(march(15), deviceC = 20)

        activityService.deleteActivity(last.id!!, runner.id!!)

        assertEquals(listOf(day("2026-03-10")), days().map { it.day })
        assertMatchesRebuild()
    }

    @Test
    fun `importing an older run rewrites only the rows from its day on`() {
        listOf(10, 11, 12, 20).forEach { import(march(it)) }
        val writersBefore = writers()
        val before = days()

        import(march(15))

        val cutoff = day("2026-03-15")
        assertEquals(before.filter { it.day < cutoff }, days().filter { it.day < cutoff })
        assertEquals(
            writersBefore.filterKeys { it < cutoff },
            writers().filterKeys { it < cutoff },
            "rows before the day are not rewritten",
        )
        // 9 steps on the 12th, 2 days of decay to 5, then +3 on the 15th.
        assertEquals(steps(8), heat().getValue(cutoff))
        assertMatchesRebuild()
    }

    @Test
    fun `an import 10 days after the last Activity continues from the decayed value`() {
        listOf(1, 2, 3, 4, 5, 6, 7).forEach { import(march(it)) }
        assertEquals(steps(21), heat().getValue(day("2026-03-07")))

        import(march(17))

        // 21 steps on the 7th, 9 unexposed days to 3, then +3 on the 17th.
        assertEquals(steps(3), heat().getValue(day("2026-03-16")))
        assertEquals(steps(6), heat().getValue(day("2026-03-17")))
        assertMatchesRebuild()
    }

    @Test
    fun `days are the athlete's local dates`() {
        athleteTimeZone.set(runner.id!!, "Europe/London")
        drain()

        import("2026-07-10T23:30:00Z")
        import("2026-07-10T23:30:00Z", owner = otherRunner)

        assertEquals(listOf(day("2026-07-11")), days().map { it.day }, "23:30Z is 00:30 the next day in British Summer Time")
        assertEquals(listOf(day("2026-07-10")), days(otherRunner).map { it.day })
    }

    @Test
    fun `stale rollups are left alone before commit and rebuilt in full by the worker`() {
        import(march(10))
        jdbcTemplate.update("UPDATE athlete_analytics_state SET rollup_version = ? WHERE user_id = ?", ROLLUP_VERSION - 1, runner.id)

        import(march(11))
        assertEquals(listOf(day("2026-03-10")), days().map { it.day }, "the stale rollup was skipped")

        assertEquals(1, drain().rollupsRebuilt)
        assertEquals(listOf(steps(3), steps(6)), days().map { it.heatPct })
        assertEquals(0, drain().rollupsRebuilt)
    }

    @Test
    fun `the worker builds the acclimation of an athlete with Activities but no state row`() {
        import(march(10))
        jdbcTemplate.update("DELETE FROM athlete_acclimation_daily WHERE user_id = ?", runner.id)
        jdbcTemplate.update("DELETE FROM athlete_analytics_state WHERE user_id = ?", runner.id)

        assertEquals(1, drain().rollupsRebuilt)
        assertEquals(listOf(day("2026-03-10")), days().map { it.day })
    }

    @Test
    fun `an athlete's acclimation never counts another athlete's Activities`() {
        import(march(10), owner = otherRunner)

        assertEquals(emptyList(), days())
        assertTrue(days(otherRunner).isNotEmpty())
    }
}
