package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Detected Intervals** are derived data: computed when a Run is created or Upgraded, removed when it stops being a
 * Run or is deleted, filled in for Runs stored before they existed by the recompute worker, and served with the
 * Activity (ADR-0014).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DetectedIntervalRefreshTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var detectedIntervalRepository: ActivityDetectedIntervalRepository

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
        runner = userRepository.save(User(email = "interval_runner@example.com", passwordHash = "x", username = "interval_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    /** A flat 1 Hz run: [speedAt] m/s at each second from 0 to [seconds], with a GPS fix per sample. */
    private fun run(
        seconds: Int,
        speedAt: (Int) -> Float,
    ): FitTelemetry {
        val n = seconds + 1
        val distance = FloatArray(n)
        for (i in 1 until n) distance[i] = distance[i - 1] + speedAt(i)
        return FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + distance[it] / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { speedAt(it) },
            distance = Array(n) { distance[it] },
            altitude = Array(n) { 30.0f },
            heartRate = Array(n) { 150.toShort() },
        )
    }

    /** 10 min easy, six 3 min reps at 4.5 m/s with 2 min jogs at 2.4 m/s, 10 min easy. */
    private fun repsRun(): FitTelemetry =
        run(600 + 6 * 300 + 600) { s ->
            val into = s - 600
            if (into < 0 || into >= 6 * 300) {
                3.0f
            } else if (into % 300 < 180) {
                4.5f
            } else {
                2.4f
            }
        }

    private fun steadyRun(): FitTelemetry = run(1800) { 3.0f }

    private fun import(
        details: FitTelemetry,
        sport: ActivitySport = ActivitySport.RUN,
        externalId: String = "interval-1",
        parserVersion: ParserVersion = FitActivityParser.PARSER_VERSION,
    ): Activity {
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = sport,
                    title = "Interval fixture",
                    startedAt = Instant.parse("2026-03-01T08:00:00Z"),
                    distance = 8.0,
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

    private fun intervals(activity: Activity) = detectedIntervalRepository.findByActivityId(activity.id!!)

    private fun rowCount(activity: Activity): Int =
        jdbcTemplate.queryForObject("SELECT count(*) FROM activity_detected_interval WHERE activity_id = ?", Int::class.java, activity.id)!!

    @Test
    fun `importing a Run with six reps stores its Detected Intervals in order`() {
        val run = import(repsRun())

        val stored = intervals(run)
        assertEquals(6, stored.count { it.interval.isWork })
        assertEquals(7, stored.count { !it.interval.isWork })
        assertEquals((1..13).toList(), stored.map { it.intervalNumber })
        assertEquals(stored.map { it.interval.startTimeSeconds }.sorted(), stored.map { it.interval.startTimeSeconds })
    }

    @Test
    fun `a steady Run stores no Detected Intervals`() {
        assertEquals(0, rowCount(import(steadyRun())))
    }

    @Test
    fun `a walk stores no Detected Intervals even with reps in it`() {
        assertEquals(0, rowCount(import(repsRun(), sport = ActivitySport.WALK)))
    }

    @Test
    fun `an Upgrade replaces the Detected Intervals with ones from the new Activity Details`() {
        val run = import(repsRun())
        assertTrue(rowCount(run) > 0)

        import(steadyRun(), parserVersion = ParserVersion(FitActivityParser.PARSER_VERSION.value + 1))

        assertEquals(0, rowCount(run))
    }

    @Test
    fun `reclassifying a Run as a walk removes its Detected Intervals, and back to a Run restores them`() {
        val run = import(repsRun())
        val before = intervals(run)

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Walk", sport = ActivitySport.WALK))
        assertEquals(0, rowCount(run))

        activityService.updateActivity(run.id!!, runner.id!!, ActivityUpdateRequestDTO(title = "Run", sport = ActivitySport.RUN))
        assertEquals(before, intervals(run))
    }

    @Test
    fun `deleting a Run removes its Detected Intervals`() {
        val run = import(repsRun())
        assertTrue(rowCount(run) > 0)

        activityService.deleteActivity(run.id!!, runner.id!!)

        assertEquals(0, rowCount(run))
    }

    @Test
    fun `the recompute worker fills in Detected Intervals for Runs stored at the previous version, once`() {
        val run = import(repsRun())
        val expected = intervals(run)
        jdbcTemplate.update("DELETE FROM activity_detected_interval WHERE activity_id = ?", run.id)
        jdbcTemplate.update(
            "UPDATE activity_derived_metrics SET algorithm_version = ? WHERE activity_id = ?",
            DERIVED_DATA_VERSION - 1,
            run.id,
        )

        val first = worker.drain(onlyUserIds = listOf(runner.id!!))
        val second = worker.drain(onlyUserIds = listOf(runner.id!!))

        assertEquals(1, first.activitiesRecomputed)
        assertEquals(expected, intervals(run))
        assertEquals(0, second.activitiesRecomputed)
    }

    @Test
    fun `the Activity detail serves a Run's Detected Intervals, and none for a steady Run`() {
        val reps = import(repsRun(), externalId = "reps")
        val steady = import(steadyRun(), externalId = "steady")

        val detail = assertNotNull(activityService.getActivityDetail(reps.id!!, runner.id!!))
        assertEquals(13, detail.detectedIntervals.size)
        assertEquals(6, detail.detectedIntervals.count { it.kind == DetectedIntervalKind.WORK })
        val rep = detail.detectedIntervals.first { it.kind == DetectedIntervalKind.WORK }
        assertEquals(4.5, rep.averageGapMetersPerSecond, 0.1)
        assertEquals(150.0, assertNotNull(rep.averageHeartRate), 1e-9)
        assertEquals(0, assertNotNull(activityService.getActivityDetail(steady.id!!, runner.id!!)).detectedIntervals.size)
    }
}
