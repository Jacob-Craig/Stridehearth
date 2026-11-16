package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "dashboard_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class DashboardApiIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var runner: User
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner =
            userRepository.save(
                User(
                    email = "dashboard_runner@example.com",
                    passwordHash = passwordEncoder.encode("secret123")!!,
                    username = "dashboard_runner",
                ),
            )
        otherRunner =
            userRepository.save(
                User(
                    email = "dashboard_other@example.com",
                    passwordHash = passwordEncoder.encode("secret123")!!,
                    username = "dashboard_other",
                ),
            )
    }

    private fun importRun(
        owner: User,
        title: String,
        startedAt: String,
        distanceMeters: Float,
        totalSeconds: Int,
    ): Activity =
        activityImportService
            .importParsed(
                listOf(
                    parsedActivity(
                        sport = ActivitySport.RUN,
                        title = title,
                        startedAt = Instant.parse(startedAt),
                        distance = distanceMeters / 1000.0,
                        timeTakenSeconds = totalSeconds,
                        externalId = "$title-$startedAt",
                        telemetry =
                            FitTelemetry(
                                t = intArrayOf(0, totalSeconds),
                                lons = doubleArrayOf(-0.16, -0.17),
                                lats = doubleArrayOf(51.51, 51.52),
                                routeIndex = arrayOf(0, 1),
                                distance = arrayOf(0.0f, distanceMeters),
                            ),
                    ),
                ),
                owner.id!!,
            ).createdActivities
            .single()

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    @WithAnonymousUser
    fun `unauthenticated GET api dashboard returns 401 UNAUTHORIZED envelope`() {
        mockMvc
            .perform(get("/api/dashboard").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }

    @Test
    fun `athlete with no activities receives all six running benchmark slots with empty personal records`() {
        mockMvc
            .perform(get("/api/dashboard").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.personalRecordSlots", hasSize<Any>(6)))
            .andExpect(
                jsonPath(
                    "$.personalRecordSlots[*].benchmarkDistance",
                    contains(
                        "FOUR_HUNDRED_METERS",
                        "ONE_KILOMETER",
                        "ONE_MILE",
                        "FIVE_KILOMETERS",
                        "TEN_KILOMETERS",
                        "HALF_MARATHON",
                    ),
                ),
            ).andExpect(
                jsonPath(
                    "$.personalRecordSlots[*].benchmarkName",
                    contains("400m", "1k", "1 mile", "5k", "10k", "Half-Marathon"),
                ),
            ).andExpect(
                jsonPath(
                    "$.personalRecordSlots[*].distanceMeters",
                    contains(400.0, 1000.0, 1609.344, 5000.0, 10000.0, 21097.5),
                ),
            ).andExpect(jsonPath("$.personalRecordSlots[0].personalRecord").value(nullValue()))
            .andExpect(jsonPath("$.personalRecordSlots[5].personalRecord").value(nullValue()))
    }

    @Test
    fun `partial personal records populate achieved benchmarks with raw primitives and leave longer benchmarks empty`() {
        importRun(runner, "Steady 5k", "2026-04-05T08:00:00Z", 5000.0f, 1500)
        val fastRun = importRun(runner, "Fast 5k Race", "2026-04-10T09:00:00Z", 5000.0f, 1200)

        mockMvc
            .perform(get("/api/dashboard").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.personalRecordSlots[3].benchmarkDistance").value("FIVE_KILOMETERS"))
            .andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.activityId").value(fastRun.id!!))
            .andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.activityTitle").value("Fast 5k Race"))
            .andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.elapsedTimeSeconds").value(1200.0))
            .andExpect(
                jsonPath("$.personalRecordSlots[3].personalRecord.activityStartedAt").value("2026-04-10T09:00:00Z"),
            ).andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.bestEffortId").isNumber)
            .andExpect(jsonPath("$.personalRecordSlots[0].personalRecord.elapsedTimeSeconds").value(96.0))
            .andExpect(jsonPath("$.personalRecordSlots[4].personalRecord").value(nullValue()))
            .andExpect(jsonPath("$.personalRecordSlots[5].personalRecord").value(nullValue()))
            .andExpect(content().string(not(containsString("formatted"))))
    }

    @Test
    fun `a full set of personal records populates every benchmark slot`() {
        val halfMarathon = importRun(runner, "Half Marathon Race", "2026-05-01T07:00:00Z", 21097.5f, 6000)

        mockMvc
            .perform(get("/api/dashboard").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.personalRecordSlots[*].personalRecord.activityId", hasSize<Any>(6)))
            .andExpect(jsonPath("$.personalRecordSlots[5].personalRecord.activityId").value(halfMarathon.id!!))
            .andExpect(jsonPath("$.personalRecordSlots[5].personalRecord.elapsedTimeSeconds").value(6000.0))
    }

    @Test
    fun `another athlete's faster personal records never appear on the dashboard`() {
        importRun(runner, "My 5k", "2026-04-05T08:00:00Z", 5000.0f, 1500)
        importRun(otherRunner, "Other Athlete 10k", "2026-04-01T08:00:00Z", 10000.0f, 1800)

        mockMvc
            .perform(get("/api/dashboard").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.activityTitle").value("My 5k"))
            .andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.elapsedTimeSeconds").value(1500.0))
            .andExpect(jsonPath("$.personalRecordSlots[4].personalRecord").value(nullValue()))
            .andExpect(content().string(not(containsString("Other Athlete 10k"))))
    }
}
