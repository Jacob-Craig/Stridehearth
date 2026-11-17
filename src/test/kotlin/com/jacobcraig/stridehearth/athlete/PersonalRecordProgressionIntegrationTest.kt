package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivityService
import com.jacobcraig.stridehearth.activities.BenchmarkDistance
import com.jacobcraig.stridehearth.activities.PersonalRecordRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.createdActivities
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import kotlin.test.assertEquals

/**
 * **Personal Record Progression**, `GET /api/athlete/personal-record-progression`: every Best Effort that beat all
 * earlier ones for its Benchmark Distance, in the order the Activities were run, whatever order they were imported in.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "progression_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class PersonalRecordProgressionIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var personalRecordRepository: PersonalRecordRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var runner: User
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = athlete("progression_runner")
        otherRunner = athlete("progression_other")
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun athlete(username: String): User =
        userRepository.save(
            User(email = "$username@example.com", passwordHash = passwordEncoder.encode("secret123")!!, username = username),
        )

    /** Imports a steady 5 km run taking [seconds], so its 5k Best Effort is exactly [seconds]. */
    private fun import5k(
        title: String,
        startedAt: String,
        seconds: Int,
        owner: User = runner,
    ): Activity =
        activityImportService
            .importParsed(
                listOf(
                    parsedActivity(
                        sport = ActivitySport.RUN,
                        title = title,
                        startedAt = Instant.parse(startedAt),
                        distance = 5.0,
                        timeTakenSeconds = seconds,
                        externalId = title,
                        telemetry =
                            FitTelemetry(
                                t = intArrayOf(0, seconds),
                                lons = doubleArrayOf(-0.16, -0.17),
                                lats = doubleArrayOf(51.51, 51.52),
                                routeIndex = arrayOf(0, 1),
                                distance = arrayOf(0.0f, 5000.0f),
                            ),
                    ),
                ),
                owner.id!!,
            ).createdActivities
            .single()

    private fun progression(): PersonalRecordProgressionResponseDTO {
        val body =
            mockMvc
                .perform(get("/api/athlete/personal-record-progression").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readValue(body, PersonalRecordProgressionResponseDTO::class.java)
    }

    private fun PersonalRecordProgressionResponseDTO.titlesFor(benchmark: BenchmarkDistance): List<String> =
        progressions.single { it.benchmarkDistance == benchmark }.records.map { it.activityTitle }

    @Test
    fun `out-of-order imports give the records in the order they were run, a tie leaving the earlier run holding it`() {
        val a = import5k("A March", "2026-03-01T08:00:00Z", 1500)
        import5k("D April", "2026-04-01T08:00:00Z", 1500)
        import5k("C February", "2026-02-01T08:00:00Z", 1550)
        import5k("B January", "2026-01-01T08:00:00Z", 1600)

        val result = progression()

        assertEquals(listOf("B January", "C February", "A March"), result.titlesFor(BenchmarkDistance.FIVE_KILOMETERS))
        assertEquals(
            listOf(1600.0, 1550.0, 1500.0),
            result.progressions
                .single { it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS }
                .records
                .map { it.elapsedTimeSeconds },
        )
        assertEquals(a.id, lastStepOf(result, BenchmarkDistance.FIVE_KILOMETERS))
        assertLastStepsArePersonalRecords(result)
    }

    @Test
    fun `deleting the record holder hands the step to the run that tied it later`() {
        val a = import5k("A March", "2026-03-01T08:00:00Z", 1500)
        val d = import5k("D April", "2026-04-01T08:00:00Z", 1500)
        import5k("C February", "2026-02-01T08:00:00Z", 1550)
        import5k("B January", "2026-01-01T08:00:00Z", 1600)

        activityService.deleteActivity(a.id!!, runner.id!!)
        val result = progression()

        assertEquals(listOf("B January", "C February", "D April"), result.titlesFor(BenchmarkDistance.FIVE_KILOMETERS))
        assertEquals(d.id, lastStepOf(result, BenchmarkDistance.FIVE_KILOMETERS))
        assertLastStepsArePersonalRecords(result)
    }

    @Test
    fun `another athlete's faster runs never appear`() {
        import5k("Mine", "2026-03-01T08:00:00Z", 1500)
        import5k("Theirs", "2026-01-01T08:00:00Z", 1200, owner = otherRunner)

        assertEquals(listOf("Mine"), progression().titlesFor(BenchmarkDistance.FIVE_KILOMETERS))
    }

    @Test
    fun `an athlete without records gets every Benchmark Distance, shortest first, with no records`() {
        mockMvc
            .perform(get("/api/athlete/personal-record-progression").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.progressions", hasSize<Any>(6)))
            .andExpect(
                jsonPath(
                    "$.progressions[*].benchmarkDistance",
                    contains("FOUR_HUNDRED_METERS", "ONE_KILOMETER", "ONE_MILE", "FIVE_KILOMETERS", "TEN_KILOMETERS", "HALF_MARATHON"),
                ),
            ).andExpect(jsonPath("$.progressions[0].benchmarkName").value("400m"))
            .andExpect(jsonPath("$.progressions[0].distanceMeters").value(400.0))
            .andExpect(jsonPath("$.progressions[0].records", empty<Any>()))
    }

    @Test
    fun `a record carries its Activity and raw primitives`() {
        val run = import5k("Race", "2026-03-01T08:00:00Z", 1500)

        mockMvc
            .perform(get("/api/athlete/personal-record-progression").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.progressions[3].records[0].activityId").value(run.id!!))
            .andExpect(jsonPath("$.progressions[3].records[0].activityTitle").value("Race"))
            .andExpect(jsonPath("$.progressions[3].records[0].activityStartedAt").value("2026-03-01T08:00:00Z"))
            .andExpect(jsonPath("$.progressions[3].records[0].elapsedTimeSeconds").value(1500.0))
    }

    @Test
    @WithAnonymousUser
    fun `an unauthenticated request is refused`() {
        mockMvc
            .perform(get("/api/athlete/personal-record-progression").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }

    private fun lastStepOf(
        result: PersonalRecordProgressionResponseDTO,
        benchmark: BenchmarkDistance,
    ): Long =
        result.progressions
            .single { it.benchmarkDistance == benchmark }
            .records
            .last()
            .activityId

    /** Every Benchmark Distance's last step is the athlete's current Personal Record for it. */
    private fun assertLastStepsArePersonalRecords(result: PersonalRecordProgressionResponseDTO) {
        val records = personalRecordRepository.findByUserIdAndSport(runner.id!!, ActivitySport.RUN).associateBy { it.benchmarkDistance }
        for (progression in result.progressions) {
            val record = records[progression.benchmarkDistance]
            val last = progression.records.lastOrNull()
            assertEquals(record?.activityId, last?.activityId, progression.benchmarkName)
            assertEquals(record?.elapsedTimeSeconds, last?.elapsedTimeSeconds, progression.benchmarkName)
        }
    }
}
