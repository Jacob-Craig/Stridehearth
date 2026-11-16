package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParsedActivityLap
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Activity Details module's offer, through its interface against real Postgres: the returned change
 * and what is then stored.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ActivityDetailsServiceTest {
    @Autowired
    private lateinit var activityDetailsService: ActivityDetailsService

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var activityLapRepository: ActivityLapRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    private lateinit var athlete: User
    private lateinit var activity: Activity

    @BeforeEach
    fun setUp() {
        athlete = userRepository.save(User(email = "offer@example.com", passwordHash = "x", username = "offer_runner"))
        activity =
            activityRepository.save(
                Activity(
                    title = "Offered Run",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.parse("2026-03-01T08:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1500,
                    user = athlete,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun details(
        heartRate: Short = 140,
        distance: Array<Float?> = arrayOf(0.0f, 2500.0f, 5000.0f),
    ) = FitTelemetry(
        t = intArrayOf(0, 750, 1500),
        lons = doubleArrayOf(),
        lats = doubleArrayOf(),
        routeIndex = arrayOfNulls(3),
        heartRate = arrayOf(heartRate, heartRate, heartRate),
        distance = distance,
    )

    private val noDistance: Array<Float?> = arrayOfNulls(3)

    private fun laps(count: Int) =
        (1..count).map {
            ParsedActivityLap(
                lapNumber = it,
                startedAt = Instant.parse("2026-03-01T08:00:00Z").plusSeconds(it * 300L),
                distance = 1.0,
                timeTakenSeconds = 300,
            )
        }

    private fun offer(
        details: FitTelemetry?,
        version: Int,
        laps: List<ParsedActivityLap> = emptyList(),
    ): ActivityDetailsChange =
        transactionTemplate.execute { activityDetailsService.offer(activity, details, laps, ParserVersion(version)) }!!

    private fun storedTelemetry(): JsonNode? =
        activityDetailsRepository
            .findTelemetryJson(activity.id!!, athlete.id!!)
            ?.let { JsonMapper.builder().build().readTree(it)["telemetry"] }

    private fun storedHeartRate(): Int? = storedTelemetry()?.get("heartRate")?.get(0)?.asInt()

    private fun storedLapCount(): Int = activityLapRepository.findByActivityIdOrderByLapNumberAsc(activity.id!!).size

    @Test
    fun `offering details for an Activity with none stored backfills them`() {
        val change = offer(details(heartRate = 150), version = 3)

        assertEquals(ActivityDetailsChange.Changed(DetailsChange.Backfilled(ParserVersion(3)), laps = null), change)
        assertEquals(150, storedHeartRate())
    }

    @Test
    fun `offering details from a newer Parser Version upgrades the stored ones and replaces their Laps`() {
        offer(details(heartRate = 140), version = 1, laps = laps(2))

        val change = offer(details(heartRate = 160), version = 2, laps = laps(3))

        assertEquals(
            ActivityDetailsChange.Changed(
                DetailsChange.Upgraded(from = ParserVersion(1), to = ParserVersion(2)),
                LapsChange.Replaced(3),
            ),
            change,
        )
        assertEquals(160, storedHeartRate())
        assertEquals(3, storedLapCount())
    }

    @Test
    fun `offering details from the stored Parser Version changes nothing`() {
        offer(details(heartRate = 140), version = 1, laps = laps(2))

        val change = offer(details(heartRate = 160), version = 1, laps = laps(3))

        assertEquals(ActivityDetailsChange.Unchanged, change)
        assertEquals(140, storedHeartRate())
        assertEquals(2, storedLapCount())
    }

    @Test
    fun `details from a newer Parser Version are never overwritten by an older one, even to add distance`() {
        offer(details(heartRate = 160, distance = noDistance), version = 2, laps = laps(1))

        val change = offer(details(heartRate = 140), version = 1, laps = laps(2))

        assertEquals(ActivityDetailsChange.Unchanged, change)
        assertEquals(160, storedHeartRate())
        assertEquals(1, storedLapCount())
    }

    @Test
    fun `Rule 3 literal reading - an offer from an older Parser Version still backfills Laps when none are stored`() {
        offer(details(heartRate = 160), version = 2)

        val change = offer(details(heartRate = 140), version = 1, laps = laps(2))

        // Rule 3 reads "Laps are backfilled when nothing else changed, no Laps are stored, and the offer has
        // Laps", with no condition on the offered Parser Version. The spec owner may tighten this later.
        assertEquals(ActivityDetailsChange.Changed(details = null, laps = LapsChange.Backfilled(2)), change)
        assertEquals(160, storedHeartRate())
        assertEquals(2, storedLapCount())
    }

    @Test
    fun `stored details that lack distance are distance backfilled from offered details that have it`() {
        offer(details(heartRate = 140, distance = noDistance), version = 1, laps = laps(1))

        val change = offer(details(heartRate = 150), version = 1, laps = laps(2))

        assertEquals(
            ActivityDetailsChange.Changed(DetailsChange.DistanceBackfilled(ParserVersion(1)), LapsChange.Replaced(2)),
            change,
        )
        assertEquals(5000.0, storedTelemetry()!!["distance"][2].asDouble(), 1e-6)
        assertEquals(2, storedLapCount())
    }

    @Test
    fun `a stored series of fewer than two samples that lacks distance can be distance backfilled`() {
        val oneSample = { distance: Float? ->
            FitTelemetry(
                t = intArrayOf(0),
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOfNulls(1),
                distance = arrayOf(distance),
            )
        }
        offer(oneSample(null), version = 1)

        val change = offer(oneSample(0.0f), version = 1)

        assertEquals(ActivityDetailsChange.Changed(DetailsChange.DistanceBackfilled(ParserVersion(1)), laps = null), change)
    }

    @Test
    fun `offered details that also lack distance do not backfill stored details that lack it`() {
        offer(details(heartRate = 140, distance = noDistance), version = 1)

        val change = offer(details(heartRate = 150, distance = noDistance), version = 1)

        assertEquals(ActivityDetailsChange.Unchanged, change)
        assertEquals(140, storedHeartRate())
    }

    @Test
    fun `Laps are backfilled when nothing else changed and none are stored`() {
        offer(details(heartRate = 140), version = 1)

        val change = offer(details(heartRate = 150), version = 1, laps = laps(3))

        assertEquals(ActivityDetailsChange.Changed(details = null, laps = LapsChange.Backfilled(3)), change)
        assertEquals(140, storedHeartRate())
        assertEquals(3, storedLapCount())
    }

    @Test
    fun `an offer without details or Laps changes nothing`() {
        assertEquals(ActivityDetailsChange.Unchanged, offer(details = null, version = 1))
        assertNull(storedTelemetry())
    }

    @Test
    fun `offering requires the caller's transaction`() {
        assertThrows<IllegalTransactionStateException> {
            activityDetailsService.offer(activity, details(), emptyList(), ParserVersion(1))
        }
        assertNull(storedTelemetry())
    }

    @Test
    fun `concurrent offers for the same Activity serialize, so the second decides against what the first committed`() {
        offer(details(heartRate = 140), version = 1)
        val firstHasWritten = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first =
                pool.submit(
                    Callable {
                        transactionTemplate.execute {
                            activityDetailsService.offer(activity, details(heartRate = 160), laps(2), ParserVersion(2)).also {
                                firstHasWritten.countDown()
                                // Hold the uncommitted write so the second offer runs while it is in flight.
                                Thread.sleep(500)
                            }
                        }!!
                    },
                )
            val second =
                pool.submit(
                    Callable {
                        firstHasWritten.await(10, TimeUnit.SECONDS)
                        transactionTemplate.execute {
                            activityDetailsService.offer(activity, details(heartRate = 170), laps(2), ParserVersion(2))
                        }!!
                    },
                )

            assertEquals(
                ActivityDetailsChange.Changed(
                    DetailsChange.Upgraded(from = ParserVersion(1), to = ParserVersion(2)),
                    LapsChange.Replaced(2),
                ),
                first.get(30, TimeUnit.SECONDS),
            )
            assertEquals(ActivityDetailsChange.Unchanged, second.get(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(160, storedHeartRate())
        assertEquals(2, storedLapCount())
    }

    @Test
    fun `the time and distance series of details that have distance is read as stored`() {
        offer(details(distance = arrayOf(0.0f, 2400.0f, 4900.0f)), version = 1)

        val series = activityDetailsService.findTimeAndDistanceSeries(activity.id!!)

        assertNotNull(series)
        assertEquals(listOf(0, 750, 1500), series.t.toList())
        assertEquals(listOf(0.0f, 2400.0f, 4900.0f), series.distance.toList())
    }

    @Test
    fun `the time and distance series of details that lack distance is synthesized in memory, leaving the row unchanged`() {
        // 0.005 deg of longitude at the equator is ~555.97 m, so the route covers ~1111.95 m.
        activityDetailsRepository.upsert(
            activity.id!!,
            FitTelemetry(
                t = intArrayOf(0, 125, 250),
                lons = doubleArrayOf(0.0, 0.005, 0.010),
                lats = doubleArrayOf(0.0, 0.0, 0.0),
                routeIndex = arrayOf(0, 1, 2),
                enhancedSpeed = arrayOf(4.4f, 4.4f, 4.4f),
                distance = arrayOf(null, null, null),
            ),
            ParserVersion(1),
        )
        val storedRowBefore = storedRowAsJson(activity.id!!)

        val series = activityDetailsService.findTimeAndDistanceSeries(activity.id!!)

        assertNotNull(series)
        assertEquals(listOf(0, 125, 250), series.t.toList())
        assertEquals(0.0f, series.distance[0]!!, 1e-2f)
        assertEquals(1111.95f, series.distance[2]!!, 1.0f)
        assertEquals(storedRowBefore, storedRowAsJson(activity.id!!), "The read must not write to the stored row")
        assertEquals(
            "{NULL,NULL,NULL}",
            jdbcClient
                .sql("SELECT distance::text FROM activity_details WHERE activity_id = :id")
                .param("id", activity.id!!)
                .query(String::class.java)
                .single(),
        )
    }

    @Test
    fun `an Activity without stored details has no time and distance series`() {
        assertNull(activityDetailsService.findTimeAndDistanceSeries(activity.id!!))
    }

    private fun storedRowAsJson(activityId: Long): String =
        jdbcClient
            .sql("SELECT to_jsonb(d)::text FROM activity_details d WHERE d.activity_id = :id")
            .param("id", activityId)
            .query(String::class.java)
            .single()
}
