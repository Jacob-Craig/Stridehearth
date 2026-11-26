package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.CriticalSpeedFit
import com.jacobcraig.stridehearth.analytics.CriticalSpeedStatus
import com.jacobcraig.stridehearth.analytics.PeakEffort
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import com.jacobcraig.stridehearth.athlete.AthleteAnalyticsStateRepository
import com.jacobcraig.stridehearth.athlete.CriticalSpeedModelRepository
import com.jacobcraig.stridehearth.athlete.StoredCriticalSpeedModel
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate

/**
 * **Stamina** (#40) on `GET /api/activities/{id}/telemetry` (`derivedStreams.staminaPct`) and `GET /api/activities/{id}`
 * (`minStaminaPct`): computed on read from the Run's stored Grade-Adjusted speed and the **Critical Speed** model in
 * effect on its athlete-local date. Each Run holds 5 m/s for 150 s then 3 m/s for 150 s, so against CS 4 m/s and
 * D′ 200 m Stamina is 50 % at 100 s, bottoms out at 25 % at 150 s, and recovers to 200 - 150·e^(-0.75) = 129.145 m
 * (64.6 %) at 300 s.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "stamina_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class StaminaApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var derivedMetricsRepository: ActivityDerivedMetricsRepository

    @Autowired
    private lateinit var modelRepository: CriticalSpeedModelRepository

    @Autowired
    private lateinit var peakEffortRepository: ActivityPeakEffortRepository

    @Autowired
    private lateinit var stateRepository: AthleteAnalyticsStateRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private lateinit var runner: User
    private lateinit var otherUser: User

    private val runDay = LocalDate.parse("2026-01-01")
    private val samples = 301

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "stamina_runner@example.com", passwordHash = "x", username = "stamina_runner"))
        otherUser = userRepository.save(User(email = "stamina_other@example.com", passwordHash = "x", username = "stamina_other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun activity(
        owner: User = runner,
        sport: ActivitySport = ActivitySport.RUN,
        startedAt: Instant = Instant.parse("2026-01-01T08:00:00Z"),
        withGap: Boolean = true,
    ): Activity {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Stamina Run",
                    sport = sport,
                    startedAt = startedAt,
                    distance = 1.2,
                    timeTakenSeconds = samples - 1,
                    user = owner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        activityDetailsRepository.upsert(
            activity.id!!,
            FitTelemetry(
                t = IntArray(samples) { it },
                lons = doubleArrayOf(-0.16, -0.17),
                lats = doubleArrayOf(51.51, 51.52),
                routeIndex = Array(samples) { if (it < 2) it else null },
            ),
            ParserVersion(1),
        )
        val gap = Array<Float?>(samples) { if (it <= 150) 5.0f else 3.0f }
        derivedMetricsRepository.upsert(activity.id!!, DERIVED_DATA_VERSION, if (withGap) gap else null)
        return activity
    }

    private fun model(
        day: LocalDate,
        criticalSpeed: Double,
        dPrime: Double,
    ) = StoredCriticalSpeedModel(
        PeakEffortMetric.GAP_SPEED,
        day,
        CriticalSpeedFit(CriticalSpeedStatus.MODEL, criticalSpeed, dPrime, 0.999, 5),
    )

    private fun noModel(day: LocalDate) =
        StoredCriticalSpeedModel(
            PeakEffortMetric.GAP_SPEED,
            day,
            CriticalSpeedFit(CriticalSpeedStatus.INSUFFICIENT_POINTS, null, null, null, 2),
        )

    private fun storeModels(
        owner: User,
        vararg models: StoredCriticalSpeedModel,
    ) = modelRepository.replace(owner.id!!, null, null, models.toList())

    private fun telemetry(activity: Activity): ResultActions =
        mockMvc.perform(get("/api/activities/{id}/telemetry", activity.id)).andExpect(status().isOk)

    private fun detail(activity: Activity): ResultActions =
        mockMvc.perform(get("/api/activities/{id}", activity.id)).andExpect(status().isOk)

    @Test
    fun `a Run on a day with a model gets a Stamina stream parallel to t, and its minimum on the Activity`() {
        val run = activity()
        storeModels(runner, model(runDay, 4.0, 200.0))

        telemetry(run)
            .andExpect(jsonPath("$.derivedStreams.staminaPct.length()").value(samples))
            .andExpect(jsonPath("$.derivedStreams.staminaPct[0]").value(100.0))
            .andExpect(jsonPath("$.derivedStreams.staminaPct[100]").value(50.0))
            .andExpect(jsonPath("$.derivedStreams.staminaPct[150]").value(25.0))
            .andExpect(jsonPath("$.derivedStreams.staminaPct[300]", closeTo(64.6, 1e-4)))
        detail(run).andExpect(jsonPath("$.minStaminaPct").value(25.0))
    }

    @Test
    fun `the model is the one in effect on the Run's own athlete-local date, its own Peak Efforts included`() {
        // 23:00 UTC on 1 January is 12:00 on 2 January in Auckland (UTC+13).
        runner.timeZone = "Pacific/Auckland"
        userRepository.save(runner)
        val run = activity(startedAt = Instant.parse("2026-01-01T23:00:00Z"))
        // The model dated the Run's own day wins: CS 4.5 m/s, D′ 300 m loses 0.5 m/s x 150 s = 75 m, leaving 75 %.
        storeModels(runner, model(runDay, 4.0, 200.0), model(runDay.plusDays(1), 4.5, 300.0))

        telemetry(run).andExpect(jsonPath("$.derivedStreams.staminaPct[150]").value(75.0))
        detail(run).andExpect(jsonPath("$.minStaminaPct").value(75.0))
    }

    @Test
    fun `a refit that changes the day's model changes the Stamina read`() {
        val run = activity()
        storeModels(runner, model(runDay, 4.0, 200.0))
        detail(run).andExpect(jsonPath("$.minStaminaPct").value(25.0))

        storeModels(runner, model(runDay, 4.5, 300.0))

        detail(run).andExpect(jsonPath("$.minStaminaPct").value(75.0))
        telemetry(run).andExpect(jsonPath("$.derivedStreams.staminaPct[150]").value(75.0))
    }

    @Test
    fun `no model, a Walk, or a Run without a Grade-Adjusted stream has no Stamina`() {
        val noModelRun = activity()
        val walk = activity(sport = ActivitySport.WALK, startedAt = Instant.parse("2026-01-05T08:00:00Z"))
        val runWithoutGap = activity(withGap = false, startedAt = Instant.parse("2026-01-06T08:00:00Z"))
        storeModels(
            runner,
            noModel(runDay),
            model(LocalDate.parse("2026-01-05"), 4.0, 200.0),
            model(LocalDate.parse("2026-01-06"), 4.0, 200.0),
        )

        for (activity in listOf(noModelRun, walk, runWithoutGap)) {
            telemetry(activity)
                .andExpect(jsonPath("$.derivedStreams").exists())
                .andExpect(jsonPath("$.derivedStreams.staminaPct").value(nullValue()))
            detail(activity).andExpect(jsonPath("$.minStaminaPct").value(nullValue()))
        }
    }

    @Test
    fun `another athlete's Run is a 404, its Stamina never read`() {
        val foreign = activity(owner = otherUser)
        storeModels(otherUser, model(runDay, 4.0, 200.0))

        mockMvc
            .perform(get("/api/activities/{id}/telemetry", foreign.id))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.derivedStreams").doesNotExist())
        mockMvc
            .perform(get("/api/activities/{id}", foreign.id))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.minStaminaPct").doesNotExist())
    }

    @Test
    fun `after a time-zone change and before the rebuild, Stamina uses a live fit for the new zone`() {
        // 23:00 UTC on 1 January is 12:00 on 2 January in Auckland (UTC+13).
        val run = activity(startedAt = Instant.parse("2026-01-01T23:00:00Z"))
        // The Run's own Peak Efforts follow v = 4 + 200 / t: a live fit gives CS 4 m/s and D' 200 m, so a 25 % low.
        peakEffortRepository.replace(
            run.id!!,
            BenchmarkDuration.entries
                .filter { it.seconds in 120..1200 }
                .map { PeakEffort(PeakEffortMetric.GAP_SPEED, it, 4.0 + 200.0 / it.seconds, 0, it.seconds, 0.0, it.seconds.toDouble()) },
        )
        // A row dated 2 January left from the old zone (another Run that day, since moved): CS 4.5 m/s, D' 300 m, 75 %.
        storeModels(runner, model(runDay.plusDays(1), 4.5, 300.0))
        runner.timeZone = "Pacific/Auckland"
        userRepository.save(runner)
        // What the time-zone listener does: the worker has not rebuilt the stored models yet.
        stateRepository.markRollupsStale(runner.id!!)

        detail(run).andExpect(jsonPath("$.minStaminaPct").value(25.0))
        telemetry(run).andExpect(jsonPath("$.derivedStreams.staminaPct[150]").value(25.0))
    }
}
