package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.lessThanOrEqualTo
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * `GET /api/activities/{id}/race-split-plan` (#46). Each course is a 1 Hz Run at 4 m/s heading north; the flat one
 * is 2400 m at a constant 30 m, so a 600 s goal is 0.25 s/m: two 250 s kilometres and a 100 s last 400 m.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "split_planner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class RaceSplitPlanApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private lateinit var planner: User
    private lateinit var otherUser: User

    @BeforeEach
    fun setUp() {
        planner = userRepository.save(User(email = "split_planner@example.com", passwordHash = "x", username = "split_planner"))
        otherUser = userRepository.save(User(email = "split_other@example.com", passwordHash = "x", username = "split_other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun activity(
        owner: User = planner,
        sport: ActivitySport = ActivitySport.RUN,
        samples: Int = 601,
        withGps: Boolean = true,
        withDetails: Boolean = true,
    ): Activity {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Split Plan Course",
                    sport = sport,
                    startedAt = Instant.parse("2026-01-01T08:00:00Z"),
                    distance = (samples - 1) * 4.0 / 1000,
                    timeTakenSeconds = samples - 1,
                    user = owner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
        if (withDetails) {
            activityDetailsRepository.upsert(
                activity.id!!,
                FitTelemetry(
                    t = IntArray(samples) { it },
                    lons = if (withGps) DoubleArray(samples) { -1.5 } else DoubleArray(0),
                    lats = if (withGps) DoubleArray(samples) { 53.0 + it * 4.0 / 111_195.0 } else DoubleArray(0),
                    routeIndex = Array(samples) { if (withGps) it else null },
                    speed = Array(samples) { 4.0f },
                    distance = Array(samples) { it * 4.0f },
                    altitude = Array(samples) { 30.0f },
                ),
                ParserVersion(1),
            )
        }
        return activity
    }

    private fun plan(
        activity: Activity,
        goalSeconds: String = "600",
        configure: MockHttpServletRequestBuilder.() -> MockHttpServletRequestBuilder = { this },
    ) = mockMvc.perform(
        get("/api/activities/{id}/race-split-plan", activity.id).param("goalSeconds", goalSeconds).configure(),
    )

    @Test
    fun `returns the splits of the athlete's own course that sum to the goal`() {
        val course = activity()

        plan(course)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.activityId").value(course.id!!.toInt()))
            .andExpect(jsonPath("$.courseDistanceM").value(closeTo(2400.0, 1e-6)))
            .andExpect(jsonPath("$.goalSeconds").value(600.0))
            .andExpect(jsonPath("$.unit").value("KM"))
            .andExpect(jsonPath("$.biasPct").value(0.0))
            .andExpect(jsonPath("$.elevationGainM").value(closeTo(0.0, 1e-6)))
            .andExpect(jsonPath("$.splits.length()").value(3))
            .andExpect(jsonPath("$.splits[0].index").value(1))
            .andExpect(jsonPath("$.splits[0].startM").value(0.0))
            .andExpect(jsonPath("$.splits[0].endM").value(closeTo(1000.0, 1e-6)))
            .andExpect(jsonPath("$.splits[0].distanceM").value(closeTo(1000.0, 1e-6)))
            .andExpect(jsonPath("$.splits[0].targetSeconds").value(closeTo(250.0, 0.01)))
            .andExpect(jsonPath("$.splits[0].paceSecondsPerUnit").value(closeTo(250.0, 0.01)))
            .andExpect(jsonPath("$.splits[0].cumulativeSeconds").value(closeTo(250.0, 0.01)))
            .andExpect(jsonPath("$.splits[0].elevationGainM").value(closeTo(0.0, 1e-6)))
            .andExpect(jsonPath("$.splits[0].elevationLossM").value(closeTo(0.0, 1e-6)))
            .andExpect(jsonPath("$.splits[0].avgGradePct").value(closeTo(0.0, 1e-6)))
            .andExpect(jsonPath("$.splits[2].distanceM").value(closeTo(400.0, 1e-6)))
            .andExpect(jsonPath("$.splits[2].targetSeconds").value(closeTo(100.0, 0.01)))
            .andExpect(jsonPath("$.splits[2].paceSecondsPerUnit").value(closeTo(250.0, 0.01)))
            .andExpect(jsonPath("$.splits[2].cumulativeSeconds").value(600.0))
            .andExpect(jsonPath("$.profile.length()").value(lessThanOrEqualTo(500)))
            .andExpect(jsonPath("$.profile[0].distanceM").value(0.0))
            .andExpect(jsonPath("$.profile[0].altitudeM").value(closeTo(30.0, 1e-6)))
    }

    @Test
    fun `plans in miles with a Split Bias`() {
        // 2400 m = 1609.344 m + 790.656 m; the second part is slower under a positive bias.
        plan(activity()) { param("unit", "MILE").param("biasPct", "5") }
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.unit").value("MILE"))
            .andExpect(jsonPath("$.biasPct").value(5.0))
            .andExpect(jsonPath("$.splits.length()").value(2))
            .andExpect(jsonPath("$.splits[0].distanceM").value(closeTo(1609.344, 1e-6)))
            .andExpect(jsonPath("$.splits[1].cumulativeSeconds").value(600.0))
    }

    @Test
    fun `a long course's profile has at most 500 points`() {
        plan(activity(samples = 2501), goalSeconds = "3000")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.length()").value(500))
            .andExpect(jsonPath("$.profile[499].distanceM").value(closeTo(10000.0, 1e-6)))
    }

    @Test
    fun `another athlete's Activity is not found`() {
        plan(activity(owner = otherUser)).andExpect(status().isNotFound)
    }

    @Test
    fun `a missing Activity is not found`() {
        mockMvc
            .perform(get("/api/activities/{id}/race-split-plan", Long.MAX_VALUE).param("goalSeconds", "600"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `a Ride has no Race Split Plan`() {
        plan(activity(sport = ActivitySport.RIDE))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors", hasKey("activityId")))
    }

    @Test
    fun `an Activity without a GPS route has no Race Split Plan`() {
        plan(activity(withGps = false))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors", hasKey("activityId")))
    }

    @Test
    fun `an Activity without Activity Details has no Race Split Plan`() {
        plan(activity(withDetails = false))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors", hasKey("activityId")))
    }

    @Test
    fun `a goal under a minute is refused`() {
        plan(activity(), goalSeconds = "30")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors", hasKey("goalSeconds")))
    }

    @Test
    fun `a goal over a day, a malformed goal, an unknown unit and an out-of-range bias are refused`() {
        val course = activity()
        plan(course, goalSeconds = "86401").andExpect(status().isBadRequest)
        plan(course, goalSeconds = "fast").andExpect(status().isBadRequest)
        plan(course) { param("unit", "FURLONG") }
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors", hasKey("unit")))
        plan(course) { param("biasPct", "5.5") }
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors", hasKey("biasPct")))
    }

    @Test
    fun `the plan never changes the stored Activity Details`() {
        val course = activity()
        val before = activityDetailsRepository.findStoredAnalyticsSeries(course.id!!)

        plan(course).andExpect(status().isOk)

        kotlin.test.assertEquals(before, activityDetailsRepository.findStoredAnalyticsSeries(course.id!!))
    }
}
