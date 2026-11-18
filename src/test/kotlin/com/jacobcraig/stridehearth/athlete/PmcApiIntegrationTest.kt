package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.analytics.PmcCalculator
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.exp

/**
 * `GET /api/athlete/pmc` (#36): the signed-in athlete's dense daily **Performance Management Chart**, extended with
 * zero-load days to today, and never another athlete's.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "pmc_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class PmcApiIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var athleteThresholds: AthleteThresholds

    private lateinit var runner: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = save("pmc_runner")
        other = save("pmc_other")
        listOf(runner, other).forEach { owner ->
            athleteThresholds.record(owner.id!!, ThresholdKind.RESTING_HR, LocalDate.parse("2020-01-01"), 50.0)
            athleteThresholds.record(owner.id!!, ThresholdKind.MAX_HR, LocalDate.parse("2020-01-01"), 190.0)
            athleteThresholds.record(owner.id!!, ThresholdKind.LTHR, LocalDate.parse("2020-01-01"), 170.0)
        }
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll(listOf(runner, other))
    }

    private fun save(username: String) =
        userRepository.save(
            User(email = "$username@example.com", passwordHash = passwordEncoder.encode("secret123")!!, username = username),
        )

    /** An hour at 170 bpm on [day] at 08:00 UTC: a Training Load of 100. */
    private fun importHour(
        day: LocalDate,
        owner: User = runner,
    ) {
        val n = 3601
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "PMC fixture",
                    startedAt = day.atTime(8, 0).toInstant(ZoneOffset.UTC),
                    distance = 10.8,
                    timeTakenSeconds = 3600,
                    externalId = "pmc-$day",
                    telemetry =
                        FitTelemetry(
                            t = IntArray(n) { it },
                            lons = DoubleArray(n) { -1.5 },
                            lats = DoubleArray(n) { 53.0 + it * 3.0 / 111_195.0 },
                            routeIndex = Array(n) { it },
                            speed = Array(n) { 3.0f },
                            distance = Array(n) { it * 3.0f },
                            altitude = Array(n) { 30.0f },
                            heartRate = Array(n) { 170.toShort() },
                        ),
                ),
            ),
            owner.id!!,
        )
    }

    private val today: LocalDate get() = LocalDate.now(ZoneOffset.UTC)

    @Test
    @WithAnonymousUser
    fun `a signed-out request is refused`() {
        mockMvc.perform(get("/api/athlete/pmc")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an athlete without a scored Activity gets no days`() {
        mockMvc
            .perform(get("/api/athlete/pmc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timeZone").value("UTC"))
            .andExpect(jsonPath("$.firstDay").value(nullValue()))
            .andExpect(jsonPath("$.days", hasSize<Any>(0)))
    }

    @Test
    fun `the series is dense from the first scored day and extends to today with zero-load days`() {
        val first = today.minusDays(10)
        importHour(first)
        importHour(first.plusDays(2))

        mockMvc
            .perform(get("/api/athlete/pmc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.firstDay").value(first.toString()))
            .andExpect(jsonPath("$.days", hasSize<Any>(11)))
            .andExpect(jsonPath("$.days[0].date").value(first.toString()))
            .andExpect(jsonPath("$.days[0].trainingLoad").value(closeTo(100.0, 0.01)))
            .andExpect(jsonPath("$.days[0].activityCount").value(1))
            .andExpect(jsonPath("$.days[0].unscoredActivityCount").value(0))
            .andExpect(jsonPath("$.days[0].tsb").value(0.0))
            .andExpect(jsonPath("$.days[0].acwr").value(nullValue()))
            .andExpect(jsonPath("$.days[1].trainingLoad").value(0.0))
            .andExpect(jsonPath("$.days[1].activityCount").value(0))
            .andExpect(jsonPath("$.days[10].date").value(today.toString()))
            .andExpect(jsonPath("$.days[10].trainingLoad").value(0.0))
            // Two loads of 100 on days 0 and 2, then 8 rest days: CTL decays by e^(-1/42) a day.
            .andExpect(jsonPath("$.days[10].ctl").value(closeTo(expectedCtl(10), 0.01)))
            .andExpect(jsonPath("$.days[10].monotony").value(nullValue()))
    }

    /** CTL [day] days after the first of two 100-load days two days apart, by the closed form. */
    private fun expectedCtl(day: Int): Double {
        val decay = exp(-1.0 / 42)
        return 100 * PmcCalculator.CTL_ALPHA * (Math.pow(decay, day.toDouble()) + Math.pow(decay, day - 2.0))
    }

    @Test
    fun `from and to bound the series, and to is capped at today`() {
        val first = today.minusDays(10)
        importHour(first)

        mockMvc
            .perform(get("/api/athlete/pmc").param("from", first.plusDays(3).toString()).param("to", first.plusDays(5).toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.firstDay").value(first.toString()))
            .andExpect(jsonPath("$.days", hasSize<Any>(3)))
            .andExpect(jsonPath("$.days[0].date").value(first.plusDays(3).toString()))
        mockMvc
            .perform(get("/api/athlete/pmc").param("from", first.minusDays(30).toString()).param("to", today.plusDays(30).toString()))
            .andExpect(jsonPath("$.days", hasSize<Any>(11)))
            .andExpect(jsonPath("$.days[0].date").value(first.toString()))
            .andExpect(jsonPath("$.days[10].date").value(today.toString()))
    }

    @Test
    fun `a from after to, or a malformed date, is refused`() {
        mockMvc
            .perform(get("/api/athlete/pmc").param("from", "2026-03-10").param("to", "2026-03-01"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.from").exists())
        mockMvc
            .perform(get("/api/athlete/pmc").param("from", "10/03/2026"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.from").exists())
        mockMvc
            .perform(get("/api/athlete/pmc").param("to", "yesterday"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.to").exists())
    }

    @Test
    fun `an athlete never sees another athlete's series`() {
        importHour(today.minusDays(3), owner = other)

        mockMvc
            .perform(get("/api/athlete/pmc"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.firstDay").value(nullValue()))
            .andExpect(jsonPath("$.days", hasSize<Any>(0)))
    }
}
