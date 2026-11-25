package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
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
 * `GET /api/athlete/marathon-shape` (#44): the signed-in athlete's daily **Marathon Shape**, continued to today, never
 * another athlete's.
 *
 * The 40-minute run 10 days ago (9.6 km) gives an Effective VO2max of 55.540, so the weekly target is 95.528 km and
 * the target long run 25.570 km. With a 20 km run 5 days ago, today has 29.6 km over 11 days, averaged over 4 weeks:
 * 7.4 km a week, a Volume Score of 7.4 / 95.528 = 0.07746; the long run scores (1 − 5/70)·((20 − 13) / 12.570)² =
 * 0.2880 points, a Long-Run Score of 0.2880 / 5.5 = 0.05236; Marathon Shape 100·(⅔·0.07746 + ⅓·0.05236) = 6.910.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "shape_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class MarathonShapeApiIntegrationTest {
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
        runner = save("shape_runner")
        other = save("shape_other")
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
                    externalId = "shape-$daysAgo-$km",
                ),
            ),
            owner.id!!,
        )
    }

    @Test
    @WithAnonymousUser
    fun `a signed-out request is refused`() {
        mockMvc.perform(get("/api/athlete/marathon-shape")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an athlete without an Effective VO2max has no Marathon Shape`() {
        importLongRun(3, 20.0)

        mockMvc
            .perform(get("/api/athlete/marathon-shape"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current").value(nullValue()))
            .andExpect(jsonPath("$.days", hasSize<Any>(0)))
    }

    @Test
    fun `the current value and one day each from the first Effective VO2max, continued to today`() {
        importQualifying(10)
        importLongRun(5, 20.0)

        mockMvc
            .perform(get("/api/athlete/marathon-shape"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.day").value(today.toString()))
            .andExpect(jsonPath("$.current.pct").value(closeTo(6.910, 0.01), Double::class.java))
            .andExpect(jsonPath("$.current.volumeScore").value(closeTo(0.07746, 0.0001), Double::class.java))
            .andExpect(jsonPath("$.current.longRunScore").value(closeTo(0.05236, 0.0001), Double::class.java))
            .andExpect(jsonPath("$.current.weeklyKm").value(closeTo(7.4, 1e-9), Double::class.java))
            .andExpect(jsonPath("$.current.targetWeeklyKm").value(closeTo(95.528, 0.05), Double::class.java))
            .andExpect(jsonPath("$.current.targetLongRunKm").value(closeTo(25.570, 0.01), Double::class.java))
            .andExpect(jsonPath("$.days", hasSize<Any>(11)))
            .andExpect(jsonPath("$.days[0].day").value(today.minusDays(10).toString()))
            // 9.6 km over 4 weeks: 100 · ⅔ · 2.4 / 95.528 = 1.675.
            .andExpect(jsonPath("$.days[0].pct").value(closeTo(1.675, 0.003), Double::class.java))
            // The day of the long run, the last stored row: 100 · (⅔ · 7.4 / 95.528 + ⅓ · 0.3101 / 5.5) = 7.044.
            .andExpect(jsonPath("$.days[5].pct").value(closeTo(7.044, 0.01), Double::class.java))
            .andExpect(jsonPath("$.days[10].day").value(today.toString()))
            .andExpect(jsonPath("$.days[10].pct").value(closeTo(6.910, 0.01), Double::class.java))
    }

    @Test
    fun `there is no Marathon Shape once the Effective VO2max runs out, 30 days after the last estimate`() {
        importQualifying(40)

        mockMvc
            .perform(get("/api/athlete/marathon-shape"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(41)))
            // The last day with an Effective VO2max, 30 days after the first Run: 100 · ⅔ · (9.6 · 7/30) / 95.528 = 1.563.
            .andExpect(jsonPath("$.days[29].pct").value(closeTo(1.563, 0.003), Double::class.java))
            .andExpect(jsonPath("$.days[30].pct").value(nullValue()))
            .andExpect(jsonPath("$.current").value(nullValue()))
    }

    @Test
    fun `from and to bound the days, and to is capped at today`() {
        importQualifying(10)
        importLongRun(5, 20.0)

        mockMvc
            .perform(
                get("/api/athlete/marathon-shape")
                    .param("from", today.minusDays(6).toString())
                    .param("to", today.minusDays(5).toString()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(2)))
            .andExpect(jsonPath("$.days[0].day").value(today.minusDays(6).toString()))
            .andExpect(jsonPath("$.days[1].pct").value(closeTo(7.044, 0.01), Double::class.java))
            .andExpect(jsonPath("$.current.pct").value(closeTo(6.910, 0.01), Double::class.java))
        mockMvc
            .perform(get("/api/athlete/marathon-shape").param("to", today.plusDays(30).toString()))
            .andExpect(jsonPath("$.days", hasSize<Any>(11)))
    }

    @Test
    fun `a from after to, or a malformed date, is refused`() {
        mockMvc
            .perform(get("/api/athlete/marathon-shape").param("from", "2026-03-10").param("to", "2026-03-01"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.from").exists())
        mockMvc
            .perform(get("/api/athlete/marathon-shape").param("to", "yesterday"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.to").exists())
    }

    @Test
    fun `an athlete never sees another athlete's Marathon Shape, nor counts their runs`() {
        importQualifying(10)
        importLongRun(5, 20.0, owner = other)
        importQualifying(3, owner = other)

        mockMvc
            .perform(get("/api/athlete/marathon-shape"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.days", hasSize<Any>(11)))
            .andExpect(jsonPath("$.current.weeklyKm").value(closeTo(2.4, 1e-9), Double::class.java))
            .andExpect(jsonPath("$.current.longRunScore").value(0.0))
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-03-30T12:00:00Z")

        @JvmStatic
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
