package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.analytics.RacePrognosis
import com.jacobcraig.stridehearth.athlete.Vo2maxFixtures.importRun
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
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
 * `GET /api/athlete/race-prognosis` (#45): the signed-in athlete's **Race Prognosis** today and on each day, from
 * their stored Effective VO2max and Marathon Shape, continued to today, never another athlete's.
 *
 * The 40-minute run 10 days ago gives an Effective VO2max of 55.540, whose VDOT times are 5K 1093.0 s, 10K 2266.5 s,
 * Half 5013.4 s and Marathon 10467.7 s. With a 20 km run 5 days ago, today's Marathon Shape is 6.910%
 * ([MarathonShapeApiIntegrationTest]), so the endurance penalty is 1 + 0.20 · (d − 5000) / 37195 · 0.93090:
 * 10K × 1.025029, Half × 1.080576, Marathon × 1.186180.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "prognosis_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class RacePrognosisApiIntegrationTest {
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

    @Autowired
    private lateinit var fitnessService: AthleteFitnessService

    private lateinit var runner: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = save("prognosis_runner")
        other = save("prognosis_other")
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

    private fun importQualifying(
        daysAgo: Long,
        owner: User = runner,
    ) = activityImportService.importRun(activityRepository, owner.id!!, today.minusDays(daysAgo), Vo2maxFixtures.fast())

    private fun importLongRun(
        daysAgo: Long,
        km: Double,
        owner: User = runner,
    ) {
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "Long run",
                    startedAt = today.minusDays(daysAgo).atTime(8, 0).toInstant(ZoneOffset.UTC),
                    distance = km,
                    timeTakenSeconds = (km * 360).toInt(),
                    externalId = "prognosis-$daysAgo-$km",
                ),
            ),
            owner.id!!,
        )
    }

    @Test
    @WithAnonymousUser
    fun `a signed-out request is refused`() {
        mockMvc.perform(get("/api/athlete/race-prognosis")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an athlete without an Effective VO2max has no prognosis`() {
        importLongRun(3, 20.0)

        mockMvc
            .perform(get("/api/athlete/race-prognosis"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current").value(nullValue()))
            .andExpect(jsonPath("$.days", hasSize<Any>(0)))
    }

    @Test
    fun `today's predictions, from today's Effective VO2max and Marathon Shape`() {
        importQualifying(10)
        importLongRun(5, 20.0)

        mockMvc
            .perform(get("/api/athlete/race-prognosis"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.day").value(today.toString()))
            .andExpect(jsonPath("$.current.effectiveVo2max").value(closeTo(55.540, 0.01), Double::class.java))
            .andExpect(jsonPath("$.current.marathonShapePct").value(closeTo(6.910, 0.01), Double::class.java))
            .andExpect(jsonPath("$.current.predictions", hasSize<Any>(4)))
            .andExpect(jsonPath("$.current.predictions[0].distance").value("FIVE_K"))
            .andExpect(jsonPath("$.current.predictions[0].meters").value(5000.0))
            .andExpect(jsonPath("$.current.predictions[0].enduranceFactor").value(1.0))
            .andExpect(jsonPath("$.current.predictions[0].courseFactor").value(1.0))
            .andExpect(jsonPath("$.current.predictions[0].vdotSeconds").value(closeTo(1093.0, 1.0), Double::class.java))
            .andExpect(jsonPath("$.current.predictions[0].predictedSeconds").value(closeTo(1093.0, 1.0), Double::class.java))
            .andExpect(jsonPath("$.current.predictions[1].distance").value("TEN_K"))
            .andExpect(jsonPath("$.current.predictions[1].enduranceFactor").value(closeTo(1.025029, 1e-4), Double::class.java))
            .andExpect(jsonPath("$.current.predictions[1].predictedSeconds").value(closeTo(2323.2, 2.0), Double::class.java))
            .andExpect(jsonPath("$.current.predictions[2].distance").value("HALF_MARATHON"))
            .andExpect(jsonPath("$.current.predictions[2].meters").value(21097.5))
            .andExpect(jsonPath("$.current.predictions[2].predictedSeconds").value(closeTo(5417.3, 3.0), Double::class.java))
            .andExpect(jsonPath("$.current.predictions[3].distance").value("MARATHON"))
            .andExpect(jsonPath("$.current.predictions[3].enduranceFactor").value(closeTo(1.186180, 1e-4), Double::class.java))
            .andExpect(jsonPath("$.current.predictions[3].predictedSeconds").value(closeTo(12416.5, 5.0), Double::class.java))
    }

    @Test
    fun `each day is the prognosis of that day's stored Effective VO2max and Marathon Shape, continued to today`() {
        importQualifying(10)
        importLongRun(5, 20.0)

        val result =
            mockMvc
                .perform(get("/api/athlete/race-prognosis"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.days", hasSize<Any>(11)))
                .andExpect(jsonPath("$.days[0].day").value(today.minusDays(10).toString()))
                // Marathon Shape 1.675% 10 days ago: 10467.7 · (1 + 0.20 · 0.98325) = 12526.2.
                .andExpect(jsonPath("$.days[0].marathonSeconds").value(closeTo(12526.2, 5.0), Double::class.java))
                .andExpect(jsonPath("$.days[10].day").value(today.toString()))
                .andExpect(jsonPath("$.days[10].marathonSeconds").value(closeTo(12416.5, 5.0), Double::class.java))

        val stored = fitnessService.series(runner.id!!, null, null, today).days
        stored.forEachIndexed { i, day ->
            val expected = RacePrognosis.predictAll(day.effectiveVo2max!!, day.marathonShape!!.pct).map { it.predictedSeconds }
            listOf("fiveKSeconds", "tenKSeconds", "halfSeconds", "marathonSeconds").zip(expected).forEach { (field, seconds) ->
                result.andExpect(jsonPath("$.days[$i].day").value(day.day.toString()))
                result.andExpect(jsonPath("$.days[$i].$field").value(closeTo(seconds, 1e-6), Double::class.java))
            }
        }
    }

    @Test
    fun `days without an Effective VO2max are left out, 30 days after the last estimate`() {
        importQualifying(40)

        mockMvc
            .perform(get("/api/athlete/race-prognosis"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(30)))
            .andExpect(jsonPath("$.days[0].day").value(today.minusDays(40).toString()))
            .andExpect(jsonPath("$.days[29].day").value(today.minusDays(11).toString()))
            .andExpect(jsonPath("$.current").value(nullValue()))
    }

    @Test
    fun `race-day conditions derate today's predictions only, never the days`() {
        importQualifying(10)
        importLongRun(5, 20.0)

        mockMvc
            .perform(get("/api/athlete/race-prognosis").param("temperatureC", "25").param("elevationGainM", "200"))
            .andExpect(status().isOk)
            // 10K: 1.015 for 25 °C × (1 + 4 · 200 / 10000) = 1.0962; 2266.5 · 1.025029 · 1.0962 = 2546.7.
            .andExpect(jsonPath("$.current.predictions[1].courseFactor").value(closeTo(1.0962, 1e-9), Double::class.java))
            .andExpect(jsonPath("$.current.predictions[1].predictedSeconds").value(closeTo(2546.7, 2.0), Double::class.java))
            // Marathon: 1.015 × (1 + 800 / 42195) = 1.034244.
            .andExpect(jsonPath("$.current.predictions[3].courseFactor").value(closeTo(1.034244, 1e-6), Double::class.java))
            .andExpect(jsonPath("$.days[10].marathonSeconds").value(closeTo(12416.5, 5.0), Double::class.java))
            .andExpect(jsonPath("$.days[10].tenKSeconds").value(closeTo(2323.2, 2.0), Double::class.java))
    }

    @Test
    fun `from and to bound the days, and to is capped at today`() {
        importQualifying(10)
        importLongRun(5, 20.0)

        mockMvc
            .perform(
                get("/api/athlete/race-prognosis")
                    .param("from", today.minusDays(6).toString())
                    .param("to", today.minusDays(5).toString()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(2)))
            .andExpect(jsonPath("$.days[0].day").value(today.minusDays(6).toString()))
            .andExpect(jsonPath("$.current.day").value(today.toString()))
        mockMvc
            .perform(get("/api/athlete/race-prognosis").param("to", today.plusDays(30).toString()))
            .andExpect(jsonPath("$.days", hasSize<Any>(11)))
    }

    @Test
    fun `a from after to, a malformed date, or conditions out of range are refused`() {
        mockMvc
            .perform(get("/api/athlete/race-prognosis").param("from", "2026-03-10").param("to", "2026-03-01"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.from").exists())
        mockMvc
            .perform(get("/api/athlete/race-prognosis").param("to", "yesterday"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.to").exists())
        mockMvc
            .perform(get("/api/athlete/race-prognosis").param("temperatureC", "60").param("elevationGainM", "-1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.temperatureC").exists())
            .andExpect(jsonPath("$.fieldErrors.elevationGainM").exists())
        mockMvc
            .perform(get("/api/athlete/race-prognosis").param("temperatureC", "warm").param("elevationGainM", "5001"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.temperatureC").exists())
            .andExpect(jsonPath("$.fieldErrors.elevationGainM").exists())
        mockMvc
            .perform(get("/api/athlete/race-prognosis").param("temperatureC", "-30").param("elevationGainM", "5000"))
            .andExpect(status().isOk)
    }

    @Test
    fun `an athlete never sees another athlete's prognosis`() {
        importQualifying(3, owner = other)
        importLongRun(2, 30.0, owner = other)

        mockMvc
            .perform(get("/api/athlete/race-prognosis"))
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
