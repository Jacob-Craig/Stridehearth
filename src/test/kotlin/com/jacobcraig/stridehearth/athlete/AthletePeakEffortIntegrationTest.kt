package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityPeakEffortRepository
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.PeakEffort
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
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
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * `GET /api/athlete/peak-efforts`: the athlete's best **Peak Effort** per metric and **Benchmark Duration**, all-time
 * and over the last 90 days, from their own Runs only.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "peak_athlete", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class AthletePeakEffortIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var peakEffortRepository: ActivityPeakEffortRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    private lateinit var athlete: User
    private lateinit var otherAthlete: User

    @Autowired
    private lateinit var clock: Clock

    /** The day the endpoint counts its window back from: the fixed clock's, never the wall clock's. */
    private val today: LocalDate get() = LocalDate.now(clock)

    /**
     * Fixes "now" a moment before UTC midnight, so the window's day cannot change between storing the Runs and the
     * request reading them.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-03-01T23:59:59.950Z"), ZoneOffset.UTC)
    }

    @BeforeEach
    fun setUp() {
        athlete = user("peak_athlete")
        otherAthlete = user("peak_other")
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

    /** A Run started at [startedAt] holding the given Peak Efforts, stored directly as the refresh would. */
    private fun run(
        title: String,
        startedAt: Instant,
        owner: User = athlete,
        vararg peaks: Triple<PeakEffortMetric, BenchmarkDuration, Double>,
    ): Activity {
        val saved =
            activityRepository.save(
                Activity(
                    title = title,
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

    private fun daysAgo(
        days: Long,
        at: LocalTime = LocalTime.NOON,
    ): Instant = today.minusDays(days).atTime(at).toInstant(ZoneOffset.UTC)

    private fun gap(
        duration: BenchmarkDuration,
        value: Double,
    ) = Triple(PeakEffortMetric.GAP_SPEED, duration, value)

    private fun power(
        duration: BenchmarkDuration,
        value: Double,
    ) = Triple(PeakEffortMetric.POWER, duration, value)

    @Test
    fun `the best peak per Benchmark Duration, all-time and over the last 90 days`() {
        val old = run("Old fast", daysAgo(200), athlete, gap(BenchmarkDuration.FIVE_MINUTES, 5.0))
        val recent = run("Recent", daysAgo(10), athlete, gap(BenchmarkDuration.FIVE_MINUTES, 4.5))
        // Another athlete's faster run never counts.
        run("Theirs", daysAgo(5), otherAthlete, gap(BenchmarkDuration.FIVE_MINUTES, 6.0))

        mockMvc
            .perform(get("/api/athlete/peak-efforts").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.windowDays").value(90))
            .andExpect(jsonPath("$.pace", hasSize<Any>(11)))
            .andExpect(jsonPath("$.pace[*].durationSeconds", contains(5, 15, 30, 60, 120, 180, 300, 600, 1200, 1800, 3600)))
            .andExpect(jsonPath("$.pace[6].benchmarkDuration").value("FIVE_MINUTES"))
            .andExpect(jsonPath("$.pace[6].allTime.value").value(5.0))
            .andExpect(jsonPath("$.pace[6].allTime.activityId").value(old.id!!))
            .andExpect(jsonPath("$.pace[6].allTime.activityTitle").value("Old fast"))
            .andExpect(jsonPath("$.pace[6].recent.value").value(4.5))
            .andExpect(jsonPath("$.pace[6].recent.activityId").value(recent.id!!))
            .andExpect(jsonPath("$.pace[0].allTime").value(nullValue()))
            .andExpect(jsonPath("$.pace[0].recent").value(nullValue()))
            .andExpect(jsonPath("$.power", empty<Any>()))
    }

    @Test
    fun `power slots appear once the athlete has any power peak`() {
        run("Powered", daysAgo(3), athlete, gap(BenchmarkDuration.ONE_MINUTE, 4.0), power(BenchmarkDuration.ONE_MINUTE, 320.0))

        mockMvc
            .perform(get("/api/athlete/peak-efforts").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.power", hasSize<Any>(11)))
            .andExpect(jsonPath("$.power[3].benchmarkDuration").value("ONE_MINUTE"))
            .andExpect(jsonPath("$.power[3].allTime.value").value(320.0))
            .andExpect(jsonPath("$.power[3].recent.value").value(320.0))
            .andExpect(jsonPath("$.power[4].allTime").value(nullValue()))
    }

    @Test
    fun `a tie goes to the earlier Activity`() {
        val earlier = run("Earlier", daysAgo(20), athlete, gap(BenchmarkDuration.ONE_MINUTE, 5.0))
        run("Later", daysAgo(10), athlete, gap(BenchmarkDuration.ONE_MINUTE, 5.0))

        mockMvc
            .perform(get("/api/athlete/peak-efforts").accept(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.pace[3].allTime.activityId").value(earlier.id!!))
            .andExpect(jsonPath("$.pace[3].recent.activityId").value(earlier.id!!))
    }

    @Test
    fun `the 90-day window includes the day 89 days ago and excludes the day 90 days ago`() {
        val inside = run("Day -89", daysAgo(89, LocalTime.MIDNIGHT), athlete, gap(BenchmarkDuration.ONE_MINUTE, 4.0))
        val outside = run("Day -90", daysAgo(90, LocalTime.of(23, 59, 59)), athlete, gap(BenchmarkDuration.FIVE_MINUTES, 4.0))

        mockMvc
            .perform(get("/api/athlete/peak-efforts").accept(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.pace[3].recent.activityId").value(inside.id!!))
            .andExpect(jsonPath("$.pace[6].allTime.activityId").value(outside.id!!))
            .andExpect(jsonPath("$.pace[6].recent").value(nullValue()))
    }

    @Test
    @WithAnonymousUser
    fun `an unauthenticated request is refused`() {
        mockMvc
            .perform(get("/api/athlete/peak-efforts").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }
}
