package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
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
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@RecordApplicationEvents
@WithUserDetails(value = "threshold_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class AthleteThresholdsApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var athleteThresholds: AthleteThresholds

    @Autowired
    private lateinit var thresholdProposals: ThresholdProposals

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var events: ApplicationEvents

    @Autowired
    private lateinit var athleteTimeZone: AthleteTimeZone

    @Autowired
    private lateinit var clock: Clock

    /**
     * Fixes "now" at 23:30 UTC on 1 March 2026, half an hour before UTC midnight and already 2 March in a UTC+14 zone,
     * so "today" and "tomorrow" come from the injected clock and cannot straddle midnight mid-test.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class FixedClock {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-03-01T23:30:00Z"), ZoneOffset.UTC)
    }

    private lateinit var runner: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        runner = save("threshold_runner")
        other = save("threshold_other")
    }

    @AfterEach
    fun tearDown() {
        userRepository.deleteAll(listOf(runner, other))
    }

    private fun save(username: String) =
        userRepository.save(
            User(email = "$username@example.com", passwordHash = passwordEncoder.encode("secret123")!!, username = username),
        )

    private fun postEntry(
        kind: String,
        date: String,
        value: Double,
    ) = mockMvc.perform(
        post("/api/athlete/thresholds")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"kind":"$kind","effectiveDate":"$date","value":$value}"""),
    )

    private fun entryCount(userId: Long): Int =
        jdbcClient
            .sql("SELECT count(*) FROM athlete_threshold WHERE user_id = :u")
            .param("u", userId)
            .query(Int::class.java)
            .single()

    private fun thresholdEvents() = events.stream(ThresholdsChanged::class.java).toList()

    @Test
    @WithAnonymousUser
    fun `a signed-out request is refused`() {
        mockMvc.perform(get("/api/athlete/thresholds")).andExpect(status().isUnauthorized)
        mockMvc
            .perform(
                put("/api/athlete/time-zone").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"timeZone":"UTC"}"""),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `an athlete with no Thresholds gets an empty history and no zones`() {
        mockMvc
            .perform(get("/api/athlete/thresholds"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries", hasSize<Any>(0)))
            .andExpect(jsonPath("$.current.lthr").value(nullValue()))
            .andExpect(jsonPath("$.heartRateZones").value(nullValue()))
            .andExpect(jsonPath("$.paceZones").value(nullValue()))
            .andExpect(jsonPath("$.proposals", hasSize<Any>(0)))
    }

    @Test
    fun `entries are listed newest first with today's values and the zones they give`() {
        postEntry("LTHR", "2024-01-01", 160.0).andExpect(status().isOk)
        postEntry("LTHR", "2024-06-01", 170.0).andExpect(status().isOk)
        postEntry("MAX_HR", "2024-01-01", 192.0).andExpect(status().isOk)
        postEntry("THRESHOLD_PACE", "2024-01-01", 1000.0 / 240.0)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("THRESHOLD_PACE"))
            .andExpect(jsonPath("$.effectiveDate").value("2024-01-01"))

        mockMvc
            .perform(get("/api/athlete/thresholds"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries", hasSize<Any>(4)))
            .andExpect(jsonPath("$.entries[0].effectiveDate").value("2024-06-01"))
            .andExpect(jsonPath("$.current.lthr").value(170.0))
            .andExpect(jsonPath("$.current.maxHr").value(192.0))
            .andExpect(jsonPath("$.heartRateZones[1].lowerBpm").value(145))
            .andExpect(jsonPath("$.heartRateZones[4].upperBpm").value(192))
            .andExpect(jsonPath("$.heartRateZones[4].seilerZone").value(3))
            .andExpect(jsonPath("$.paceZones", hasSize<Any>(5)))
    }

    @Test
    fun `a second entry for the same kind and date replaces the first`() {
        postEntry("LTHR", "2024-01-01", 160.0).andExpect(status().isOk)
        postEntry("LTHR", "2024-01-01", 162.0).andExpect(status().isOk)

        assertEquals(1, entryCount(runner.id!!))
        assertEquals(162.0, athleteThresholds.asOf(runner.id!!, LocalDate.parse("2024-02-01")).lthr)
    }

    @Test
    fun `the service's as-of lookup reads the stored history`() {
        postEntry("LTHR", "2024-01-01", 160.0)
        postEntry("LTHR", "2024-06-01", 165.0)
        postEntry("LTHR", "2025-01-01", 170.0)

        val resolved =
            athleteThresholds.asOfMany(runner.id!!, listOf("2023-05-01", "2024-03-15", "2024-06-01", "2025-06-01").map(LocalDate::parse))

        assertEquals(listOf(160.0, 160.0, 165.0, 170.0), resolved.values.map { it.lthr })
    }

    @Test
    fun `one athlete never sees or changes another's Thresholds`() {
        val foreign = athleteThresholds.record(other.id!!, ThresholdKind.LTHR, LocalDate.parse("2024-01-01"), 150.0)
        val proposal = thresholdProposals.propose(other.id!!, ThresholdKind.LTHR, 155.0, LocalDate.parse("2024-02-01"), "TEST")!!

        mockMvc
            .perform(get("/api/athlete/thresholds"))
            .andExpect(jsonPath("$.entries", hasSize<Any>(0)))
            .andExpect(jsonPath("$.proposals", hasSize<Any>(0)))
        mockMvc.perform(delete("/api/athlete/thresholds/${foreign.id}").with(csrf())).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/athlete/thresholds/proposals/${proposal.id}/accept").with(csrf())).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/athlete/thresholds/proposals/${proposal.id}/dismiss").with(csrf())).andExpect(status().isNotFound)
        postEntry("LTHR", "2024-01-01", 175.0).andExpect(status().isOk)

        assertEquals(1, entryCount(other.id!!))
        assertEquals(150.0, athleteThresholds.asOf(other.id!!, LocalDate.parse("2024-01-01")).lthr)
        assertEquals(1, thresholdProposals.pending(other.id!!).size)
        assertEquals(175.0, athleteThresholds.asOf(runner.id!!, LocalDate.parse("2024-01-01")).lthr)
    }

    @Test
    fun `an athlete deletes their own entry`() {
        val entry = athleteThresholds.record(runner.id!!, ThresholdKind.LTHR, LocalDate.parse("2024-01-01"), 160.0)

        mockMvc.perform(delete("/api/athlete/thresholds/${entry.id}").with(csrf())).andExpect(status().isNoContent)

        assertEquals(0, entryCount(runner.id!!))
    }

    @Test
    fun `out-of-range values, a date after tomorrow and an unknown kind are refused field by field`() {
        postEntry("LTHR", "2024-01-01", 59.0)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.value").exists())
        postEntry("THRESHOLD_PACE", "2024-01-01", 8.5)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.value").exists())
        val dayAfterTomorrow = LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(2)
        postEntry("LTHR", dayAfterTomorrow.toString(), 160.0)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.effectiveDate").exists())
        postEntry("FTP", "2024-01-01", 250.0)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.kind").exists())

        assertEquals(0, entryCount(runner.id!!))
    }

    @Test
    fun `today's values and the latest acceptable date come from the injected clock`() {
        // 2026-03-01T23:30Z is 2 March in Kiritimati (UTC+14): the athlete's today. The wall clock is months later.
        athleteTimeZone.set(runner.id!!, "Pacific/Kiritimati")
        postEntry("LTHR", "2024-01-01", 160.0).andExpect(status().isOk)
        postEntry("LTHR", "2026-03-02", 170.0).andExpect(status().isOk)
        postEntry("LTHR", "2026-03-03", 180.0)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.effectiveDate").exists())
        assertNull(thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 180.0, LocalDate.parse("2026-03-03"), "CS_FIT"))
        // Recorded behind the API's back, as if accepted on a later day: not yet in force on the athlete's today.
        jdbcClient
            .sql("INSERT INTO athlete_threshold (user_id, kind, effective_date, value) VALUES (:u, 'LTHR', DATE '2026-04-01', 180)")
            .param("u", runner.id!!)
            .update()

        mockMvc
            .perform(get("/api/athlete/thresholds"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.current.lthr").value(170.0))
    }

    @Test
    fun `tomorrow is still accepted`() {
        postEntry("LTHR", LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(1).toString(), 160.0).andExpect(status().isOk)
    }

    @Test
    fun `a new entry announces the date it changes Thresholds from, or the beginning when it is the earliest`() {
        postEntry("LTHR", "2024-06-01", 165.0)
        postEntry("LTHR", "2024-09-01", 168.0)
        postEntry("LTHR", "2024-01-01", 160.0)

        assertEquals(
            listOf(
                ThresholdsChanged(runner.id!!, ThresholdKind.LTHR, null),
                ThresholdsChanged(runner.id!!, ThresholdKind.LTHR, LocalDate.parse("2024-09-01")),
                ThresholdsChanged(runner.id!!, ThresholdKind.LTHR, null),
            ),
            thresholdEvents(),
        )
    }

    @Test
    fun `deleting an entry announces its date, or the beginning when it was the earliest`() {
        val first = athleteThresholds.record(runner.id!!, ThresholdKind.LTHR, LocalDate.parse("2024-01-01"), 160.0)
        val second = athleteThresholds.record(runner.id!!, ThresholdKind.LTHR, LocalDate.parse("2024-06-01"), 165.0)
        events.clear()

        mockMvc.perform(delete("/api/athlete/thresholds/${second.id}").with(csrf())).andExpect(status().isNoContent)
        mockMvc.perform(delete("/api/athlete/thresholds/${first.id}").with(csrf())).andExpect(status().isNoContent)

        assertEquals(
            listOf(
                ThresholdsChanged(runner.id!!, ThresholdKind.LTHR, LocalDate.parse("2024-06-01")),
                ThresholdsChanged(runner.id!!, ThresholdKind.LTHR, null),
            ),
            thresholdEvents(),
        )
    }

    @Test
    fun `a proposal becomes an entry only when the athlete accepts it`() {
        val proposal = thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 168.0, LocalDate.parse("2024-03-01"), "CS_FIT")!!

        assertEquals(0, entryCount(runner.id!!))
        mockMvc
            .perform(get("/api/athlete/thresholds"))
            .andExpect(jsonPath("$.proposals", hasSize<Any>(1)))
            .andExpect(jsonPath("$.proposals[0].source").value("CS_FIT"))

        mockMvc
            .perform(post("/api/athlete/thresholds/proposals/${proposal.id}/accept").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value").value(168.0))

        assertEquals(168.0, athleteThresholds.asOf(runner.id!!, LocalDate.parse("2024-03-01")).lthr)
        assertEquals(0, thresholdProposals.pending(runner.id!!).size)
        assertEquals(listOf(ThresholdsChanged(runner.id!!, ThresholdKind.LTHR, null)), thresholdEvents())
    }

    @Test
    fun `a newer proposal from the same source replaces the older one`() {
        thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 168.0, LocalDate.parse("2024-03-01"), "CS_FIT")
        thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 171.0, LocalDate.parse("2024-04-01"), "CS_FIT")

        val pending = thresholdProposals.pending(runner.id!!)
        assertEquals(1, pending.size)
        assertEquals(171.0, pending.single().value)
    }

    @Test
    fun `a dismissed proposal is not offered again for a value within 3 percent`() {
        val proposal = thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 160.0, LocalDate.parse("2024-03-01"), "CS_FIT")!!
        mockMvc.perform(post("/api/athlete/thresholds/proposals/${proposal.id}/dismiss").with(csrf())).andExpect(status().isNoContent)

        // 164.6 is 2.9 % above 160: suppressed. 165.0 is 3.1 % above: offered again.
        assertNull(thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 164.6, LocalDate.parse("2024-04-01"), "CS_FIT"))
        assertEquals(0, thresholdProposals.pending(runner.id!!).size)
        thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 165.0, LocalDate.parse("2024-04-01"), "CS_FIT")
        assertEquals(1, thresholdProposals.pending(runner.id!!).size)
        assertEquals(0, entryCount(runner.id!!))
    }

    @Test
    fun `a proposal the athlete could not accept is skipped rather than thrown into the proposer's transaction`() {
        // Out of range (a degenerate fit), not a number, or dated after tomorrow (UTC): record() would refuse each.
        val tooLate = LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(2)
        assertNull(thresholdProposals.propose(runner.id!!, ThresholdKind.THRESHOLD_PACE, 0.9, LocalDate.parse("2024-03-01"), "CS_FIT"))
        assertNull(thresholdProposals.propose(runner.id!!, ThresholdKind.THRESHOLD_PACE, 8.5, LocalDate.parse("2024-03-01"), "CS_FIT"))
        assertNull(thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, Double.NaN, LocalDate.parse("2024-03-01"), "CS_FIT"))
        assertNull(thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 168.0, tooLate, "CS_FIT"))

        assertEquals(0, thresholdProposals.pending(runner.id!!).size)
        // Tomorrow is still acceptable, as it is for an entry.
        val tomorrow = LocalDate.now(clock.withZone(ZoneOffset.UTC)).plusDays(1)
        assertEquals(tomorrow, thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 168.0, tomorrow, "CS_FIT")?.effectiveDate)
    }

    @Test
    fun `an entry the athlete records themselves withdraws a pending proposal it already matches`() {
        thresholdProposals.propose(runner.id!!, ThresholdKind.THRESHOLD_PACE, 4.00, LocalDate.parse("2024-03-01"), "CS_FIT")
        thresholdProposals.propose(runner.id!!, ThresholdKind.LTHR, 170.0, LocalDate.parse("2024-03-01"), "TEST")

        // 3.90 m/s is 2.5 % from 4.00: the CS proposal is withdrawn; the LTHR one is another kind and stays.
        postEntry("THRESHOLD_PACE", "2024-01-01", 3.90).andExpect(status().isOk)

        assertEquals(listOf(ThresholdKind.LTHR), thresholdProposals.pending(runner.id!!).map { it.kind })
    }

    @Test
    fun `the time zone is set once, validated, and announced only when it changes`() {
        mockMvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.timeZone").value(nullValue()))

        mockMvc
            .perform(
                put(
                    "/api/athlete/time-zone",
                ).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"timeZone":"Europe/London"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.timeZone").value("Europe/London"))
        mockMvc
            .perform(
                put(
                    "/api/athlete/time-zone",
                ).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"timeZone":"Europe/London"}"""),
            ).andExpect(status().isOk)
        mockMvc
            .perform(
                put(
                    "/api/athlete/time-zone",
                ).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"timeZone":"Mars/Olympus"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.timeZone").value("Unknown timezone"))

        mockMvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.timeZone").value("Europe/London"))
        assertEquals(listOf(AthleteTimeZoneChanged(runner.id!!)), events.stream(AthleteTimeZoneChanged::class.java).toList())
    }

    @Test
    fun `local dates are UTC until a zone is set, then counted in it`() {
        // 23:30 UTC on 30 June is 00:30 on 1 July in London (BST, UTC+1).
        val lateEvening = java.time.Instant.parse("2024-06-30T23:30:00Z")
        assertEquals(ZoneOffset.UTC, athleteTimeZone.of(runner.id!!))
        assertEquals(LocalDate.parse("2024-06-30"), athleteTimeZone.localDate(runner.id!!, lateEvening))

        athleteTimeZone.set(runner.id!!, "Europe/London")

        assertEquals(LocalDate.parse("2024-07-01"), athleteTimeZone.localDate(runner.id!!, lateEvening))
    }
}
