package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.analytics.WorkoutType
import com.jacobcraig.stridehearth.athlete.AthleteThresholds
import com.jacobcraig.stridehearth.athlete.DerivedDataRecomputeWorker
import com.jacobcraig.stridehearth.athlete.ThresholdKind
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
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A Run's **Workout Type** is derived data (ADR-0014): classified when it is imported, reclassified when the athlete's
 * Thresholds change, and, because the Long Run rule compares it with the median Moving Time of the Runs before it,
 * reclassified by the recompute worker when those Runs change. It is served with the Activity and filters the list.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WorkoutTypeRefreshTest {
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
    private lateinit var workoutTypeRepository: WorkoutTypeRepository

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
        runner = userRepository.save(User(email = "type_runner@example.com", passwordHash = "x", username = "type_runner"))
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

    private fun steadyRun(seconds: Int): FitTelemetry = run(seconds) { 3.0f }

    private fun import(
        details: FitTelemetry,
        startedAt: String = "2026-03-20T08:00:00Z",
        externalId: String = "type-1",
    ): Activity {
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = ActivitySport.RUN,
                    title = "Type fixture",
                    startedAt = Instant.parse(startedAt),
                    distance = 8.0,
                    timeTakenSeconds = details.t.last(),
                    externalId = externalId,
                    telemetry = details,
                ),
            ),
            runner.id!!,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(runner.id!!, ActivitySource.FIT_UPLOAD, externalId)!!
    }

    private fun typeOf(activity: Activity): WorkoutType? = workoutTypeRepository.findType(activity.id!!)

    private fun pendingRequests(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM workout_type_request", Int::class.java)!!

    private fun drain() = worker.drain(onlyUserIds = listOf(runner.id!!))

    private fun thresholdPace(speed: Double) =
        athleteThresholds.record(runner.id!!, ThresholdKind.THRESHOLD_PACE, LocalDate.parse("2026-01-01"), speed)

    @Test
    fun `importing six reps at threshold pace classifies the Run as Intervals`() {
        thresholdPace(4.2)

        val run = import(repsRun())

        assertEquals(WorkoutType.INTERVALS, typeOf(run))
    }

    @Test
    fun `an easy steady Run is Easy`() {
        thresholdPace(4.2)

        assertEquals(WorkoutType.EASY, typeOf(import(steadyRun(1800))))
    }

    @Test
    fun `changing the threshold pace reclassifies the Run through the recompute request`() {
        val run = import(repsRun())
        // Without a threshold the reps cannot be judged to be at it.
        assertEquals(WorkoutType.EASY, typeOf(run))

        thresholdPace(4.2)
        drain()

        assertEquals(WorkoutType.INTERVALS, typeOf(run))

        thresholdPace(6.0)
        drain()

        assertEquals(WorkoutType.EASY, typeOf(run))
    }

    @Test
    fun `earlier Runs that raise the 28-day median make a Run Long only once the worker has served the request, and deleting them undoes it`() {
        val long = import(steadyRun(2400), startedAt = "2026-03-20T08:00:00Z", externalId = "long")
        assertEquals(WorkoutType.EASY, typeOf(long))

        val earlier =
            listOf("2026-03-10T08:00:00Z", "2026-03-12T08:00:00Z", "2026-03-14T08:00:00Z").mapIndexed { i, at ->
                import(steadyRun(1200), startedAt = at, externalId = "short-$i")
            }
        // The refresh never writes another Activity's derived data: it only leaves a request.
        assertEquals(WorkoutType.EASY, typeOf(long))
        assertEquals(true, pendingRequests() > 0)

        val first = drain()

        assertEquals(1, first.workoutTypesChanged)
        assertEquals(WorkoutType.LONG_RUN, typeOf(long))
        assertEquals(0, pendingRequests())

        earlier.forEach { activityService.deleteActivity(it.id!!, runner.id!!) }
        assertEquals(WorkoutType.LONG_RUN, typeOf(long))
        drain()

        assertEquals(WorkoutType.EASY, typeOf(long))
        assertEquals(0, pendingRequests())
    }

    @Test
    fun `a Run outside the 28 days after an Activity is not reclassified by it`() {
        val later = import(steadyRun(2400), startedAt = "2026-05-20T08:00:00Z", externalId = "later")
        listOf("2026-03-10T08:00:00Z", "2026-03-12T08:00:00Z", "2026-03-14T08:00:00Z").forEachIndexed { i, at ->
            import(steadyRun(1200), startedAt = at, externalId = "short-$i")
        }

        drain()

        assertEquals(WorkoutType.EASY, typeOf(later))
    }

    @Test
    fun `the summary and detail serve the type and the list filters by it`() {
        thresholdPace(4.2)
        val intervals = import(repsRun(), externalId = "reps")
        val easy = import(steadyRun(1800), startedAt = "2026-03-21T08:00:00Z", externalId = "easy")

        val summaries = activityService.getActivitySummaries(runner.id!!, ActivityFilter(), PageRequest.of(0, 10)).content
        assertEquals(WorkoutType.INTERVALS, summaries.single { it.id == intervals.id }.workoutType)
        assertEquals(WorkoutType.EASY, summaries.single { it.id == easy.id }.workoutType)
        assertEquals(WorkoutType.INTERVALS, assertNotNull(activityService.getActivityDetail(intervals.id!!, runner.id!!)).workoutType)

        val onlyIntervals =
            activityService.getActivitySummaries(runner.id!!, ActivityFilter(workoutType = WorkoutType.INTERVALS), PageRequest.of(0, 10))
        assertEquals(listOf(intervals.id), onlyIntervals.content.map { it.id })
        val none = activityService.getActivitySummaries(runner.id!!, ActivityFilter(workoutType = WorkoutType.RACE), PageRequest.of(0, 10))
        assertEquals(0, none.totalElements.toInt())
    }

    @Test
    fun `a Run turned into a walk has no type, and an edit's response is not stale`() {
        thresholdPace(4.2)
        val run = import(repsRun())
        assertEquals(WorkoutType.INTERVALS, typeOf(run))

        val asWalk =
            activityService.updateActivity(
                run.id!!,
                runner.id!!,
                ActivityUpdateRequestDTO(title = "Walk", sport = ActivitySport.WALK),
            )

        assertNull(assertNotNull(asWalk).workoutType)
        assertNull(typeOf(run))
    }

    @Test
    fun `the recompute worker classifies Runs stored at the previous version`() {
        thresholdPace(4.2)
        val run = import(repsRun())
        jdbcTemplate.update(
            "UPDATE activity_derived_metrics SET workout_type = NULL, wt_threshold_work_intervals = NULL, algorithm_version = ? WHERE activity_id = ?",
            DERIVED_DATA_VERSION - 1,
            run.id,
        )
        assertNull(typeOf(run))

        drain()

        assertEquals(WorkoutType.INTERVALS, typeOf(run))
    }
}
