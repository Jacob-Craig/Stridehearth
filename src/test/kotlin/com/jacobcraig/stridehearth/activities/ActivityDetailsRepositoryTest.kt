package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance tests for [ActivityDetailsRepository] executing against the PostGIS Testcontainer.
 * Verifies the unguarded write (whether to write is decided by [ActivityDetailsService.offer]), PostGIS route
 * creation and user authorization.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ActivityDetailsRepositoryTest {
    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    private lateinit var testUser: User
    private lateinit var testActivity: Activity

    @BeforeEach
    fun setUp() {
        testUser = userRepository.save(User(email = "repo_test@example.com", passwordHash = "x", username = "repo_runner"))
        testActivity =
            activityRepository.save(
                Activity(
                    title = "Test Run",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.now(),
                    distance = 10.0,
                    timeTakenSeconds = 3600,
                    user = testUser,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun sampleTelemetry(hrBase: Short = 140): FitTelemetry =
        FitTelemetry(
            t = intArrayOf(0, 1, 2),
            lons = doubleArrayOf(-0.16, -0.17, -0.18),
            lats = doubleArrayOf(51.51, 51.52, 51.53),
            routeIndex = arrayOf(0, 1, 2),
            heartRate = arrayOf(hrBase, (hrBase + 5).toShort(), null),
            power = arrayOf(200.toShort(), 210.toShort(), null),
            cadence = arrayOf(85.toShort(), 86.toShort(), null),
            altitude = arrayOf(100.0f, 102.0f, 105.0f),
            speed = arrayOf(3.0f, 3.2f, 3.1f),
            distance = arrayOf(0.0f, 3.0f, 6.2f),
        )

    @Test
    fun `acceptance test 1 - first write inserts the row and returns 1`() {
        val rowsAffected = activityDetailsRepository.upsert(testActivity.id!!, sampleTelemetry(), ParserVersion(1))
        assertEquals(1, rowsAffected, "First write should insert row and return 1")

        val json = activityDetailsRepository.findTelemetryJson(testActivity.id!!, testUser.id!!)
        assertNotNull(json)
        assertTrue(json.contains("\"t\": [0, 1, 2]"))
        assertTrue(json.contains("\"heartRate\": [140, 145, null]"))
    }

    @Test
    fun `acceptance test 3 - writing again replaces the stored arrays and version`() {
        // Write version 1
        activityDetailsRepository.upsert(testActivity.id!!, sampleTelemetry(140), ParserVersion(1))

        // Write version 2 with newer data
        val rowsAffected = activityDetailsRepository.upsert(testActivity.id!!, sampleTelemetry(160), ParserVersion(2))
        assertEquals(1, rowsAffected, "Writing again should return 1")

        val json = activityDetailsRepository.findTelemetryJson(testActivity.id!!, testUser.id!!)
        assertNotNull(json)
        assertTrue(json.contains("\"heartRate\": [160, 165, null]"), "Data should be replaced with version 2")
    }

    @Test
    fun `acceptance test 5 - findTelemetryJson returns GeoJSON plus arrays and returns null for another user`() {
        activityDetailsRepository.upsert(testActivity.id!!, sampleTelemetry(), ParserVersion(1))

        // Valid owner retrieval
        val ownerJson = activityDetailsRepository.findTelemetryJson(testActivity.id!!, testUser.id!!)
        assertNotNull(ownerJson)
        assertTrue(ownerJson.contains("\"route\""), "GeoJSON route should be present")
        assertTrue(ownerJson.contains("\"LineString\""), "Route should be LineString GeoJSON")
        assertTrue(ownerJson.contains("\"t\": [0, 1, 2]"))

        // Another user should receive null
        val otherUser = userRepository.save(User(email = "other_user@example.com", passwordHash = "x", username = "other_runner"))
        val otherResult = activityDetailsRepository.findTelemetryJson(testActivity.id!!, otherUser.id!!)
        assertNull(otherResult, "findTelemetryJson must return null for non-owner user")
    }

    @Test
    fun `findTelemetryJson returns nested contract with sport, full route and telemetry arrays`() {
        activityDetailsRepository.upsert(testActivity.id!!, sampleTelemetry(), ParserVersion(1))

        val json =
            JsonMapper.builder().build().readTree(
                activityDetailsRepository.findTelemetryJson(testActivity.id!!, testUser.id!!),
            )

        assertEquals(testActivity.id!!, json["activityId"].asLong())
        assertEquals("RUN", json["sport"].asString())

        val route = json["route"]
        assertEquals("LineString", route["type"].asString())
        // Route is NOT simplified: every recorded GPS fix is kept so telemetry.routeIndex stays valid.
        assertEquals(3, route["coordinates"].size())
        assertEquals(-0.17, route["coordinates"][1][0].asDouble(), 1e-9)
        assertEquals(51.52, route["coordinates"][1][1].asDouble(), 1e-9)

        val telemetry = json["telemetry"]
        assertEquals(listOf("0", "1", "2"), telemetry["t"].jsonTexts())
        assertEquals(listOf("0", "1", "2"), telemetry["routeIndex"].jsonTexts())
        assertEquals(listOf("140", "145", "null"), telemetry["heartRate"].jsonTexts())
        assertEquals(listOf(100.0, 102.0, 105.0), (0 until 3).map { telemetry["altitude"][it].asDouble() })
        assertTrue(telemetry.has("saturatedHemoglobinPercentMax"), "All persisted sensor arrays are exposed")
        assertFalse(json.has("t"), "Sensor arrays live under 'telemetry', not at the root")
    }

    @Test
    fun `route is null when fewer than 2 coordinates are present`() {
        val singlePointTelemetry =
            FitTelemetry(
                t = intArrayOf(0),
                lons = doubleArrayOf(-0.16),
                lats = doubleArrayOf(51.51),
                routeIndex = arrayOf(0),
            )
        activityDetailsRepository.upsert(testActivity.id!!, singlePointTelemetry, ParserVersion(1))

        val json = activityDetailsRepository.findTelemetryJson(testActivity.id!!, testUser.id!!)
        assertNotNull(json)
        assertTrue(json.contains("\"route\": null"), "Route must be null when fewer than 2 GPS fixes exist")
    }
}

/** Renders each element of a JSON array as its JSON text, e.g. [140, null] -> ["140", "null"]. */
private fun JsonNode.jsonTexts(): List<String> = (0 until size()).map { get(it).toString() }
