package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "list_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ActivityApiIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    private lateinit var runner: User
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "list_runner@example.com", passwordHash = "x", username = "list_runner"))
        otherRunner = userRepository.save(User(email = "list_other@example.com", passwordHash = "x", username = "list_other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun saveActivity(
        owner: User,
        title: String,
        startedAt: String,
        source: ActivitySource = ActivitySource.MANUAL,
        sport: ActivitySport = ActivitySport.RUN,
    ): Activity =
        activityRepository.save(
            Activity(
                title = title,
                sport = sport,
                startedAt = Instant.parse(startedAt),
                distance = 5.0,
                timeTakenSeconds = 1500,
                user = owner,
                averagePaceKmh = 12.0,
                averageHeartRate = 150,
                calories = 400,
                source = source,
                externalId = if (source == ActivitySource.MANUAL) null else "$title-$startedAt",
            ),
        )

    private fun importRun(
        title: String,
        startedAt: String,
        distanceMeters: Float,
        totalSeconds: Int,
        source: ActivitySource = ActivitySource.FIT_UPLOAD,
    ): Activity =
        activityImportService
            .importParsed(
                listOf(
                    parsedActivity(
                        sport = ActivitySport.RUN,
                        title = title,
                        startedAt = Instant.parse(startedAt),
                        distance = distanceMeters / 1000.0,
                        timeTakenSeconds = totalSeconds,
                        externalId = "$title-$startedAt",
                        telemetry =
                            FitTelemetry(
                                t = intArrayOf(0, totalSeconds),
                                lons = doubleArrayOf(-0.16, -0.17),
                                lats = doubleArrayOf(51.51, 51.52),
                                routeIndex = arrayOf(0, 1),
                                distance = arrayOf(0.0f, distanceMeters),
                            ),
                    ),
                ),
                runner.id!!,
                source,
            ).createdActivities
            .single()

    private fun putJson(
        id: Long,
        body: String,
    ) = mockMvc.perform(put("/api/activities/$id").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun expectFiveKilometerRecordHeldBy(title: String) {
        mockMvc
            .perform(get("/api/dashboard").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.personalRecordSlots[3].benchmarkDistance").value("FIVE_KILOMETERS"))
            .andExpect(jsonPath("$.personalRecordSlots[3].personalRecord.activityTitle").value(title))
    }

    @Test
    @WithAnonymousUser
    fun `unauthenticated GET api activities returns 401 UNAUTHORIZED envelope`() {
        mockMvc
            .perform(get("/api/activities").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }

    @Test
    fun `GET api activities pages the athlete's activities newest first with summary metrics`() {
        saveActivity(runner, "Oldest", "2026-01-01T08:00:00Z")
        saveActivity(runner, "Middle", "2026-02-01T08:00:00Z", source = ActivitySource.FIT_UPLOAD)
        saveActivity(runner, "Newest", "2026-03-01T08:00:00Z")
        saveActivity(otherRunner, "Not mine", "2026-04-01T08:00:00Z")

        mockMvc
            .perform(get("/api/activities").param("page", "0").param("size", "2").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].title", contains("Newest", "Middle")))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.hasPrevious").value(false))
            .andExpect(jsonPath("$.items[0].sport").value("RUN"))
            .andExpect(jsonPath("$.items[0].startedAt").value("2026-03-01T08:00:00Z"))
            .andExpect(jsonPath("$.items[0].distance").value(5.0))
            .andExpect(jsonPath("$.items[0].timeTakenSeconds").value(1500))
            .andExpect(jsonPath("$.items[0].averagePaceKmh").value(12.0))
            .andExpect(jsonPath("$.items[0].averageHeartRate").value(150))
            .andExpect(jsonPath("$.items[0].calories").value(400))
            .andExpect(jsonPath("$.items[0].source").value("MANUAL"))
            .andExpect(jsonPath("$.items[0].hasProtectedMetrics").value(false))
            .andExpect(jsonPath("$.items[1].hasProtectedMetrics").value(true))

        mockMvc
            .perform(get("/api/activities").param("page", "1").param("size", "2").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
            .andExpect(jsonPath("$.items[0].title").value("Oldest"))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.hasPrevious").value(true))
    }

    @Test
    fun `POST api activities creates a manual activity owned by the athlete`() {
        val body =
            """
            {"title":"Lunch Run","sport":"RUN","startedAt":"2026-05-02T12:30:00Z","distance":8.5,
             "timeTakenSeconds":2700,"averagePaceKmh":11.33,"averageHeartRate":148,"calories":610}
            """.trimIndent()

        val location =
            mockMvc
                .perform(post("/api/activities").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated)
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber)
                .andExpect(jsonPath("$.title").value("Lunch Run"))
                .andExpect(jsonPath("$.startedAt").value("2026-05-02T12:30:00Z"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.hasProtectedMetrics").value(false))
                .andReturn()
                .response
                .getHeader("Location")!!

        mockMvc
            .perform(get(location).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Lunch Run"))
            .andExpect(jsonPath("$.distance").value(8.5))
            .andExpect(jsonPath("$.timeTakenSeconds").value(2700))
            .andExpect(jsonPath("$.averageHeartRate").value(148))
            .andExpect(jsonPath("$.calories").value(610))
        assertEquals(
            runner.id,
            activityRepository
                .findAll()
                .single()
                .user.id,
        )
    }

    @Test
    fun `POST api activities rejects invalid input with per-field validation errors`() {
        val body = """{"title":" ","sport":"RUN","distance":-1,"timeTakenSeconds":0}"""

        mockMvc
            .perform(post("/api/activities").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.title").exists())
            .andExpect(jsonPath("$.fieldErrors.startedAt").exists())
            .andExpect(jsonPath("$.fieldErrors.distance").exists())
            .andExpect(jsonPath("$.fieldErrors.timeTakenSeconds").exists())
        assertEquals(0, activityRepository.count())
    }

    @Test
    fun `GET api activities id returns 404 for another athlete's activity`() {
        val notMine = saveActivity(otherRunner, "Not mine", "2026-04-01T08:00:00Z")

        mockMvc
            .perform(get("/api/activities/${notMine.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `PUT api activities id replaces every field of a manual activity without Activity Details`() {
        val activity = saveActivity(runner, "Easy Run", "2026-01-01T08:00:00Z")

        putJson(
            activity.id!!,
            """
            {"title":"Tempo Run","sport":"WALK","startedAt":"2026-01-02T07:15:00Z","distance":6.2,
             "timeTakenSeconds":1800,"averagePaceKmh":12.4,"averageHeartRate":162}
            """.trimIndent(),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Tempo Run"))
            .andExpect(jsonPath("$.sport").value("WALK"))
            .andExpect(jsonPath("$.startedAt").value("2026-01-02T07:15:00Z"))
            .andExpect(jsonPath("$.distance").value(6.2))
            .andExpect(jsonPath("$.timeTakenSeconds").value(1800))
            .andExpect(jsonPath("$.averagePaceKmh").value(12.4))
            .andExpect(jsonPath("$.averageHeartRate").value(162))
            .andExpect(jsonPath("$.calories").doesNotExist())
    }

    @Test
    fun `PUT api activities id requires the Protected Metrics of an editable manual activity`() {
        val activity = saveActivity(runner, "Easy Run", "2026-01-01T08:00:00Z")

        putJson(activity.id!!, """{"title":"Renamed","sport":"RUN"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.startedAt").exists())
            .andExpect(jsonPath("$.fieldErrors.distance").exists())
            .andExpect(jsonPath("$.fieldErrors.timeTakenSeconds").exists())
        assertEquals("Easy Run", activityRepository.findById(activity.id!!).get().title)
    }

    @Test
    fun `PUT api activities id edits only title and sport of an imported activity`() {
        val imported = saveActivity(runner, "Imported", "2026-01-01T08:00:00Z", source = ActivitySource.FIT_UPLOAD)

        putJson(imported.id!!, """{"title":"Renamed Import","sport":"HIKE"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Renamed Import"))
            .andExpect(jsonPath("$.sport").value("HIKE"))
            .andExpect(jsonPath("$.distance").value(5.0))
            .andExpect(jsonPath("$.hasProtectedMetrics").value(true))
    }

    @Test
    fun `PUT api activities id rejects changes to Protected Metrics of an imported activity`() {
        val imported = saveActivity(runner, "Imported", "2026-01-01T08:00:00Z", source = ActivitySource.FIT_UPLOAD)

        putJson(imported.id!!, """{"title":"Renamed","sport":"RUN","distance":42.0,"calories":1}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.distance").value("distance is a Protected Metric and cannot be edited"))
            .andExpect(jsonPath("$.fieldErrors.calories").exists())

        val unchanged = activityRepository.findById(imported.id!!).get()
        assertEquals("Imported", unchanged.title)
        assertEquals(5.0, unchanged.distance)
        assertEquals(400, unchanged.calories)
    }

    @Test
    fun `PUT api activities id protects the metrics of a manual activity backed by Activity Details`() {
        val manualWithDetails = importRun("Manual With Details", "2026-01-01T08:00:00Z", 5000.0f, 1500, ActivitySource.MANUAL)

        mockMvc
            .perform(get("/api/activities/${manualWithDetails.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.source").value("MANUAL"))
            .andExpect(jsonPath("$.hasProtectedMetrics").value(true))

        putJson(
            manualWithDetails.id!!,
            """{"title":"x","sport":"RUN","startedAt":"2026-01-01T08:00:00Z","distance":5.0,"timeTakenSeconds":1}""",
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fieldErrors.timeTakenSeconds").exists())
    }

    @Test
    fun `PUT api activities id returns 404 for another athlete's activity`() {
        val notMine = saveActivity(otherRunner, "Not mine", "2026-04-01T08:00:00Z")

        putJson(notMine.id!!, """{"title":"Mine now","sport":"RUN"}""")
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
        assertEquals("Not mine", activityRepository.findById(notMine.id!!).get().title)
    }

    @Test
    fun `changing sport recalibrates the athlete's Personal Records`() {
        importRun("Steady 5k", "2026-04-05T08:00:00Z", 5000.0f, 1500)
        val fast = importRun("Fast 5k", "2026-04-10T09:00:00Z", 5000.0f, 1200)
        expectFiveKilometerRecordHeldBy("Fast 5k")

        putJson(fast.id!!, """{"title":"Fast 5k","sport":"RIDE"}""").andExpect(status().isOk)
        expectFiveKilometerRecordHeldBy("Steady 5k")

        putJson(fast.id!!, """{"title":"Fast 5k","sport":"RUN"}""").andExpect(status().isOk)
        expectFiveKilometerRecordHeldBy("Fast 5k")
    }

    @Test
    fun `DELETE api activities id removes the activity and recalibrates Personal Records`() {
        importRun("Steady 5k", "2026-04-05T08:00:00Z", 5000.0f, 1500)
        val fast = importRun("Fast 5k", "2026-04-10T09:00:00Z", 5000.0f, 1200)
        expectFiveKilometerRecordHeldBy("Fast 5k")

        mockMvc
            .perform(delete("/api/activities/${fast.id}").with(csrf()))
            .andExpect(status().isNoContent)

        expectFiveKilometerRecordHeldBy("Steady 5k")
        mockMvc
            .perform(get("/api/activities/${fast.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
        mockMvc
            .perform(delete("/api/activities/${fast.id}").with(csrf()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `DELETE api activities id never deletes another athlete's activity`() {
        val notMine = saveActivity(otherRunner, "Not mine", "2026-04-01T08:00:00Z")

        mockMvc
            .perform(delete("/api/activities/${notMine.id}").with(csrf()))
            .andExpect(status().isNotFound)
        assertEquals(true, activityRepository.existsById(notMine.id!!))
    }
}
