package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
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
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `GET /api/athlete/acclimation` (#43): the signed-in athlete's daily **Heat Acclimation** and **Altitude
 * Acclimation**, decayed without exposure to today, and never another athlete's. Percentages are whole steps of
 * 100/42: +3 an exposed day, -2 an unexposed one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "acclimation_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class AcclimationApiIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    /** Today is fixed, so a run that crosses midnight cannot see one day in the test and another in the controller. */
    @TestBean
    private lateinit var clock: Clock

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

    private lateinit var runner: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = save("acclimation_runner")
        other = save("acclimation_other")
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

    /** A 30-minute run on [day] whose watch reads 36 °C (30 °C ambient) at [altitudeM] m. */
    private fun importHot(
        day: LocalDate,
        altitudeM: Float = 30f,
        owner: User = runner,
    ) {
        val n = 1801
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "Acclimation fixture",
                    startedAt = day.atTime(8, 0).toInstant(ZoneOffset.UTC),
                    distance = 5.4,
                    timeTakenSeconds = 1800,
                    externalId = "acclimation-$day",
                    telemetry =
                        FitTelemetry(
                            t = IntArray(n) { it },
                            lons = DoubleArray(n) { -1.5 },
                            lats = DoubleArray(n) { 53.0 + it * 3.0 / 111_195.0 },
                            routeIndex = Array(n) { it },
                            speed = Array(n) { 3.0f },
                            distance = Array(n) { it * 3.0f },
                            altitude = Array(n) { altitudeM },
                            temperature = Array(n) { 36.toShort() },
                        ),
                ),
            ),
            owner.id!!,
        )
    }

    private val today: LocalDate = LocalDate.ofInstant(NOW, ZoneOffset.UTC)

    private fun steps(n: Int) = n * 100.0 / 42

    @Test
    @WithAnonymousUser
    fun `a signed-out request is refused`() {
        mockMvc.perform(get("/api/athlete/acclimation")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an athlete never exposed has no acclimation`() {
        mockMvc
            .perform(get("/api/athlete/acclimation"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current").value(nullValue()))
            .andExpect(jsonPath("$.days", hasSize<Any>(0)))
    }

    @Test
    fun `the series runs from the first exposed day and decays without exposure to today`() {
        val first = today.minusDays(6)
        importHot(first)
        importHot(first.plusDays(1), altitudeM = 900f)

        // Heat steps: 3, 6, then 4, 2, 0, 0, 0 (decayed in memory after the last Activity); altitude: 0, 3, then 1, 0.
        mockMvc
            .perform(get("/api/athlete/acclimation"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(7)))
            .andExpect(jsonPath("$.days[0].day").value(first.toString()))
            .andExpect(jsonPath("$.days[0].heatPct").value(closeTo(steps(3), 1e-9)))
            .andExpect(jsonPath("$.days[0].heatExposed").value(true))
            .andExpect(jsonPath("$.days[0].altitudeExposed").value(false))
            .andExpect(jsonPath("$.days[1].heatPct").value(closeTo(steps(6), 1e-9)))
            .andExpect(jsonPath("$.days[1].altitudePct").value(closeTo(steps(3), 1e-9)))
            .andExpect(jsonPath("$.days[1].altitudeExposed").value(true))
            .andExpect(jsonPath("$.days[2].heatPct").value(closeTo(steps(4), 1e-9)))
            .andExpect(jsonPath("$.days[2].heatExposed").value(false))
            .andExpect(jsonPath("$.days[3].heatPct").value(closeTo(steps(2), 1e-9)))
            .andExpect(jsonPath("$.days[3].altitudePct").value(0.0))
            .andExpect(jsonPath("$.days[6].day").value(today.toString()))
            .andExpect(jsonPath("$.days[6].heatPct").value(0.0))
            .andExpect(jsonPath("$.current.day").value(today.toString()))
            .andExpect(jsonPath("$.current.heatPct").value(0.0))
            .andExpect(jsonPath("$.current.altitudePct").value(0.0))
    }

    @Test
    fun `today's value decays from the last stored day`() {
        listOf(10L, 9L, 8L, 7L).forEach { importHot(today.minusDays(it)) }

        // 12 steps on the 7th day back, then 7 unexposed days: 12 - 14, clamped to 0; 3 days back it was 12 - 8 = 4.
        mockMvc
            .perform(
                get("/api/athlete/acclimation").param("from", today.minusDays(3).toString()).param("to", today.minusDays(3).toString()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(1)))
            .andExpect(jsonPath("$.days[0].heatPct").value(closeTo(steps(4), 1e-9)))
            .andExpect(jsonPath("$.current.heatPct").value(0.0))
    }

    @Test
    fun `from and to bound the series, and to is capped at today`() {
        val first = today.minusDays(10)
        importHot(first)

        mockMvc
            .perform(get("/api/athlete/acclimation").param("from", first.plusDays(1).toString()).param("to", first.plusDays(2).toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(2)))
            .andExpect(jsonPath("$.days[0].day").value(first.plusDays(1).toString()))
            .andExpect(jsonPath("$.days[0].heatPct").value(closeTo(steps(1), 1e-9)))
        mockMvc
            .perform(
                get("/api/athlete/acclimation").param("from", first.minusDays(30).toString()).param("to", today.plusDays(30).toString()),
            ).andExpect(jsonPath("$.days", hasSize<Any>(11)))
            .andExpect(jsonPath("$.days[0].day").value(first.toString()))
            .andExpect(jsonPath("$.days[10].day").value(today.toString()))
    }

    @Test
    fun `a from after to, or a malformed date, is refused`() {
        mockMvc
            .perform(get("/api/athlete/acclimation").param("from", "2026-03-10").param("to", "2026-03-01"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.from").exists())
        mockMvc
            .perform(get("/api/athlete/acclimation").param("to", "yesterday"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.to").exists())
    }

    @Test
    fun `an athlete never sees another athlete's acclimation`() {
        importHot(today.minusDays(3), owner = other)

        mockMvc
            .perform(get("/api/athlete/acclimation"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current").value(nullValue()))
            .andExpect(jsonPath("$.days", hasSize<Any>(0)))
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-03-30T12:00:00Z")

        @JvmStatic
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
