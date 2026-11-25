package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityPeakEffortRepository
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.CriticalSpeedFit
import com.jacobcraig.stridehearth.analytics.CriticalSpeedStatus
import com.jacobcraig.stridehearth.analytics.PeakEffort
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * `GET /api/athlete/critical-speed`: the athlete's **Critical Speed** model (and **Critical Power** model) for today,
 * fitted live from their own Run Peak Efforts of the last 90 days. Always 200: a missing model is an explicit status.
 * Peak Efforts follow `v = CS + D′ / t`, so the fitted values are known by hand.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "cs_api_athlete", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class CriticalSpeedApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var peakEffortRepository: ActivityPeakEffortRepository

    @Autowired
    private lateinit var modelRepository: CriticalSpeedModelRepository

    @Autowired
    private lateinit var criticalSpeedService: CriticalSpeedService

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var clock: Clock

    private lateinit var athlete: User
    private lateinit var otherAthlete: User

    private val today: LocalDate get() = LocalDate.now(clock)

    /** Fixes "now" a moment before UTC midnight, as the Peak Effort endpoint's test does. */
    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-03-01T23:59:59.950Z"), ZoneOffset.UTC)
    }

    @BeforeEach
    fun setUp() {
        athlete = user("cs_api_athlete")
        otherAthlete = user("cs_api_other")
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun user(username: String): User =
        userRepository.save(
            User(email = "$username@example.com", passwordHash = passwordEncoder.encode("secret123")!!, username = username),
        )

    private fun daysAgo(
        days: Long,
        at: LocalTime = LocalTime.NOON,
    ): Instant = today.minusDays(days).atTime(at).toInstant(ZoneOffset.UTC)

    /** A Run started at [startedAt] holding the given Peak Efforts, stored without any rollup. */
    private fun run(
        startedAt: Instant,
        owner: User = athlete,
        peaks: List<Triple<PeakEffortMetric, BenchmarkDuration, Double>>,
    ): Activity {
        val saved =
            activityRepository.save(
                Activity(
                    title = "Run",
                    sport = ActivitySport.RUN,
                    startedAt = startedAt,
                    distance = 5.0,
                    timeTakenSeconds = 1500,
                    user = owner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        peakEffortRepository.replace(
            saved.id!!,
            peaks.map { (metric, duration, value) -> PeakEffort(metric, duration, value, 0, 1, 0.0, duration.seconds.toDouble()) },
        )
        return saved
    }

    /** `critical + reserve / t` at each of [durations]. */
    private fun hyperbolic(
        critical: Double,
        reserve: Double,
        durations: List<BenchmarkDuration> = FIT,
        metric: PeakEffortMetric = PeakEffortMetric.GAP_SPEED,
    ) = durations.map { Triple(metric, it, critical + reserve / it.seconds) }

    private fun request() = mockMvc.perform(get("/api/athlete/critical-speed").accept(MediaType.APPLICATION_JSON))

    @Test
    fun `peaks from CS 4 m per s and D' 200 m over several Runs give that model, with its points`() {
        val shortEfforts = run(daysAgo(30), peaks = hyperbolic(4.0, 200.0, FIT.take(3)))
        val longEfforts = run(daysAgo(2), peaks = hyperbolic(4.0, 200.0, FIT.drop(3)))

        request()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.asOf").value(today.toString()))
            .andExpect(jsonPath("$.windowDays").value(90))
            .andExpect(jsonPath("$.pace.status").value("MODEL"))
            .andExpect(jsonPath("$.pace.criticalValue", closeTo(4.0, 1e-9)))
            .andExpect(jsonPath("$.pace.reserveCapacity", closeTo(200.0, 1e-6)))
            .andExpect(jsonPath("$.pace.rSquared", greaterThan(0.999)))
            .andExpect(jsonPath("$.pace.pointCount").value(5))
            .andExpect(jsonPath("$.pace.points[*].durationSeconds", contains(120, 180, 300, 600, 1200)))
            .andExpect(jsonPath("$.pace.points[0].benchmarkDuration").value("TWO_MINUTES"))
            .andExpect(jsonPath("$.pace.points[0].value", closeTo(4.0 + 200.0 / 120, 1e-9)))
            .andExpect(jsonPath("$.pace.points[0].activityId").value(shortEfforts.id!!))
            .andExpect(jsonPath("$.pace.points[4].activityId").value(longEfforts.id!!))
            .andExpect(jsonPath("$.power").value(nullValue()))
    }

    @Test
    fun `power peaks add a Critical Power model`() {
        run(daysAgo(1), peaks = hyperbolic(4.0, 200.0) + hyperbolic(300.0, 20_000.0, metric = PeakEffortMetric.POWER))

        request()
            .andExpect(jsonPath("$.power.status").value("MODEL"))
            .andExpect(jsonPath("$.power.criticalValue", closeTo(300.0, 1e-6)))
            .andExpect(jsonPath("$.power.reserveCapacity", closeTo(20_000.0, 1e-4)))
            .andExpect(jsonPath("$.power.pointCount").value(5))
    }

    @Test
    fun `too few points return an explicit no-model result with null values`() {
        run(daysAgo(1), peaks = hyperbolic(4.0, 200.0, FIT.take(2)))

        request()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pace.status").value("INSUFFICIENT_POINTS"))
            .andExpect(jsonPath("$.pace.criticalValue").value(nullValue()))
            .andExpect(jsonPath("$.pace.reserveCapacity").value(nullValue()))
            .andExpect(jsonPath("$.pace.rSquared").value(nullValue()))
            .andExpect(jsonPath("$.pace.pointCount").value(2))
            .andExpect(jsonPath("$.pace.points", hasSize<Any>(2)))
    }

    @Test
    fun `an athlete without Runs gets a no-model result`() {
        request()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pace.status").value("INSUFFICIENT_POINTS"))
            .andExpect(jsonPath("$.pace.pointCount").value(0))
            .andExpect(jsonPath("$.pace.points", empty<Any>()))
            .andExpect(jsonPath("$.power").value(nullValue()))
    }

    @Test
    fun `a non-physical fit reports its R squared but no values`() {
        // Equal speeds up to 10 min and a faster 20 min: D' = -57.69 m, R^2 = 0.99943 by hand.
        run(daysAgo(1), peaks = FIT.map { Triple(PeakEffortMetric.GAP_SPEED, it, if (it.seconds == 1200) 4.2 else 4.0) })

        request()
            .andExpect(jsonPath("$.pace.status").value("NON_PHYSICAL"))
            .andExpect(jsonPath("$.pace.criticalValue").value(nullValue()))
            .andExpect(jsonPath("$.pace.rSquared", closeTo(0.999426, 1e-6)))
    }

    @Test
    fun `the window includes a peak 89 days old and excludes one 90 days old`() {
        run(daysAgo(0), peaks = hyperbolic(4.0, 200.0, FIT.take(2)))
        val inside = run(daysAgo(89, LocalTime.MIDNIGHT), peaks = hyperbolic(4.0, 200.0, listOf(FIT[2])))
        run(daysAgo(90, LocalTime.of(23, 59, 59)), peaks = hyperbolic(4.0, 200.0, listOf(FIT[3])))

        request()
            .andExpect(jsonPath("$.pace.pointCount").value(3))
            .andExpect(jsonPath("$.pace.points[*].durationSeconds", contains(120, 180, 300)))
            .andExpect(jsonPath("$.pace.points[2].activityId").value(inside.id!!))
    }

    @Test
    fun `another athlete's peaks are never used`() {
        run(daysAgo(1), owner = otherAthlete, peaks = hyperbolic(5.0, 300.0))
        run(daysAgo(1), peaks = hyperbolic(4.0, 200.0, FIT.take(2)))

        request()
            .andExpect(jsonPath("$.pace.status").value("INSUFFICIENT_POINTS"))
            .andExpect(jsonPath("$.pace.pointCount").value(2))
    }

    @Test
    fun `the request stores nothing`() {
        run(daysAgo(1), peaks = hyperbolic(4.0, 200.0))

        request().andExpect(jsonPath("$.pace.status").value("MODEL"))

        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM athlete_critical_speed_model", Int::class.java))
    }

    @Test
    fun `the model in effect on a day is the stored one, else a live fit`() {
        run(daysAgo(1), peaks = hyperbolic(4.0, 200.0))
        val stored = CriticalSpeedFit(CriticalSpeedStatus.MODEL, 3.5, 150.0, 0.99, 4)
        transactionTemplate.execute {
            modelRepository.replace(
                athlete.id!!,
                today,
                today,
                listOf(StoredCriticalSpeedModel(PeakEffortMetric.GAP_SPEED, today, stored)),
            )
        }

        assertEquals(stored, criticalSpeedService.inEffect(athlete.id!!, PeakEffortMetric.GAP_SPEED, today))
        val live = criticalSpeedService.inEffect(athlete.id!!, PeakEffortMetric.GAP_SPEED, today.minusDays(1))
        assertEquals(CriticalSpeedStatus.MODEL, live.status)
        assertEquals(4.0, live.criticalValue!!, 1e-9)
    }

    @Test
    @WithAnonymousUser
    fun `an unauthenticated request is refused`() {
        request()
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }

    private companion object {
        val FIT =
            listOf(
                BenchmarkDuration.TWO_MINUTES,
                BenchmarkDuration.THREE_MINUTES,
                BenchmarkDuration.FIVE_MINUTES,
                BenchmarkDuration.TEN_MINUTES,
                BenchmarkDuration.TWENTY_MINUTES,
            )
    }
}
