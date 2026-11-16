package com.jacobcraig.stridehearth.heatmap

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.createdActivities
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **Heatmap** as the SPA sees it: Activities go in through the import module, and tiles come back from the
 * tile URL the renderer descriptor advertises, decoded as PNGs and read pixel by pixel.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "heatmap_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class HeatmapApiIntegrationTest {
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
        runner = userRepository.save(User(email = "heatmap@example.com", passwordHash = "x", username = "heatmap_runner"))
        otherAthlete = userRepository.save(User(email = "heatmap-other@example.com", passwordHash = "x", username = "heatmap_other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    // A z14 tile over Hyde Park, London; routes are laid out from its centre so they stay inside it.
    private val tile = Tile.containing(lon = -0.165, lat = 51.507, z = 14)
    private val origin = tile.centre()

    @Test
    fun `a tile over an imported route is a 512-pixel PNG lit along the route and transparent away from it`() {
        importRoute(runner, "east-500m", eastwards(origin, metres = 500.0))

        val raster = tileImage(tile)

        assertEquals(512 to 512, raster.width to raster.height)
        listOf(50.0, 250.0, 450.0).forEach { metres ->
            assertTrue(raster.isLitAt(tile.pixelOf(east(origin, metres))), "unlit $metres m along the route")
        }
        assertTrue(raster.isTransparentAt(tile.pixelOf(north(origin, 150.0))), "lit 150 m north of the route")
        assertTrue(raster.isTransparentAt(tile.pixelOf(east(origin, -150.0))), "lit 150 m before the route starts")
    }

    @Test
    fun `where two Activities overlap, even with GPS drift of a few metres, the tile is brighter than on one`() {
        val lone = north(origin, 200.0)
        importRoute(runner, "lone", eastwards(lone, metres = 600.0))
        importRoute(runner, "road-a", eastwards(origin, metres = 600.0))
        importRoute(runner, "road-b", eastwards(origin, metres = 600.0, step = 4.0, northOffset = 3.0))

        val raster = tileImage(tile)

        listOf(100.0, 300.0, 500.0).forEach { metres ->
            val single = raster.brightnessNear(tile.pixelOf(east(lone, metres)))
            val overlap = raster.brightnessNear(tile.pixelOf(east(origin, metres)))
            assertTrue(overlap > single * 1.2, "$metres m along: overlap $overlap is not brighter than single $single")
        }
    }

    @Test
    fun `a lone Activity in a second city stays as dim as the minimum allows, while the home corridor is near full brightness`() {
        val cityB = Tile.containing(lon = -1.90, lat = 52.48, z = 14)
        val lone = cityB.centre()
        importRoute(runner, "away", eastwards(lone, metres = 600.0))
        val onLone = cityB.pixelOf(east(lone, 300.0))
        // The dimmest a route can be: the athlete's whole Heatmap is this one Activity.
        val minimum = tileImage(cityB).brightnessNear(onLone)

        repeat(12) { importRoute(runner, "home-$it", eastwards(origin, metres = 600.0)) }
        val awayBrightness = tileImage(cityB).brightnessNear(onLone)
        val homeBrightness = tileImage(tile).brightnessNear(tile.pixelOf(east(origin, 300.0)))

        assertTrue(awayBrightness > 0.0, "the lone Activity is invisible")
        assertEquals(minimum, awayBrightness, 1e-9, "the lone Activity is brighter than the minimum")
        assertTrue(homeBrightness > 0.9 * 255, "the home corridor is only $homeBrightness bright")
        assertTrue(awayBrightness < 0.3 * homeBrightness, "the lone Activity is $awayBrightness against home $homeBrightness")

        // At the minimum already: a far busier home corridor does not dim it any further.
        repeat(40) { importRoute(runner, "more-home-$it", eastwards(origin, metres = 600.0)) }
        assertEquals(awayBrightness, tileImage(cityB).brightnessNear(onLone), 1e-9)
    }

    @Test
    fun `brightness follows the Heatmap Filter, so a route is brighter where it is among the busiest corridors`() {
        val rideCorridor = north(origin, 300.0)
        repeat(3) { importRoute(runner, "ride-$it", eastwards(rideCorridor, metres = 600.0), ActivitySport.RIDE) }
        repeat(20) { importRoute(runner, "run-$it", eastwards(origin, metres = 600.0)) }
        val onRide = tile.pixelOf(east(rideCorridor, 300.0))

        val amongRides = tileImage(tile, "?sports=RIDE").brightnessNear(onRide)
        val amongEverything = tileImage(tile).brightnessNear(onRide)

        assertTrue(amongRides > amongEverything * 1.5, "rides alone $amongRides, among busier runs $amongEverything")
    }

    @Test
    fun `another athlete's routes never change a tile's pixels`() {
        importRoute(runner, "mine-1", eastwards(origin, metres = 600.0))
        importRoute(runner, "mine-2", eastwards(origin, metres = 600.0))
        val before = fetchTile(tile).contentAsByteArray

        repeat(30) { importRoute(otherAthlete, "theirs-$it", eastwards(north(origin, 100.0), metres = 600.0)) }

        assertTrue(before.contentEquals(fetchTile(tile).contentAsByteArray), "another athlete's routes changed the tile")
    }

    @Test
    fun `a GPS jump beyond a run's threshold is left unlit`() {
        // 100 m, a 400 m jump, then 100 m more: a run splits at jumps over about 200 m.
        importRoute(runner, "jumpy-run", eastwards(origin, metres = 100.0) + eastwards(east(origin, 500.0), metres = 100.0))

        val raster = tileImage(tile)

        assertTrue(raster.isLitAt(tile.pixelOf(east(origin, 50.0))))
        assertTrue(raster.isLitAt(tile.pixelOf(east(origin, 550.0))))
        assertTrue(raster.isTransparentAt(tile.pixelOf(east(origin, 300.0))), "the jump was drawn")
    }

    @Test
    fun `a ride's gap within a ride's threshold is drawn`() {
        importRoute(
            runner,
            "gappy-ride",
            eastwards(origin, metres = 100.0) + eastwards(east(origin, 500.0), metres = 100.0),
            ActivitySport.RIDE,
        )

        assertTrue(tileImage(tile).isLitAt(tile.pixelOf(east(origin, 300.0))), "a ride's 400 m gap was split")
    }

    @Test
    fun `another athlete's routes never appear in a tile`() {
        importRoute(otherAthlete, "other-1", eastwards(origin, metres = 500.0))
        importRoute(otherAthlete, "other-2", eastwards(origin, metres = 500.0))

        assertTrue(tileImage(tile).isFullyTransparent)

        importRoute(runner, "mine", eastwards(origin, metres = 500.0))

        assertTrue(tileImage(tile).isLitAt(tile.pixelOf(east(origin, 250.0))))
    }

    @Test
    fun `Activities without a route contribute nothing and an athlete with no routes gets transparent tiles`() {
        assertTrue(tileImage(tile).isFullyTransparent)
        assertTrue(tileImage(Tile(0, 0, 0)).isFullyTransparent)
        val noRoutes = revision()

        importRoute(runner, "treadmill", emptyList())
        importRoute(runner, "single-fix", listOf(origin))
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "No details",
                    startedAt = nextStart,
                    distance = 5.0,
                    timeTakenSeconds = 1500,
                    externalId = "no-details",
                ),
            ),
            runner.id!!,
        )

        assertTrue(tileImage(tile).isFullyTransparent)
        assertEquals(noRoutes, revision())
    }

    @Test
    fun `tiles filter on start time and sport`() {
        importRoute(runner, "jan-run", eastwards(origin, metres = 300.0), startedAt = Instant.parse("2026-01-10T08:00:00Z"))
        importRoute(runner, "feb-ride", eastwards(origin, metres = 300.0), ActivitySport.RIDE, Instant.parse("2026-02-10T08:00:00Z"))
        importRoute(runner, "mar-run", eastwards(origin, metres = 300.0), startedAt = Instant.parse("2026-03-10T08:00:00Z"))
        val onRoute = tile.pixelOf(east(origin, 150.0))

        fun brightness(query: String) = tileImage(tile, query).brightnessNear(onRoute)

        // The same routes overlap exactly, so equal numbers of matching Activities give equal brightness.
        val three = brightness("")
        val two = brightness("?from=2026-02-01T00:00:00Z")
        val one = brightness("?to=2026-02-01T00:00:00Z")
        assertTrue(three > two && two > one && one > 0.0, "brightness for 3, 2, 1 Activities: $three, $two, $one")
        assertEquals(one, brightness("?from=2026-02-10T08:00:00Z&to=2026-03-10T08:00:00Z"))
        assertEquals(one, brightness("?sports=RIDE"))
        assertEquals(two, brightness("?sports=RUN"))
        assertEquals(three, brightness("?sports=RUN&sports=RIDE"))
        assertTrue(tileImage(tile, "?sports=RUN&from=2026-02-01T00:00:00Z&to=2026-03-01T00:00:00Z").isFullyTransparent)
    }

    @Test
    fun `date ranges may be open at the start, open at the end, or bounded on both sides`() {
        // Three parallel routes, a year apart, so each pixel shows which Activities a range matched.
        val y2024 = origin
        val y2025 = north(origin, 150.0)
        val y2026 = north(origin, -150.0)
        importRoute(runner, "run-2024", eastwards(y2024, metres = 300.0), startedAt = Instant.parse("2024-06-01T08:00:00Z"))
        importRoute(runner, "run-2025", eastwards(y2025, metres = 300.0), startedAt = Instant.parse("2025-06-01T08:00:00Z"))
        importRoute(runner, "run-2026", eastwards(y2026, metres = 300.0), startedAt = Instant.parse("2026-06-01T08:00:00Z"))

        fun lit(query: String) =
            tileImage(tile, query).let { raster ->
                listOf(y2024, y2025, y2026).filter { raster.isLitAt(tile.pixelOf(east(it, 150.0))) }
            }

        assertEquals(listOf(y2024, y2025), lit("?to=2026-01-01T00:00:00Z"))
        assertEquals(listOf(y2025, y2026), lit("?from=2025-01-01T00:00:00Z"))
        assertEquals(listOf(y2025), lit("?from=2025-01-01T00:00:00Z&to=2026-01-01T00:00:00Z"))
        // `from` is inclusive and `to` exclusive, so adjacent ranges never count an Activity twice.
        assertEquals(listOf(y2025), lit("?from=2025-06-01T08:00:00Z&to=2026-06-01T08:00:00Z"))
    }

    @Test
    fun `several sports filter to exactly those sports`() {
        val run = origin
        val ride = north(origin, 150.0)
        val walk = north(origin, -150.0)
        importRoute(runner, "the-run", eastwards(run, metres = 300.0))
        importRoute(runner, "the-ride", eastwards(ride, metres = 300.0), ActivitySport.RIDE)
        importRoute(runner, "the-walk", eastwards(walk, metres = 300.0), ActivitySport.WALK)

        fun lit(query: String) =
            tileImage(tile, query).let { raster ->
                listOf(run, ride, walk).filter { raster.isLitAt(tile.pixelOf(east(it, 150.0))) }
            }

        assertEquals(listOf(run, ride, walk), lit(""))
        assertEquals(listOf(run, walk), lit("?sports=RUN&sports=WALK"))
        assertEquals(listOf(ride, walk), lit("?sports=RIDE,WALK"))
        assertEquals(listOf(ride), lit("?sports=RIDE&sports=SWIM"))
    }

    @Test
    fun `an empty sport set matches nothing`() {
        importRoute(runner, "any-run", eastwards(origin, metres = 300.0))
        importRoute(runner, "any-ride", eastwards(origin, metres = 300.0), ActivitySport.RIDE)

        assertTrue(tileImage(tile, "?sports=").isFullyTransparent)
        assertTrue(tileImage(Tile(0, 0, 0), "?sports=").isFullyTransparent)
        assertTrue(getJson("/api/heatmap/starting-view?sports=")["bounds"].isNull)
    }

    @Test
    fun `a single sport edit moves the Activity to its new sport in filtered tiles and changes the revision`() {
        val edited = createdActivity(importRoute(runner, "was-a-run", eastwards(origin, metres = 300.0)))
        val onRoute = tile.pixelOf(east(origin, 150.0))
        assertTrue(tileImage(tile, "?sports=RUN").isLitAt(onRoute))
        val before = revision()

        editSport(edited, ActivitySport.WALK)

        assertNotEquals(before, revision())
        assertTrue(tileImage(tile, "?sports=RUN").isFullyTransparent, "still drawn as a run")
        assertTrue(tileImage(tile, "?sports=WALK").isLitAt(onRoute), "not drawn as a walk")
    }

    @Test
    fun `a batch sport edit moves every edited Activity to the new sport in filtered tiles and changes the revision`() {
        val first = createdActivity(importRoute(runner, "batch-a", eastwards(origin, metres = 300.0)))
        val second = createdActivity(importRoute(runner, "batch-b", eastwards(north(origin, 150.0), metres = 300.0)))
        val untouched = north(origin, -150.0)
        importRoute(runner, "batch-untouched", eastwards(untouched, metres = 300.0))
        val before = revision()

        mockMvc
            .perform(
                patch("/api/activities/batch")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"activityIds":[$first,$second],"sport":"HIKE"}"""),
            ).andExpect(status().isOk)

        assertNotEquals(before, revision())
        val hikes = tileImage(tile, "?sports=HIKE")
        assertTrue(hikes.isLitAt(tile.pixelOf(east(origin, 150.0))))
        assertTrue(hikes.isLitAt(tile.pixelOf(east(north(origin, 150.0), 150.0))))
        assertTrue(hikes.isTransparentAt(tile.pixelOf(east(untouched, 150.0))))
        val runs = tileImage(tile, "?sports=RUN")
        assertTrue(runs.isLitAt(tile.pixelOf(east(untouched, 150.0))))
        assertTrue(runs.isTransparentAt(tile.pixelOf(east(origin, 150.0))))
    }

    @Test
    fun `a sport edit that changes the GPS-jump threshold changes where the route splits`() {
        // A 400 m gap: split for a run (about 200 m), drawn for a ride (about 1 km).
        val gappy =
            createdActivity(
                importRoute(runner, "gappy", eastwards(origin, metres = 100.0) + eastwards(east(origin, 500.0), metres = 100.0)),
            )
        val inGap = tile.pixelOf(east(origin, 300.0))
        assertTrue(tileImage(tile).isTransparentAt(inGap), "a run's 400 m gap was drawn")

        editSport(gappy, ActivitySport.RIDE)
        assertTrue(tileImage(tile).isLitAt(inGap), "a ride's 400 m gap was split")

        batchEditSport(gappy, ActivitySport.RUN)
        assertTrue(tileImage(tile).isTransparentAt(inGap), "a run's 400 m gap was drawn after a batch edit")
    }

    @Test
    fun `a sport edit removes a route that no longer survives the split, and restores it when it does again`() {
        // Fixes 300 m apart: every step is a GPS jump for a run, so nothing of it is drawable as one.
        val sparse =
            createdActivity(importRoute(runner, "sparse-ride", eastwards(origin, metres = 600.0, step = 300.0), ActivitySport.RIDE))
        val onRoute = tile.pixelOf(east(origin, 150.0))
        assertTrue(tileImage(tile).isLitAt(onRoute))
        val asRide = revision()

        editSport(sparse, ActivitySport.RUN)
        val asRun = revision()
        assertNotEquals(asRide, asRun)
        assertTrue(tileImage(tile).isFullyTransparent)

        batchEditSport(sparse, ActivitySport.RIDE)
        assertNotEquals(asRun, revision())
        assertTrue(tileImage(tile).isLitAt(onRoute))
    }

    @Test
    fun `the years with routes are listed newest first, in the browser's timezone`() {
        // 23:30 on New Year's Eve in New York is already 2026 in UTC.
        importRoute(runner, "nye-run", eastwards(origin, metres = 300.0), startedAt = Instant.parse("2026-01-01T04:30:00Z"))
        importRoute(runner, "old-run", eastwards(origin, metres = 300.0), startedAt = Instant.parse("2023-05-01T08:00:00Z"))
        importRoute(otherAthlete, "not-mine", eastwards(origin, metres = 300.0), startedAt = Instant.parse("2021-05-01T08:00:00Z"))
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "No route",
                    startedAt = Instant.parse("2019-05-01T08:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1500,
                    externalId = "no-route",
                ),
            ),
            runner.id!!,
        )

        fun years(timeZone: String): List<Int> {
            val years = getJson("/api/heatmap/years?timeZone=$timeZone")["years"]
            return (0 until years.size()).map { years[it].asInt() }
        }

        assertEquals(listOf(2025, 2023), years("America/New_York"))
        assertEquals(listOf(2026, 2023), years("UTC"))
    }

    @Test
    fun `a run at 23 30 local time on New Year's Eve falls in that year's local-time range`() {
        importRoute(runner, "nye-run", eastwards(origin, metres = 300.0), startedAt = Instant.parse("2026-01-01T04:30:00Z"))
        val onRoute = tile.pixelOf(east(origin, 150.0))

        // 2025 and 2026 in New York, as the browser would send them.
        val in2025 = "?from=2025-01-01T05:00:00Z&to=2026-01-01T05:00:00Z"
        val in2026 = "?from=2026-01-01T05:00:00Z&to=2027-01-01T05:00:00Z"

        assertTrue(tileImage(tile, in2025).isLitAt(onRoute))
        assertTrue(tileImage(tile, in2026).isFullyTransparent)
    }

    @Test
    fun `the years of an unknown timezone are rejected`() {
        mockMvc.perform(get("/api/heatmap/years?timeZone=Not/AZone")).andExpect(status().isBadRequest)
    }

    @Test
    fun `tiles at the current revision are cached privately and for good`() {
        importRoute(runner, "cached", eastwards(origin, metres = 300.0))

        val response = fetchTile(tile)

        assertEquals("image/png", response.contentType)
        val cacheControl =
            response
                .getHeader("Cache-Control")!!
                .split(",")
                .map { it.trim() }
                .toSet()
        assertEquals(setOf("max-age=31536000", "private", "immutable"), cacheControl)
    }

    @Test
    fun `a tile requested at an outdated revision is not cached`() {
        val outdated = tileUrl(tile)
        importRoute(runner, "newer", eastwards(origin, metres = 300.0))

        val response =
            mockMvc
                .perform(get(outdated))
                .andExpect(status().isOk)
                .andReturn()
                .response

        assertEquals("no-store", response.getHeader("Cache-Control"))
    }

    @Test
    fun `a tile requested at an outdated renderer version is not cached`() {
        importRoute(runner, "restyled", eastwards(origin, metres = 300.0))
        val outdated = tileUrl(tile).replace("raster-density-v3", "raster-density-v2")

        val response =
            mockMvc
                .perform(get(outdated))
                .andExpect(status().isOk)
                .andReturn()
                .response

        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertTrue(RasterTile.decode(response.contentAsByteArray).isLitAt(tile.pixelOf(east(origin, 150.0))))
    }

    @Test
    fun `a tile that does not exist is rejected`() {
        mockMvc.perform(get(tileUrl(Tile(17, 0, 0)))).andExpect(status().isBadRequest)
        mockMvc.perform(get(tileUrl(Tile(2, 4, 0)))).andExpect(status().isBadRequest)
    }

    @Test
    fun `the revision changes with every change to the athlete's routes, but not with a title edit`() {
        val empty = revision()

        val created = importRoute(runner, "rev-run", eastwards(origin, metres = 300.0)).let { createdActivity(it) }
        val afterCreate = revision()
        assertNotEquals(empty, afterCreate)

        mockMvc
            .perform(
                put(
                    "/api/activities/{id}",
                    created,
                ).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"title":"Renamed","sport":"RUN"}"""),
            ).andExpect(status().isOk)
        assertEquals(afterCreate, revision())

        importRoute(
            runner,
            "rev-run",
            eastwards(origin, metres = 400.0),
            parserVersion =
                ParserVersion(
                    FitActivityParser.PARSER_VERSION.value + 1,
                ),
        )
        val afterUpgrade = revision()
        assertNotEquals(afterCreate, afterUpgrade)

        val second = importRoute(runner, "rev-second", eastwards(origin, metres = 300.0)).let { createdActivity(it) }
        val afterSecond = revision()

        mockMvc.perform(delete("/api/activities/{id}", created).with(csrf())).andExpect(status().isNoContent)
        val afterDelete = revision()
        assertNotEquals(afterSecond, afterDelete)

        mockMvc
            .perform(
                post(
                    "/api/activities/batch-delete",
                ).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""{"activityIds":[$second]}"""),
            ).andExpect(status().isOk)
        assertNotEquals(afterDelete, revision())
        assertEquals(5, setOf(empty, afterCreate, afterUpgrade, afterSecond, afterDelete).size)
    }

    @Test
    fun `the renderer descriptor names the raster density renderer and a tile URL with its version and the revision`() {
        val descriptor = getJson("/api/heatmap/renderer")

        assertEquals("raster-density", descriptor["id"].asString())
        assertEquals(16, descriptor["maxZoom"].asInt())
        assertEquals("/api/heatmap/tiles/raster-density-v3/{revision}/{z}/{x}/{y}", descriptor["tileUrlTemplate"].asString())
    }

    @Test
    fun `the starting view fits the athlete's routes, or is empty without any`() {
        assertNull(getJson("/api/heatmap/starting-view")["bounds"].takeUnless { it.isNull })
        importRoute(otherAthlete, "far-away", eastwards(LonLat(10.0, 45.0), metres = 300.0))
        importRoute(runner, "start-a", eastwards(origin, metres = 300.0))
        importRoute(runner, "start-b", eastwards(origin, metres = 300.0, northOffset = 200.0))

        val bounds = getJson("/api/heatmap/starting-view")["bounds"]

        val end = east(origin, 300.0)
        assertEquals(origin.lon, bounds["west"].asDouble(), 1e-4)
        assertEquals(end.lon, bounds["east"].asDouble(), 1e-4)
        assertEquals(origin.lat, bounds["south"].asDouble(), 1e-4)
        assertEquals(origin.lat + 200.0 / METRES_PER_DEGREE_LAT, bounds["north"].asDouble(), 1e-4)
    }

    @Nested
    @WithAnonymousUser
    inner class SignedOut {
        @Test
        fun `every heatmap endpoint requires sign-in`() {
            listOf(
                "/api/heatmap/revision",
                "/api/heatmap/renderer",
                "/api/heatmap/starting-view",
                "/api/heatmap/years?timeZone=UTC",
                "/api/heatmap/tiles/raster-density-v1/0-0/14/0/0",
            ).forEach { path -> mockMvc.perform(get(path)).andExpect(status().isUnauthorized) }
        }
    }

    // ---- helpers ----

    private var nextStart = Instant.parse("2026-03-01T07:00:00Z")

    private fun importRoute(
        owner: User,
        externalId: String,
        route: List<LonLat>,
        sport: ActivitySport = ActivitySport.RUN,
        startedAt: Instant = nextStart.also { nextStart = it.plusSeconds(86_400) },
        parserVersion: ParserVersion = FitActivityParser.PARSER_VERSION,
    ) = activityImportService.importParsed(
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
                parserVersion = parserVersion,
            ),
        ),
        owner.id!!,
    )

    private fun createdActivity(outcome: com.jacobcraig.stridehearth.activities.ImportOutcome): Long =
        outcome.createdActivities.single().id!!

    private fun editSport(
        activityId: Long,
        sport: ActivitySport,
    ) {
        mockMvc
            .perform(
                put("/api/activities/{id}", activityId)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"title":"Edited","sport":"$sport"}"""),
            ).andExpect(status().isOk)
    }

    private fun batchEditSport(
        activityId: Long,
        sport: ActivitySport,
    ) {
        mockMvc
            .perform(
                patch("/api/activities/batch")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"activityIds":[$activityId],"sport":"$sport"}"""),
            ).andExpect(status().isOk)
    }

    private fun getJson(path: String) =
        json.readTree(
            mockMvc
                .perform(get(path))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun revision(): String = getJson("/api/heatmap/revision")["revision"].asString()

    /** The tile URL the SPA would request, from the renderer descriptor's template. */
    private fun tileUrl(
        tile: Tile,
        query: String = "",
    ): String =
        getJson("/api/heatmap/renderer")["tileUrlTemplate"]
            .asString()
            .replace("{revision}", revision())
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString()) + query

    private fun fetchTile(
        tile: Tile,
        query: String = "",
    ) = mockMvc
        .perform(get(tileUrl(tile, query)))
        .andExpect(status().isOk)
        .andReturn()
        .response

    private fun tileImage(
        tile: Tile,
        query: String = "",
    ): RasterTile = RasterTile.decode(fetchTile(tile, query).contentAsByteArray)
}

data class LonLat(
    val lon: Double,
    val lat: Double,
)

private const val EARTH_RADIUS = 6_371_000.0

fun metresBetween(
    a: LonLat,
    b: LonLat,
): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon) * Math.cos(Math.toRadians((a.lat + b.lat) / 2))
    return EARTH_RADIUS * Math.hypot(dLat, dLon)
}

val METRES_PER_DEGREE_LAT = Math.toRadians(1.0) * EARTH_RADIUS

private fun metresPerDegreeLon(lat: Double) = METRES_PER_DEGREE_LAT * Math.cos(Math.toRadians(lat))

/** The point [metres] east of [start]. */
fun east(
    start: LonLat,
    metres: Double,
) = LonLat(start.lon + metres / metresPerDegreeLon(start.lat), start.lat)

/** The point [metres] north of [start]. */
fun north(
    start: LonLat,
    metres: Double,
) = LonLat(start.lon, start.lat + metres / METRES_PER_DEGREE_LAT)

/** Points every [step] metres heading east from [start], [metres] long, optionally [northOffset] metres north. */
fun eastwards(
    start: LonLat,
    metres: Double,
    step: Double = 5.0,
    northOffset: Double = 0.0,
): List<LonLat> =
    (0..(metres / step).toInt()).map { i ->
        LonLat(start.lon + i * step / metresPerDegreeLon(start.lat), start.lat + northOffset / METRES_PER_DEGREE_LAT)
    }

/** A slippy-map (XYZ, Web Mercator) tile. */
data class Tile(
    val z: Int,
    val x: Int,
    val y: Int,
) {
    private val n get() = 1 shl z

    fun centre(): LonLat = toLonLat(PIXELS / 2.0, PIXELS / 2.0)

    /** The pixel of this tile's 512-pixel image that holds [point]. */
    fun pixelOf(point: LonLat): Pixel {
        val latRad = Math.toRadians(point.lat)
        val fx = (point.lon + 180.0) / 360.0 * n
        val fy = (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n
        return Pixel(((fx - x) * PIXELS).toInt(), ((fy - y) * PIXELS).toInt())
    }

    private fun toLonLat(
        px: Double,
        py: Double,
    ): LonLat {
        val fx = (x + px / PIXELS) / n
        val fy = (y + py / PIXELS) / n
        return LonLat(fx * 360.0 - 180.0, Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * fy)))))
    }

    companion object {
        const val PIXELS = 512

        fun containing(
            lon: Double,
            lat: Double,
            z: Int,
        ): Tile {
            val n = 1 shl z
            val latRad = Math.toRadians(lat)
            val x = ((lon + 180.0) / 360.0 * n).toInt()
            val y = ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n).toInt()
            return Tile(z, x, y)
        }
    }
}
