package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import com.jacobcraig.stridehearth.athlete.DerivedDataRecomputeWorker
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Peak Efforts** are derived data: computed when a Run is created or Upgraded, removed when it stops being a Run
 * or is deleted, and filled in for Runs stored before they existed by the recompute worker (ADR-0014).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PeakEffortRefreshTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var peakEffortRepository: ActivityPeakEffortRepository

    @Autowired
    private lateinit var worker: DerivedDataRecomputeWorker

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var runner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "peak_runner@example.com", passwordHash = "x", username = "peak_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** A flat 1 Hz run from t = 0 to t = [seconds] at [speed] m/s, with a GPS fix per sample and, if given, [watts]. */
    private fun flatRun(
        seconds: Int = 600,
        speed: Float = 3.0f,
        watts: Short? = null,
    ): FitTelemetry {
        val n = seconds + 1
        return FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + it * speed / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { speed },
            distance = Array(n) { it * speed },
            altitude = Array(n) { 30.0f },
            power = if (watts == null) emptyArray() else Array(n) { watts },
        )
    }

    private fun import(
        details: FitTelemetry = flatRun(),
        sport: ActivitySport = ActivitySport.RUN,
        externalId: String = "peak-1",
        parserVersion: ParserVersion = FitActivityParser.PARSER_VERSION,
    ): Activity {
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = sport,
                    title = "Peak fixture",
                    startedAt = Instant.parse("2026-03-01T08:00:00Z"),
                    distance = 1.8,
                    timeTakenSeconds = details.t.last(),
                    externalId = externalId,
                    telemetry = details,
                    parserVersion = parserVersion,
                ),
            ),
            runner.id!!,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(runner.id!!, ActivitySource.FIT_UPLOAD, externalId)!!
    }

    private fun peaks(
        activity: Activity,
        metric: PeakEffortMetric = PeakEffortMetric.GAP_SPEED,
    ): Map<BenchmarkDuration, Double> =
        peakEffortRepository.findByActivityId(activity.id!!).filter { it.metric == metric }.associate { it.benchmarkDuration to it.value }

    private fun peakRowCount(activity: Activity): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM activity_peak_effort WHERE activity_id = ?", Int::class.java, activity.id)!!

    /** The Benchmark Durations a stream spanning [seconds] reaches. */
    private fun durationsUpTo(seconds: Int) = BenchmarkDuration.entries.filter { it.seconds <= seconds }

    @Test
    fun `importing a run stores its GAP and power Peak Efforts`() {
        val run = import(flatRun(watts = 240))

        val gap = peaks(run)
        assertEquals(durationsUpTo(600), gap.keys.sorted())
        gap.values.forEach { assertEquals(3.0, it, 1e-3) }
        val power = peaks(run, PeakEffortMetric.POWER)
        assertEquals(durationsUpTo(600), power.keys.sorted())
        power.values.forEach { assertEquals(240.0, it, 1e-9) }
    }

    @Test
    fun `importing a walk or a ride stores no Peak Efforts`() {
        val walk = import(sport = ActivitySport.WALK, externalId = "walk")
        val ride = import(sport = ActivitySport.RIDE, externalId = "ride")

        assertEquals(0, peakRowCount(walk))
        assertEquals(0, peakRowCount(ride))
    }

    @Test
    fun `an Upgrade replaces the Peak Efforts with ones from the new Activity Details`() {
        val run = import(flatRun(seconds = 600, speed = 3.0f))

        import(flatRun(seconds = 1200, speed = 3.5f), parserVersion = ParserVersion(FitActivityParser.PARSER_VERSION.value + 1))

        val gap = peaks(run)
        assertEquals(durationsUpTo(1200), gap.keys.sorted())
        gap.values.forEach { assertEquals(3.5, it, 1e-3) }
    }

    @Test
    fun `reclassifying a run as a walk removes its Peak Efforts, and back to run restores them`() {
        val run = import()
        val before = peaks(run)

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Walk", sport = ActivitySport.WALK))
        assertEquals(0, peakRowCount(run))

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Run", sport = ActivitySport.RUN))
        assertEquals(before, peaks(run))
    }

    @Test
    fun `a batch reclassification removes the Peak Efforts of every run it changes`() {
        val first = import(externalId = "a")
        val second = import(externalId = "b")

        activityService.batchUpdateActivities(
            listOf(first.id!!, second.id!!),
            runner.id!!,
            BatchMetadataChange.of(null, ActivitySport.WALK)!!,
        )

        assertEquals(0, peakRowCount(first))
        assertEquals(0, peakRowCount(second))
    }

    @Test
    fun `deleting a run removes its Peak Efforts`() {
        val run = import()
        assertTrue(peakRowCount(run) > 0)

        activityService.deleteActivity(run.id!!, runner.id!!)

        assertEquals(0, peakRowCount(run))
    }

    @Test
    fun `the recompute worker fills in Peak Efforts for runs that already hold Best Efforts, once`() {
        val run = import(flatRun(seconds = 1200))
        // As stored before Peak Efforts existed: Best Efforts and a derived-data row of the previous version.
        jdbcTemplate.update("DELETE FROM activity_peak_effort WHERE activity_id = ?", run.id)
        jdbcTemplate.update(
            "UPDATE activity_derived_metrics SET algorithm_version = ? WHERE activity_id = ?",
            DERIVED_DATA_VERSION - 1,
            run.id,
        )
        val bestEfforts =
            jdbcTemplate.queryForObject("SELECT count(*) FROM activity_best_effort WHERE activity_id = ?", Int::class.java, run.id)!!
        assertTrue(bestEfforts > 0, "the run already holds Best Efforts")

        val first = worker.drain(onlyUserIds = listOf(runner.id!!))
        val second = worker.drain(onlyUserIds = listOf(runner.id!!))

        assertEquals(1, first.activitiesRecomputed)
        assertEquals(durationsUpTo(1200), peaks(run).keys.sorted())
        assertEquals(0, second.activitiesRecomputed)
    }
}
