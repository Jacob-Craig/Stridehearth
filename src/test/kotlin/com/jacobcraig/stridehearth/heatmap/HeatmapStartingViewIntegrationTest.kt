package com.jacobcraig.stridehearth.heatmap

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the **Heatmap** opens: the starting view is the busiest area of the athlete's routes under the
 * **Heatmap Filter**, not the bounds of every route, which would be the whole world for an athlete who has trained on
 * two continents. Activities go in through the import module; the starting view comes back from the HTTP API.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "start_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class HeatmapStartingViewIntegrationTest {
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

    private val json = JsonMapper.builder().build()

    private lateinit var runner: User
    private lateinit var otherAthlete: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "start@example.com", passwordHash = "x", username = "start_runner"))
        otherAthlete = userRepository.save(User(email = "start-other@example.com", passwordHash = "x", username = "start_other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private val hydePark = LonLat(-0.17, 51.505)
    private val amsterdam = LonLat(4.90, 52.37)
    private val paris = LonLat(2.35, 48.86)

    /** Five runs spread across London: from Hyde Park to 12 km east and 6 km north. */
    private fun importLondonCluster(owner: User = runner) {
        importRoute(owner, eastwards(hydePark, metres = 2_000.0))
        importRoute(owner, eastwards(hydePark, metres = 2_000.0, northOffset = 100.0))
        importRoute(owner, eastwards(east(hydePark, 10_000.0), metres = 2_000.0))
        importRoute(owner, eastwards(north(hydePark, 6_000.0), metres = 1_000.0))
        importRoute(owner, eastwards(hydePark, metres = 500.0))
    }

    private val londonClusterBounds =
        Bounds(west = hydePark.lon, south = hydePark.lat, east = east(hydePark, 12_000.0).lon, north = north(hydePark, 6_000.0).lat)

    @Test
    fun `the starting view fits the athlete's dense cluster in one city, not the few Activities in another`() {
        importLondonCluster()
        importRoute(runner, eastwards(amsterdam, metres = 3_000.0))
        importRoute(runner, eastwards(amsterdam, metres = 3_000.0, northOffset = 500.0))

        assertEquals(londonClusterBounds, startingView())
    }

    @Test
    fun `a training area spread wide counts as one, beating a smaller compact cluster elsewhere`() {
        // Six runs 6 km apart along 30 km of the London area, against four compact runs in Amsterdam.
        (0..5).forEach { i -> importRoute(runner, eastwards(east(hydePark, i * 6_000.0), metres = 500.0)) }
        repeat(4) { importRoute(runner, eastwards(amsterdam, metres = 1_000.0)) }

        assertEquals(Bounds(hydePark.lon, hydePark.lat, east(hydePark, 30_500.0).lon, hydePark.lat), startingView())
    }

    @Test
    fun `the starting view fits the busiest area under the Heatmap Filter`() {
        importLondonCluster()
        // Rides: two in Amsterdam, one in Paris.
        importRoute(runner, eastwards(amsterdam, metres = 3_000.0), ActivitySport.RIDE, Instant.parse("2025-06-01T08:00:00Z"))
        importRoute(runner, eastwards(amsterdam, metres = 1_000.0), ActivitySport.RIDE, Instant.parse("2025-06-02T08:00:00Z"))
        importRoute(runner, eastwards(paris, metres = 5_000.0), ActivitySport.RIDE, Instant.parse("2025-06-03T08:00:00Z"))
        importRoute(runner, eastwards(paris, metres = 2_000.0), startedAt = Instant.parse("2024-06-01T08:00:00Z"))
        val amsterdamRide = Bounds(amsterdam.lon, amsterdam.lat, east(amsterdam, 3_000.0).lon, amsterdam.lat)
        val parisRun = Bounds(paris.lon, paris.lat, east(paris, 2_000.0).lon, paris.lat)

        assertEquals(amsterdamRide, startingView("?sports=RIDE"))
        assertEquals(parisRun, startingView("?to=2025-01-01T00:00:00Z"))
        assertEquals(amsterdamRide, startingView("?from=2025-01-01T00:00:00Z&to=2026-01-01T00:00:00Z"))
        assertEquals(londonClusterBounds, startingView("?sports=RUN&from=2026-01-01T00:00:00Z"))
    }

    @Test
    fun `the starting view is empty when no routes match the Heatmap Filter`() {
        assertEquals(null, startingView())

        importLondonCluster()

        assertEquals(null, startingView("?sports=SWIM"))
        assertEquals(null, startingView("?to=2020-01-01T00:00:00Z"))
    }

    @Test
    fun `the starting view is scoped to the signed-in athlete`() {
        importLondonCluster(owner = otherAthlete)
        importRoute(otherAthlete, eastwards(paris, metres = 2_000.0))

        assertEquals(null, startingView())

        importRoute(runner, eastwards(paris, metres = 2_000.0))

        assertEquals(Bounds(paris.lon, paris.lat, east(paris, 2_000.0).lon, paris.lat), startingView())
    }

    // ---- helpers ----

    /** Bounds in degrees, equal within about a metre. */
    private class Bounds(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
    ) {
        private val sides get() = listOf(west, south, east, north)

        override fun equals(other: Any?) = other is Bounds && sides.zip(other.sides).all { (a, b) -> Math.abs(a - b) < 1e-5 }

        override fun hashCode() = 0

        override fun toString() = "Bounds(west=$west, south=$south, east=$east, north=$north)"
    }

    private fun startingView(query: String = ""): Bounds? {
        val view =
            json.readTree(
                mockMvc
                    .perform(get("/api/heatmap/starting-view$query"))
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertTrue(view.has("bounds"), "the starting view always carries bounds, null when empty")
        val bounds: JsonNode = view["bounds"]
        if (bounds.isNull) return null
        return Bounds(bounds["west"].asDouble(), bounds["south"].asDouble(), bounds["east"].asDouble(), bounds["north"].asDouble())
    }

    private var routes = 0
    private var nextStart = Instant.parse("2026-03-01T07:00:00Z")

    private fun importRoute(
        owner: User,
        route: List<LonLat>,
        sport: ActivitySport = ActivitySport.RUN,
        startedAt: Instant = nextStart.also { nextStart = it.plusSeconds(86_400) },
    ) {
        val externalId = "route-${routes++}"
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    sport = sport,
                    title = externalId,
                    startedAt = startedAt,
                    distance = 1.0,
                    timeTakenSeconds = route.size,
                    externalId = externalId,
                    telemetry =
                        FitTelemetry(
                            t = IntArray(route.size) { it },
                            lons = route.map { it.lon }.toDoubleArray(),
                            lats = route.map { it.lat }.toDoubleArray(),
                            routeIndex = Array(route.size) { it },
                        ),
                ),
            ),
            owner.id!!,
        )
    }
}
