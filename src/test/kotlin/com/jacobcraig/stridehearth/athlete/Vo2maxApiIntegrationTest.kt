package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.athlete.Vo2maxFixtures.importRun
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
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
 * `GET /api/athlete/vo2max` (#42): the signed-in athlete's daily **Effective VO2max**, continued to today, and their
 * Runs' **VO2max Estimates**, never another athlete's. Expected values are hand-computed in [Vo2maxFixtures].
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "vo2max_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class Vo2maxApiIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    /** Today is fixed, so the continuation to today is deterministic. */
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

    @Autowired
    private lateinit var athleteThresholds: AthleteThresholds

    private lateinit var runner: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = save("vo2max_runner")
        other = save("vo2max_other")
        listOf(runner, other).forEach {
            athleteThresholds.record(it.id!!, ThresholdKind.MAX_HR, LocalDate.parse("2026-01-01"), 190.0)
            athleteThresholds.record(it.id!!, ThresholdKind.RESTING_HR, LocalDate.parse("2026-01-01"), 50.0)
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

    private val today: LocalDate = LocalDate.ofInstant(NOW, ZoneOffset.UTC)

    private fun import(
        daysAgo: Long,
        details: FitTelemetry = Vo2maxFixtures.fast(),
        owner: User = runner,
    ): Long = activityImportService.importRun(activityRepository, owner.id!!, today.minusDays(daysAgo), details)

    private val mean = (Vo2maxFixtures.FAST_RAW + Vo2maxFixtures.EASY_RAW) / 2

    @Test
    @WithAnonymousUser
    fun `a signed-out request is refused`() {
        mockMvc.perform(get("/api/athlete/vo2max")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an athlete without an estimate has no Effective VO2max`() {
        mockMvc
            .perform(get("/api/athlete/vo2max"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current").value(nullValue()))
            .andExpect(jsonPath("$.days", hasSize<Any>(0)))
            .andExpect(jsonPath("$.runs", hasSize<Any>(0)))
    }

    @Test
    fun `two qualifying runs give the current value, one day each from the first, and both runs`() {
        val fast = import(10)
        val easy = import(5, Vo2maxFixtures.easy())

        mockMvc
            .perform(get("/api/athlete/vo2max"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(11)))
            .andExpect(jsonPath("$.days[0].day").value(today.minusDays(10).toString()))
            .andExpect(jsonPath("$.days[0].effectiveVo2max").value(closeTo(Vo2maxFixtures.FAST_RAW, 0.05)))
            .andExpect(jsonPath("$.days[0].correctionFactor").value(1.0))
            .andExpect(jsonPath("$.days[5].effectiveVo2max").value(closeTo(mean, 0.05)))
            .andExpect(jsonPath("$.days[10].day").value(today.toString()))
            .andExpect(jsonPath("$.days[10].effectiveVo2max").value(closeTo(mean, 0.05)))
            .andExpect(jsonPath("$.current.day").value(today.toString()))
            .andExpect(jsonPath("$.current.effectiveVo2max").value(closeTo(mean, 0.05)))
            .andExpect(jsonPath("$.current.correctionFactor").value(1.0))
            .andExpect(jsonPath("$.current.correctionSource").value(nullValue()))
            .andExpect(jsonPath("$.runs", hasSize<Any>(2)))
            .andExpect(jsonPath("$.runs[0].activityId").value(fast))
            .andExpect(jsonPath("$.runs[0].day").value(today.minusDays(10).toString()))
            .andExpect(jsonPath("$.runs[0].vo2maxEstimate").value(closeTo(Vo2maxFixtures.FAST_RAW, 0.05)))
            .andExpect(jsonPath("$.runs[1].activityId").value(easy))
            .andExpect(jsonPath("$.runs[1].vo2maxEstimate").value(closeTo(Vo2maxFixtures.EASY_RAW, 0.05)))
    }

    @Test
    fun `a 5K race calibrates the Correction Factor, which the current value and each run's estimate carry`() {
        import(10)
        import(5, Vo2maxFixtures.easy())
        val race = import(3, Vo2maxFixtures.race())
        val correction = Vo2maxFixtures.RACE_VDOT / mean

        mockMvc
            .perform(get("/api/athlete/vo2max"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.correctionSource.activityId").value(race))
            .andExpect(jsonPath("$.current.correctionSource.benchmarkDistance").value("FIVE_KILOMETERS"))
            .andExpect(jsonPath("$.current.correctionSource.vdot").value(closeTo(Vo2maxFixtures.RACE_VDOT, 0.3)))
            .andExpect(jsonPath("$.current.correctionFactor").value(closeTo(correction, 0.006)))
            .andExpect(jsonPath("$.current.effectiveVo2max").value(closeTo(Vo2maxFixtures.RACE_VDOT, 0.3)))
            // Runs before the race keep the factor of their own day.
            .andExpect(jsonPath("$.runs[0].vo2maxEstimate").value(closeTo(Vo2maxFixtures.FAST_RAW, 0.05)))
    }

    @Test
    fun `the value continues to today and is null 30 days after the last estimate`() {
        import(40)

        mockMvc
            .perform(get("/api/athlete/vo2max"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(41)))
            .andExpect(jsonPath("$.days[29].effectiveVo2max").value(closeTo(Vo2maxFixtures.FAST_RAW, 0.05)))
            .andExpect(jsonPath("$.days[30].effectiveVo2max").value(nullValue()))
            .andExpect(jsonPath("$.days[30].correctionFactor").value(1.0))
            .andExpect(jsonPath("$.days[40].day").value(today.toString()))
            .andExpect(jsonPath("$.current.effectiveVo2max").value(nullValue()))
    }

    @Test
    fun `from and to bound the days and runs, and to is capped at today`() {
        import(10)
        import(5, Vo2maxFixtures.easy())

        mockMvc
            .perform(
                get("/api/athlete/vo2max")
                    .param("from", today.minusDays(6).toString())
                    .param("to", today.minusDays(5).toString()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(2)))
            .andExpect(jsonPath("$.days[0].effectiveVo2max").value(closeTo(Vo2maxFixtures.FAST_RAW, 0.05)))
            .andExpect(jsonPath("$.days[1].effectiveVo2max").value(closeTo(mean, 0.05)))
            .andExpect(jsonPath("$.runs", hasSize<Any>(1)))
            .andExpect(jsonPath("$.current.effectiveVo2max").value(closeTo(mean, 0.05)))
        mockMvc
            .perform(get("/api/athlete/vo2max").param("to", today.plusDays(30).toString()))
            .andExpect(jsonPath("$.days", hasSize<Any>(11)))
    }

    @Test
    fun `a from after to, or a malformed date, is refused`() {
        mockMvc
            .perform(get("/api/athlete/vo2max").param("from", "2026-03-10").param("to", "2026-03-01"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.from").exists())
        mockMvc
            .perform(get("/api/athlete/vo2max").param("from", "last week"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.from").exists())
    }

    @Test
    fun `an athlete never sees another athlete's Effective VO2max or runs`() {
        import(3, owner = other)

        mockMvc
            .perform(get("/api/athlete/vo2max"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current").value(nullValue()))
            .andExpect(jsonPath("$.days", hasSize<Any>(0)))
            .andExpect(jsonPath("$.runs", hasSize<Any>(0)))
    }

    @Test
    fun `a Run's detail carries its estimate, raw and corrected`() {
        import(10)
        import(5, Vo2maxFixtures.easy())
        import(3, Vo2maxFixtures.race())
        val after = import(1, Vo2maxFixtures.easy())
        val correction = Vo2maxFixtures.RACE_VDOT / mean

        mockMvc
            .perform(get("/api/activities/$after"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runMetrics.vo2maxEstimateRaw").value(closeTo(Vo2maxFixtures.EASY_RAW, 0.05)))
            .andExpect(jsonPath("$.runMetrics.vo2maxEstimate").value(closeTo(Vo2maxFixtures.EASY_RAW * correction, 0.4)))
            .andExpect(jsonPath("$.runMetrics.vo2maxUnavailableReason").value(nullValue()))
    }

    @Test
    fun `a Run's detail says why it has no estimate`() {
        val race = import(3, Vo2maxFixtures.race())

        mockMvc
            .perform(get("/api/activities/$race"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.runMetrics.vo2maxEstimateRaw").value(nullValue()))
            .andExpect(jsonPath("$.runMetrics.vo2maxEstimate").value(nullValue()))
            .andExpect(jsonPath("$.runMetrics.vo2maxUnavailableReason").value("TOO_SHORT"))
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-03-30T12:00:00Z")

        @JvmStatic
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
