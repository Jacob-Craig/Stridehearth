package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.FieldChange
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reading, logging, editing and deleting **Activities** through the `/api/activities` JSON contract the
 * SPA consumes, against real PostgreSQL: **Activity History Entries**, **Protected Metrics**, cascades,
 * pagination and user isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ActivityReadIntegrationTest {
    @Autowired
    lateinit var postgres: PostgreSQLContainer

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var activityRepository: ActivityRepository

    @Autowired
    lateinit var activityLapRepository: ActivityLapRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var activityHistory: ActivityHistory

    @Autowired
    lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

    private lateinit var testUser: User

    @BeforeEach
    fun setUp() {
        testUser = userRepository.save(User(email = "runner@example.com", passwordHash = "x", username = "runner"))
    }

    @AfterEach
    fun tearDown() {
        activityLapRepository.deleteAll()
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `context loads and container is running`() {
        assertTrue(postgres.isRunning)
    }

    @Test
    fun `flyway migration V4 renamed table, renamed constraints, and added not null sport column`() {
        // Table 'activity' exists and 'run' table does not exist
        val activityTableCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'activity'",
                Int::class.java,
            )
        val runTableCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'run'",
                Int::class.java,
            )
        assertEquals(1, activityTableCount)
        assertEquals(0, runTableCount)

        // Column 'sport' exists on 'activity' table and is NOT NULL
        val sportNullable =
            jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'activity' AND column_name = 'sport'",
                String::class.java,
            )
        assertEquals("NO", sportNullable)

        // Unique constraint 'uq_activity_user_source_external_id' exists
        val uqConstraintCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = 'public' AND constraint_name = 'uq_activity_user_source_external_id'",
                Int::class.java,
            )
        assertEquals(1, uqConstraintCount)

        // Foreign key constraint 'fk_activity_app_user' exists
        val fkConstraintCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints WHERE table_schema = 'public' AND constraint_name = 'fk_activity_app_user'",
                Int::class.java,
            )
        assertEquals(1, fkConstraintCount)
    }

    @Test
    fun `flyway migration V8 creates activity_history table with cascade delete`() {
        val historyTableCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'activity_history'",
                Int::class.java,
            )
        assertEquals(1, historyTableCount)

        val columns =
            jdbcTemplate.queryForList(
                "SELECT column_name, is_nullable, data_type FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'activity_history'",
            )
        val colNames = columns.map { it["column_name"] as String }
        assertTrue(colNames.containsAll(listOf("id", "activity_id", "change_type", "description", "created_at")))
    }

    @Test
    fun `logging a new manual activity records initial CREATED history entry exposed on the activity`() {
        createActivity(
            """{"title":"Fresh Morning Run","sport":"RUN","startedAt":"2026-03-01T00:00:00Z","distance":5.0,"timeTakenSeconds":1500}""",
        ).andExpect(status().isCreated)

        val createdActivity = activityRepository.findByUserId(testUser.id!!).firstOrNull { it.title == "Fresh Morning Run" }
        assertNotNull(createdActivity)

        assertEquals(listOf(ActivityChange.Created.MANUAL), activityHistory.entriesFor(createdActivity.id!!).map { it.change })

        mockMvc
            .perform(get("/api/activities/{id}", createdActivity.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.history.length()").value(1))
            .andExpect(jsonPath("$.history[0].change.kind").value("CREATED"))
            .andExpect(jsonPath("$.history[0].change.source").value("MANUAL"))
            .andExpect(jsonPath("$.history[0].change.parserVersion").value(nullValue()))
            .andExpect(jsonPath("$.history[0].change.lapCount").value(0))
    }

    @Test
    fun `logging activities of other sports like SWIM round-trips the sport through list and detail`() {
        createActivity(
            """{"title":"Evening Swim","sport":"SWIM","startedAt":"2026-02-15T00:00:00Z","distance":2.0,"timeTakenSeconds":2400}""",
        ).andExpect(status().isCreated)

        val activities = activityRepository.findAll()
        assertEquals(1, activities.size)
        assertEquals("Evening Swim", activities[0].title)
        assertEquals(ActivitySport.SWIM, activities[0].sport)

        mockMvc
            .perform(get("/api/activities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].sport").value("SWIM"))

        mockMvc
            .perform(get("/api/activities/{id}", activities[0].id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sport").value("SWIM"))
    }

    @Test
    fun `logging an activity without a start time is rejected with a startedAt field error`() {
        createActivity("""{"title":"Park Run","sport":"RUN","distance":10,"timeTakenSeconds":60,"averagePaceKmh":6}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.startedAt").exists())

        assertEquals(0, activityRepository.count())
    }

    @Test
    fun `logging an entirely empty activity reports every required field`() {
        createActivity("{}")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.title").exists())
            .andExpect(jsonPath("$.fieldErrors.sport").exists())
            .andExpect(jsonPath("$.fieldErrors.startedAt").exists())
            .andExpect(jsonPath("$.fieldErrors.distance").exists())
            .andExpect(jsonPath("$.fieldErrors.timeTakenSeconds").exists())

        assertEquals(0, activityRepository.count())
    }

    fun insertTestActivity(): Activity {
        val activity =
            Activity(
                title = "Test Activity",
                sport = ActivitySport.RUN,
                source = ActivitySource.MANUAL,
                startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                distance = 10.0,
                timeTakenSeconds = 30 * 60,
                user = testUser,
                calories = 600,
                averageHeartRate = 150,
            )
        return activityRepository.save(activity)
    }

    @Test
    fun `listing activities returns the saved activity summary`() {
        val saved = insertTestActivity()

        mockMvc
            .perform(get("/api/activities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].id").value(saved.id!!))
            .andExpect(jsonPath("$.items[0].title").value("Test Activity"))
            .andExpect(jsonPath("$.items[0].startedAt").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.items[0].distance").value(10.0))
            .andExpect(jsonPath("$.items[0].timeTakenSeconds").value(1800))
            .andExpect(jsonPath("$.items[0].averageHeartRate").value(150))
            .andExpect(jsonPath("$.items[0].calories").value(600))
    }

    @Test
    fun `a missing activity is reported as RESOURCE_NOT_FOUND`() {
        mockMvc
            .perform(get("/api/activities/999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `an activity's detail exposes its canonical unformatted metrics`() {
        val activity =
            activityRepository.save(
                Activity(
                    title = "Precision Activity",
                    sport = ActivitySport.RUN,
                    source = ActivitySource.MANUAL,
                    startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    distance = 10.13622,
                    timeTakenSeconds = 3600,
                    averagePaceKmh = 8.72676,
                    user = testUser,
                ),
            )

        mockMvc
            .perform(get("/api/activities/{id}", activity.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Precision Activity"))
            .andExpect(jsonPath("$.startedAt").value("2026-01-01T00:00:00Z"))
            .andExpect(jsonPath("$.distance").value(10.13622))
            .andExpect(jsonPath("$.timeTakenSeconds").value(3600))
            .andExpect(jsonPath("$.averagePaceKmh").value(8.72676))
            .andExpect(jsonPath("$.ownerUsername").value("runner"))

        mockMvc
            .perform(get("/api/activities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].distance").value(10.13622))
            .andExpect(jsonPath("$.items[0].averagePaceKmh").value(8.72676))
    }

    @Test
    fun `deleting an activity removes it`() {
        val activityId = checkNotNull(insertTestActivity().id)

        mockMvc
            .perform(delete("/api/activities/{id}", activityId).with(csrf()))
            .andExpect(status().isNoContent)

        assertFalse(activityRepository.existsById(activityId))
    }

    @Test
    fun `deleting a missing activity is reported as RESOURCE_NOT_FOUND`() {
        mockMvc
            .perform(delete("/api/activities/9999").with(csrf()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `updating a manual activity with valid inputs replaces every field`() {
        val activityId = checkNotNull(insertTestActivity().id)

        updateActivity(
            activityId,
            """
            {"title":"Updated 10k Run","sport":"RUN","startedAt":"2026-03-15T00:00:00Z","distance":10.5,
             "timeTakenSeconds":3100,"averagePaceKmh":12.2,"calories":650,"averageHeartRate":155}
            """.trimIndent(),
        ).andExpect(status().isOk)

        val updatedActivity = checkNotNull(activityRepository.findByIdOrNull(activityId))
        assertEquals("Updated 10k Run", updatedActivity.title)
        assertEquals(ActivitySport.RUN, updatedActivity.sport)
        assertEquals(Instant.parse("2026-03-15T00:00:00Z"), updatedActivity.startedAt)
        assertEquals(10.5, updatedActivity.distance)
        assertEquals(3100, updatedActivity.timeTakenSeconds)
        assertEquals(12.2, updatedActivity.averagePaceKmh)
        assertEquals(650, updatedActivity.calories)
        assertEquals(155, updatedActivity.averageHeartRate)
    }

    @Test
    fun `updating a missing activity is reported as RESOURCE_NOT_FOUND`() {
        updateActivity(
            9999,
            """{"title":"Updated 10k Run","sport":"RUN","startedAt":"2026-03-15T00:00:00Z","distance":10.5,"timeTakenSeconds":3100}""",
        ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `updating a manual activity without a required metric is rejected and changes nothing`() {
        val activityId = checkNotNull(insertTestActivity().id)

        updateActivity(activityId, """{"title":"Updated Title","sport":"RUN","distance":10.5,"timeTakenSeconds":3100}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.startedAt").exists())

        val unchangedActivity = checkNotNull(activityRepository.findByIdOrNull(activityId))
        assertEquals("Test Activity", unchangedActivity.title)
        assertEquals(10.0, unchangedActivity.distance)
    }

    @Test
    fun `updating a manual activity with changed fields records EDITED history entry with old to new diff`() {
        val activityId = checkNotNull(insertTestActivity().id)

        updateActivity(
            activityId,
            """
            {"title":"New Title","sport":"RIDE","startedAt":"2026-01-01T00:00:00Z","distance":15.0,
             "timeTakenSeconds":1800,"averageHeartRate":150,"calories":600}
            """.trimIndent(),
        ).andExpect(status().isOk)

        assertEquals(
            listOf(
                ActivityChange.Edited(
                    listOf(
                        FieldChange.Title("Test Activity", "New Title"),
                        FieldChange.Sport(ActivitySport.RUN, ActivitySport.RIDE),
                        FieldChange.Distance(10.0, 15.0),
                    ),
                ),
            ),
            activityHistory.entriesFor(activityId).map { it.change },
        )
    }

    @Test
    fun `updating an activity with unchanged fields does not record an EDITED history entry`() {
        val activityId = checkNotNull(insertTestActivity().id)

        updateActivity(
            activityId,
            """
            {"title":"Test Activity","sport":"RUN","startedAt":"2026-01-01T00:00:00Z","distance":10.0,
             "timeTakenSeconds":1800,"averageHeartRate":150,"calories":600}
            """.trimIndent(),
        ).andExpect(status().isOk)

        val historyCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activity_history WHERE activity_id = ?",
                Int::class.javaObjectType,
                activityId,
            )
        assertEquals(0, historyCount)
    }

    @Test
    fun `updating an imported activity changes only title and sport, protects its metrics and records EDITED history entry`() {
        val importedActivity =
            activityRepository.save(
                Activity(
                    title = "Original Imported Run",
                    sport = ActivitySport.RUN,
                    source = ActivitySource.FIT_UPLOAD,
                    startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    distance = 10.0,
                    timeTakenSeconds = 1800,
                    user = testUser,
                    calories = 500,
                    averageHeartRate = 145,
                    averagePaceKmh = 12.0,
                ),
            )
        val activityId = checkNotNull(importedActivity.id)

        // Attempting to change Protected Metrics is rejected outright, changing nothing
        updateActivity(
            activityId,
            """
            {"title":"Upgraded Cycling Ride","sport":"RIDE","distance":99.0,"timeTakenSeconds":9999,
             "averagePaceKmh":50.0,"calories":9999,"averageHeartRate":200}
            """.trimIndent(),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.distance").exists())
        assertEquals("Original Imported Run", activityRepository.findByIdOrNull(activityId)!!.title)

        updateActivity(activityId, """{"title":"Upgraded Cycling Ride","sport":"RIDE"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasProtectedMetrics").value(true))

        val updated = checkNotNull(activityRepository.findByIdOrNull(activityId))
        assertEquals("Upgraded Cycling Ride", updated.title)
        assertEquals(ActivitySport.RIDE, updated.sport)
        // Protected Metrics must remain unchanged
        assertEquals(10.0, updated.distance)
        assertEquals(1800, updated.timeTakenSeconds)
        assertEquals(12.0, updated.averagePaceKmh)
        assertEquals(500, updated.calories)
        assertEquals(145, updated.averageHeartRate)

        assertEquals(
            listOf(
                ActivityChange.Edited(
                    listOf(
                        FieldChange.Title("Original Imported Run", "Upgraded Cycling Ride"),
                        FieldChange.Sport(ActivitySport.RUN, ActivitySport.RIDE),
                    ),
                ),
            ),
            activityHistory.entriesFor(activityId).map { it.change },
        )
    }

    @Test
    fun `deleting an activity cascades deletion to all of its activity history entries`() {
        val activityId = checkNotNull(insertTestActivity().id)

        jdbcTemplate.update(
            "INSERT INTO activity_history (activity_id, change_type, description, created_at) VALUES (?, 'CREATED', 'Initial', now())",
            activityId,
        )

        mockMvc
            .perform(delete("/api/activities/{id}", activityId).with(csrf()))
            .andExpect(status().isNoContent)

        val historyCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activity_history WHERE activity_id = ?",
                Int::class.java,
                activityId,
            )
        assertEquals(0, historyCount)
    }

    @Test
    fun `pagination retrieves the correct chunk of the athlete's activities, newest first`() {
        val activities =
            (1..15).map { i ->
                Activity(
                    title = "Activity $i",
                    sport = ActivitySport.RUN,
                    source = ActivitySource.MANUAL,
                    startedAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i.toLong() * 86400),
                    distance = 10.0,
                    timeTakenSeconds = 30 * 60,
                    user = testUser,
                    calories = 600,
                    averageHeartRate = 150,
                )
            }
        activityRepository.saveAll(activities)

        mockMvc
            .perform(get("/api/activities").param("page", "0").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(10))
            .andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.totalElements").value(15))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.hasPrevious").value(false))
            .andExpect(jsonPath("$.items[*].title", contains(*(15 downTo 6).map { "Activity $it" }.toTypedArray())))

        mockMvc
            .perform(get("/api/activities").param("page", "1").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(10))
            .andExpect(jsonPath("$.items.length()").value(5))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.totalElements").value(15))
            .andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.hasPrevious").value(true))
            .andExpect(jsonPath("$.items[*].title", contains(*(5 downTo 1).map { "Activity $it" }.toTypedArray())))
    }

    @Test
    fun `athlete cannot see, edit or delete another athlete's activity`() {
        val otherUser = userRepository.save(User(email = "other@example.com", passwordHash = "x", username = "other"))
        val othersActivity =
            activityRepository.save(
                Activity(
                    title = "Other's Activity",
                    sport = ActivitySport.RUN,
                    source = ActivitySource.MANUAL,
                    startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1800,
                    user = otherUser,
                ),
            )
        val otherId = checkNotNull(othersActivity.id)

        mockMvc
            .perform(get("/api/activities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.items.length()").value(0))

        mockMvc
            .perform(get("/api/activities/{id}", otherId))
            .andExpect(status().isNotFound)

        updateActivity(
            otherId,
            """{"title":"Hijacked","sport":"RUN","startedAt":"2026-01-01T00:00:00Z","distance":5.0,"timeTakenSeconds":1800}""",
        ).andExpect(status().isNotFound)

        mockMvc
            .perform(delete("/api/activities/{id}", otherId).with(csrf()))
            .andExpect(status().isNotFound)

        val untouched = checkNotNull(activityRepository.findByIdOrNull(otherId))
        assertEquals("Other's Activity", untouched.title)
    }

    @Test
    fun `a new activity is owned by the logged in athlete`() {
        createActivity("""{"title":"Mine","sport":"RUN","startedAt":"2026-01-01T00:00:00Z","distance":10,"timeTakenSeconds":60}""")
            .andExpect(status().isCreated)

        val saved = activityRepository.findByUserId(checkNotNull(testUser.id))
        assertEquals(listOf("Mine"), saved.map { it.title })
    }

    @Test
    fun `an activity's detail lists its device laps in lap order`() {
        val activityId = checkNotNull(insertTestActivity().id)

        val lap1 =
            ActivityLap(
                activityId = activityId,
                lapNumber = 1,
                startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                distance = 1.0,
                timeTakenSeconds = 300,
                averagePaceKmh = 12.0,
                averageHeartRate = 145,
                elevationGainMeters = 10.5,
            )
        val lap2 =
            ActivityLap(
                activityId = activityId,
                lapNumber = 2,
                startedAt = Instant.parse("2026-01-01T00:05:00Z"),
                distance = 2.5,
                timeTakenSeconds = 750,
                averagePaceKmh = 12.0,
                averageHeartRate = 160,
                elevationGainMeters = 25.0,
            )
        activityLapRepository.saveAll(listOf(lap2, lap1))

        mockMvc
            .perform(get("/api/activities/{id}", activityId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.laps.length()").value(2))
            .andExpect(jsonPath("$.laps[0].lapNumber").value(1))
            .andExpect(jsonPath("$.laps[0].distance").value(1.0))
            .andExpect(jsonPath("$.laps[0].timeTakenSeconds").value(300))
            .andExpect(jsonPath("$.laps[0].averagePaceKmh").value(12.0))
            .andExpect(jsonPath("$.laps[0].averageHeartRate").value(145))
            .andExpect(jsonPath("$.laps[0].elevationGainMeters").value(10.5))
            .andExpect(jsonPath("$.laps[1].lapNumber").value(2))
            .andExpect(jsonPath("$.laps[1].distance").value(2.5))
            .andExpect(jsonPath("$.laps[1].timeTakenSeconds").value(750))
            .andExpect(jsonPath("$.laps[1].averageHeartRate").value(160))
            .andExpect(jsonPath("$.laps[1].elevationGainMeters").value(25.0))
    }

    @Test
    fun `an activity's detail returns its single lap or no laps unchanged`() {
        val activity0Id = checkNotNull(insertTestActivity().id)
        mockMvc
            .perform(get("/api/activities/{id}", activity0Id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.laps.length()").value(0))

        val activity1Id = checkNotNull(insertTestActivity().id)
        activityLapRepository.save(
            ActivityLap(
                activityId = activity1Id,
                lapNumber = 1,
                startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                distance = 5.0,
                timeTakenSeconds = 1500,
            ),
        )
        mockMvc
            .perform(get("/api/activities/{id}", activity1Id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.laps.length()").value(1))
            .andExpect(jsonPath("$.laps[0].distance").value(5.0))
    }

    @Test
    fun `deleting an activity cascade deletes all associated laps in PostgreSQL`() {
        val activityId = checkNotNull(insertTestActivity().id)

        val laps =
            listOf(
                ActivityLap(
                    activityId = activityId,
                    lapNumber = 1,
                    startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    distance = 1.0,
                    timeTakenSeconds = 300,
                ),
                ActivityLap(
                    activityId = activityId,
                    lapNumber = 2,
                    startedAt = Instant.parse("2026-01-01T00:05:00Z"),
                    distance = 1.0,
                    timeTakenSeconds = 300,
                ),
            )
        activityLapRepository.saveAll(laps)

        val countBefore =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activity_laps WHERE activity_id = ?",
                Int::class.java,
                activityId,
            )
        assertEquals(2, countBefore)

        mockMvc
            .perform(delete("/api/activities/{id}", activityId).with(csrf()))
            .andExpect(status().isNoContent)

        assertFalse(activityRepository.existsById(activityId))
        val countAfter =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM activity_laps WHERE activity_id = ?",
                Int::class.java,
                activityId,
            )
        assertEquals(0, countAfter)
        assertTrue(activityLapRepository.findByActivityIdOrderByLapNumberAsc(activityId).isEmpty())
    }

    @Test
    fun `activity list summaries never carry laps`() {
        val activityId = checkNotNull(insertTestActivity().id)
        activityLapRepository.saveAll(
            listOf(
                ActivityLap(
                    activityId = activityId,
                    lapNumber = 1,
                    startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                    distance = 1.0,
                    timeTakenSeconds = 300,
                ),
                ActivityLap(
                    activityId = activityId,
                    lapNumber = 2,
                    startedAt = Instant.parse("2026-01-01T00:05:00Z"),
                    distance = 1.0,
                    timeTakenSeconds = 300,
                ),
            ),
        )

        mockMvc
            .perform(get("/api/activities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value(activityId))
            .andExpect(jsonPath("$.items[0].laps").doesNotExist())
    }

    private fun createActivity(json: String): ResultActions =
        mockMvc.perform(post("/api/activities").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))

    private fun updateActivity(
        id: Long,
        json: String,
    ): ResultActions = mockMvc.perform(put("/api/activities/{id}", id).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
}
