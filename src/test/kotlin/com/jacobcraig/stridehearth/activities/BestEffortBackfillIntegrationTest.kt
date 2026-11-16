package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.closeTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "backfill_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class BestEffortBackfillIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var activityBestEffortRepository: ActivityBestEffortRepository

    @Autowired
    private lateinit var personalRecordRepository: PersonalRecordRepository

    @Autowired
    private lateinit var bestEffortBackfillService: BestEffortBackfillService

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var runner: User
    private lateinit var secondRunner: User

    @BeforeEach
    fun setUp() {
        runner =
            userRepository.save(
                User(
                    email = "backfill_runner@example.com",
                    passwordHash = "x",
                    username = "backfill_runner",
                ),
            )
        secondRunner =
            userRepository.save(
                User(
                    email = "second_runner@example.com",
                    passwordHash = "x",
                    username = "second_runner",
                ),
            )
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun seedHistoricalActivityWithTelemetry(
        owner: User,
        title: String,
        sport: ActivitySport,
        startedAt: Instant,
        distanceMeters: Float,
        durationSeconds: Int,
    ): Activity {
        val activity =
            activityRepository.save(
                Activity(
                    title = title,
                    sport = sport,
                    startedAt = startedAt,
                    distance = (distanceMeters / 1000.0),
                    timeTakenSeconds = durationSeconds,
                    user = owner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        activityDetailsRepository.upsert(
            activityId = activity.id!!,
            telemetry =
                FitTelemetry(
                    t = intArrayOf(0, durationSeconds),
                    lons = doubleArrayOf(-0.16, -0.17),
                    lats = doubleArrayOf(51.51, 51.52),
                    routeIndex = arrayOf(0, 1),
                    distance = arrayOf(0.0f, distanceMeters),
                ),
            parserVersion = ParserVersion(1),
        )
        return activity
    }

    @Test
    fun `backfillForUser scans only the athlete's RUN activities in pages, fills best efforts and personal records, idempotently`() {
        val run1 =
            seedHistoricalActivityWithTelemetry(
                owner = runner,
                title = "Historical 5k Slower",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2025-05-01T08:00:00Z"),
                distanceMeters = 5000.0f,
                durationSeconds = 1500,
            )
        val run2 =
            seedHistoricalActivityWithTelemetry(
                owner = runner,
                title = "Historical 10k Faster",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2025-06-01T08:00:00Z"),
                distanceMeters = 10000.0f,
                durationSeconds = 2400, // 5k in 1200s (20:00), 10k in 2400s (40:00)
            )
        val nonRun =
            seedHistoricalActivityWithTelemetry(
                owner = runner,
                title = "Historical Ride",
                sport = ActivitySport.RIDE,
                startedAt = Instant.parse("2025-07-01T08:00:00Z"),
                distanceMeters = 10000.0f,
                durationSeconds = 1200,
            )
        val otherUserRun =
            seedHistoricalActivityWithTelemetry(
                owner = secondRunner,
                title = "Second Runner 1k",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2025-08-01T08:00:00Z"),
                distanceMeters = 1000.0f,
                durationSeconds = 210,
            )
        val shortRun =
            seedHistoricalActivityWithTelemetry(
                owner = runner,
                title = "Sub-400m Stride",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2025-08-15T08:00:00Z"),
                distanceMeters = 250.0f,
                durationSeconds = 45,
            )
        val laterRun =
            seedHistoricalActivityWithTelemetry(
                owner = runner,
                title = "Historical 1k After The Other Athlete's",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2025-09-01T08:00:00Z"),
                distanceMeters = 1000.0f,
                durationSeconds = 240,
            )

        // pageSize = 1 runs one page per candidate, and the other athlete's candidate sits between the pages.
        val firstResult = bestEffortBackfillService.backfillForUser(runner.id!!, pageSize = 1)
        assertEquals(3, firstResult.activitiesProcessed) // run1, run2, laterRun: the ride and the stride are no candidates
        assertEquals(11, firstResult.bestEffortsCreated) // run1: 4 (400m,1k,1mi,5k), run2: 5 (+10k), laterRun: 2 (400m,1k)

        assertEquals(4, activityBestEffortRepository.findByActivityId(run1.id!!).size)
        assertEquals(5, activityBestEffortRepository.findByActivityId(run2.id!!).size)
        assertEquals(0, activityBestEffortRepository.findByActivityId(nonRun.id!!).size)
        assertEquals(0, activityBestEffortRepository.findByActivityId(shortRun.id!!).size)
        assertEquals(2, activityBestEffortRepository.findByActivityId(laterRun.id!!).size)

        val runnerSlots = personalRecordSlotsOf(runner)
        val slot5k = runnerSlots.single { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
        assertNotNull(slot5k.personalRecord)
        assertEquals(run2.id, slot5k.personalRecord.activityId)
        assertEquals(1200.0, slot5k.personalRecord.elapsedTimeSeconds, 1e-6)
        val slot1k = runnerSlots.single { it.benchmarkDistance == BenchmarkDistance.ONE_KILOMETER }
        assertNotNull(slot1k.personalRecord)
        assertEquals(run2.id, slot1k.personalRecord.activityId)

        // The other athlete's candidate Activity is left untouched: no Best Efforts and no Personal Records.
        assertEquals(0, activityBestEffortRepository.findByActivityId(otherUserRun.id!!).size)
        assertTrue(personalRecordSlotsOf(secondRunner).all { it.personalRecord == null })

        // A second run finds nothing left to do.
        val secondResult = bestEffortBackfillService.backfillForUser(runner.id!!, pageSize = 2)
        assertEquals(0, secondResult.activitiesProcessed)
        assertEquals(0, secondResult.bestEffortsCreated)
        assertEquals(11L, activityBestEffortRepository.count())
    }

    @Test
    fun `backfillForUser refuses to run inside a caller's transaction, since each page must commit on its own`() {
        seedHistoricalActivityWithTelemetry(
            owner = runner,
            title = "Historical 5k",
            sport = ActivitySport.RUN,
            startedAt = Instant.parse("2025-05-01T08:00:00Z"),
            distanceMeters = 5000.0f,
            durationSeconds = 1500,
        )

        assertThrows<IllegalTransactionStateException> {
            transactionTemplate.execute { bestEffortBackfillService.backfillForUser(runner.id!!) }
        }
        assertEquals(0L, activityBestEffortRepository.count())
    }

    @Test
    fun `POST api backfill-best-efforts backfills the athlete's historical running Activities into Dashboard Personal Records`() {
        val historicalRun =
            seedHistoricalActivityWithTelemetry(
                owner = runner,
                title = "Manage Triggered Historical 5k",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2025-09-01T08:00:00Z"),
                distanceMeters = 5000.0f,
                durationSeconds = 1260, // 21:00
            )

        mockMvc
            .perform(post("/api/activities/backfill-best-efforts").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activitiesProcessed").value(1))
            .andExpect(jsonPath("$.bestEffortsCreated").value(4))

        assertEquals(4, activityBestEffortRepository.findByActivityId(historicalRun.id!!).size)

        mockMvc
            .perform(get("/api/dashboard"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.personalRecordSlots[3].benchmarkDistance").value("FIVE_KILOMETERS"))
            .andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.activityTitle").value("Manage Triggered Historical 5k"))
            .andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.elapsedTimeSeconds").value(closeTo(1260.0, 0.5)))
    }

    @Test
    fun `backfillForUser processes historical RUN activity whose distance array has leading and trailing nulls`() {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Delayed GPS Lock 1k",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.parse("2025-10-01T08:00:00Z"),
                    distance = 1.0,
                    timeTakenSeconds = 260,
                    user = runner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        activityDetailsRepository.upsert(
            activityId = activity.id!!,
            telemetry =
                FitTelemetry(
                    t = intArrayOf(0, 10, 130, 250, 260),
                    lons = doubleArrayOf(-0.16, -0.17, -0.18),
                    lats = doubleArrayOf(51.51, 51.52, 51.53),
                    routeIndex = arrayOf(null, 0, 1, 2, null),
                    distance = arrayOf(null, 0.0f, 500.0f, 1000.0f, null),
                ),
            parserVersion = ParserVersion(1),
        )

        val result = bestEffortBackfillService.backfillForUser(runner.id!!)
        assertEquals(1, result.activitiesProcessed)
        assertEquals(2, result.bestEffortsCreated) // 400m and 1k

        val savedEfforts = activityBestEffortRepository.findByActivityId(activity.id!!)
        assertEquals(2, savedEfforts.size)
    }

    @Test
    fun `backfillForUser synthesizes distance from the stored GPS route for scanning, and does not persist it`() {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Apple Watch Export 1k",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.parse("2025-11-30T19:52:20Z"),
                    distance = 1.05,
                    timeTakenSeconds = 250,
                    user = runner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        // Stored with all-null distance array, mimicking FIT files that omit RecordMesg.distance
        // 0.005 deg lon at equator is ~555.97m; two steps of 0.005 deg = ~1111.95m in 250s
        activityDetailsRepository.upsert(
            activityId = activity.id!!,
            telemetry =
                FitTelemetry(
                    t = intArrayOf(0, 125, 250),
                    lons = doubleArrayOf(0.0, 0.005, 0.010),
                    lats = doubleArrayOf(0.0, 0.0, 0.0),
                    routeIndex = arrayOf(0, 1, 2),
                    enhancedSpeed = arrayOf(4.4f, 4.4f, 4.4f),
                    distance = arrayOf(null, null, null),
                ),
            parserVersion = ParserVersion(1),
        )

        val result = bestEffortBackfillService.backfillForUser(runner.id!!)
        assertEquals(1, result.activitiesProcessed)
        assertEquals(2, result.bestEffortsCreated) // 400m and 1k

        assertEquals(
            "{NULL,NULL,NULL}",
            jdbcClient
                .sql("SELECT distance::text FROM activity_details WHERE activity_id = :id")
                .param("id", activity.id!!)
                .query(String::class.java)
                .single(),
            "Distance is synthesized for scanning only; the stored details still lack distance",
        )

        val slots = personalRecordSlotsOf(runner)
        val slot1k = slots.single { it.benchmarkDistance == BenchmarkDistance.ONE_KILOMETER }
        assertNotNull(slot1k.personalRecord)
        assertEquals(activity.id, slot1k.personalRecord.activityId)
    }

    @Test
    fun `backfillForUser scans an Activity whose summary distance, not its recorded span, reaches the shortest Benchmark Distance`() {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Short Recorded Span, 1k Summary",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.parse("2025-12-01T08:00:00Z"),
                    distance = 1.0,
                    timeTakenSeconds = 300,
                    user = runner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        activityDetailsRepository.upsert(
            activityId = activity.id!!,
            telemetry =
                FitTelemetry(
                    t = intArrayOf(0, 300),
                    lons = doubleArrayOf(),
                    lats = doubleArrayOf(),
                    routeIndex = arrayOf(null, null),
                    distance = arrayOf(0.0f, 300.0f),
                ),
            parserVersion = ParserVersion(1),
        )

        val result = bestEffortBackfillService.backfillForUser(runner.id!!)

        assertEquals(1, result.activitiesProcessed)
        assertEquals(0, result.bestEffortsCreated) // 300 m recorded: no Benchmark Distance is covered
    }

    /** The athlete's running **Personal Record** slots exactly as the **Dashboard** API builds them. */
    private fun personalRecordSlotsOf(athlete: User): List<PersonalRecordSlotDTO> =
        buildPersonalRecordSlots(ActivitySport.RUN, personalRecordRepository.findByUserIdAndSport(athlete.id!!, ActivitySport.RUN))
}
