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
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Best Effort refresh module, tested through its interface against real Postgres: each test applies a
 * change to an **Activity**, refreshes it in the same transaction, and checks the stored **Best Efforts** and
 * the athlete's **Personal Records**.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class BestEffortRefreshTest {
    @Autowired
    private lateinit var bestEffortRefresh: BestEffortRefresh

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var activityBestEffortRepository: ActivityBestEffortRepository

    @Autowired
    private lateinit var personalRecordRepository: PersonalRecordRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var runner: User
    private lateinit var otherRunner: User
    private lateinit var fixtures: BestEffortFixtures

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "refresh_runner@example.com", passwordHash = "x", username = "refresh_runner"))
        otherRunner = userRepository.save(User(email = "refresh_other@example.com", passwordHash = "x", username = "refresh_other"))
        fixtures =
            BestEffortFixtures(
                activityRepository,
                activityDetailsRepository,
                personalRecordRepository,
                bestEffortRefresh,
                transactionTemplate,
                defaultOwner = runner,
            )
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun fiveKRecord(owner: User = runner) = fixtures.runningRecords(owner)[BenchmarkDistance.FIVE_KILOMETERS]

    /** The Personal Record [activity] should hold at [seconds]. */
    private fun heldBy(
        activity: Activity,
        seconds: Double,
    ): Pair<Long, Double> = activity.id!! to seconds

    private fun storedEfforts(activity: Activity): Map<BenchmarkDistance, Double> =
        activityBestEffortRepository.findByActivityId(activity.id!!).associate { it.benchmarkDistance to it.elapsedTimeSeconds }

    private fun deleteAndRefresh(vararg activities: Activity): List<ActivityBestEffort> =
        fixtures.inTransaction {
            val deleted = activities.map { fixtures.reload(it) }
            activityRepository.deleteAll(deleted)
            if (deleted.size == 1) bestEffortRefresh.refresh(deleted.single()) else bestEffortRefresh.refresh(deleted)
        }

    @Test
    fun `a new Activity takes the Personal Record only when it is faster`() {
        val steady = fixtures.importActivity("Steady 5k", "2026-04-05T08:00:00Z", steadyDetails(5000f, 1500))
        assertEquals(heldBy(steady, 1500.0), fiveKRecord())

        val fast = fixtures.importActivity("Fast 5k", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        assertEquals(heldBy(fast, 1200.0), fiveKRecord())

        fixtures.importActivity("Slower 5k", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1320))
        assertEquals(heldBy(fast, 1200.0), fiveKRecord())
        assertEquals(heldBy(fast, 96.0), fixtures.runningRecords()[BenchmarkDistance.FOUR_HUNDRED_METERS])
    }

    @Test
    fun `refreshing a new Activity returns the Best Efforts it now holds`() {
        val efforts =
            fixtures.inTransaction {
                val details = steadyDetails(1000f, 250)
                val run = fixtures.storeActivity("1k", "2026-04-05T08:00:00Z", details)
                bestEffortRefresh.refresh(run, details)
            }

        assertEquals(
            mapOf(BenchmarkDistance.FOUR_HUNDRED_METERS to 100.0, BenchmarkDistance.ONE_KILOMETER to 250.0),
            efforts.associate { it.benchmarkDistance to it.elapsedTimeSeconds },
        )
    }

    @Test
    fun `an older Activity imported out of order takes the Personal Record when it is faster`() {
        fixtures.importActivity("Recent 5k", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val older = fixtures.importActivity("Old 5k", "2026-03-01T08:00:00Z", steadyDetails(5000f, 1100))

        assertEquals(heldBy(older, 1100.0), fiveKRecord())
    }

    @Test
    fun `on an exact tie the earlier Activity holds the Personal Record`() {
        val first = fixtures.importActivity("First 5k", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        fixtures.importActivity("Later tie", "2026-04-15T08:00:00Z", steadyDetails(5000f, 1200))
        assertEquals(heldBy(first, 1200.0), fiveKRecord())

        val earlier = fixtures.importActivity("Earlier tie", "2026-03-01T08:00:00Z", steadyDetails(5000f, 1200))
        assertEquals(heldBy(earlier, 1200.0), fiveKRecord())
    }

    @Test
    fun `a details upgrade that makes an effort slower moves the Personal Record to the next-fastest Activity`() {
        val holder = fixtures.importActivity("Holder", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val runnerUp = fixtures.importActivity("Runner-up", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1500))

        fixtures.inTransaction {
            val upgraded = steadyDetails(5000f, 1800)
            activityDetailsRepository.upsert(holder.id!!, upgraded, ParserVersion(2))
            bestEffortRefresh.refresh(fixtures.reload(holder), upgraded)
        }

        assertEquals(1800.0, storedEfforts(holder)[BenchmarkDistance.FIVE_KILOMETERS])
        assertEquals(heldBy(runnerUp, 1500.0), fiveKRecord())
    }

    @Test
    fun `a details backfill on an Activity with no Best Efforts adds them and their Personal Records`() {
        val bare = fixtures.storeActivity("No details yet", "2026-04-10T08:00:00Z", details = null)
        fixtures.inTransaction { bestEffortRefresh.refresh(fixtures.reload(bare)) }
        assertEquals(emptyMap(), storedEfforts(bare))

        fixtures.inTransaction {
            val backfilled = steadyDetails(5000f, 1400)
            activityDetailsRepository.upsert(bare.id!!, backfilled, ParserVersion(1))
            bestEffortRefresh.refresh(fixtures.reload(bare), backfilled)
        }

        assertEquals(1400.0, storedEfforts(bare)[BenchmarkDistance.FIVE_KILOMETERS])
        assertEquals(heldBy(bare, 1400.0), fiveKRecord())
    }

    @Test
    fun `reclassifying the record holder away from running removes its Best Efforts and promotes the next-fastest run`() {
        val holder = fixtures.importActivity("Holder", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val runnerUp = fixtures.importActivity("Runner-up", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1500))

        fixtures.inTransaction { bestEffortRefresh.refresh(fixtures.reload(holder).apply { sport = ActivitySport.RIDE }) }

        assertEquals(emptyMap(), storedEfforts(holder))
        assertEquals(heldBy(runnerUp, 1500.0), fiveKRecord())
    }

    @Test
    fun `reclassifying the only run away from running clears its Personal Records`() {
        val only = fixtures.importActivity("Only run", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))

        fixtures.inTransaction { bestEffortRefresh.refresh(fixtures.reload(only).apply { sport = ActivitySport.WALK }) }

        assertEquals(emptyMap(), fixtures.runningRecords())
    }

    @Test
    fun `reclassifying an Activity as a run detects its Best Efforts from the stored details`() {
        val ride = fixtures.importActivity("Mislabelled", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1300), sport = ActivitySport.RIDE)
        assertEquals(emptyMap(), storedEfforts(ride))

        fixtures.inTransaction { bestEffortRefresh.refresh(fixtures.reload(ride).apply { sport = ActivitySport.RUN }) }

        assertEquals(1300.0, storedEfforts(ride)[BenchmarkDistance.FIVE_KILOMETERS])
        assertEquals(heldBy(ride, 1300.0), fiveKRecord())
    }

    @Test
    fun `reclassifying as a run an Activity whose stored details lack distance detects its Best Efforts from the stored route`() {
        // 0.005 deg of longitude at the equator is ~555.97 m: ~1111.95 m in 250 s, so 1 km takes ~224.8 s.
        val detailsWithoutDistance =
            FitTelemetry(
                t = intArrayOf(0, 125, 250),
                lons = doubleArrayOf(0.0, 0.005, 0.010),
                lats = doubleArrayOf(0.0, 0.0, 0.0),
                routeIndex = arrayOf(0, 1, 2),
                enhancedSpeed = arrayOf(4.4f, 4.4f, 4.4f),
                distance = arrayOf(null, null, null),
            )
        val ride = fixtures.storeActivity("No distance series", "2026-04-10T08:00:00Z", detailsWithoutDistance, sport = ActivitySport.RIDE)

        fixtures.inTransaction { bestEffortRefresh.refresh(fixtures.reload(ride).apply { sport = ActivitySport.RUN }) }

        val oneK = storedEfforts(ride)[BenchmarkDistance.ONE_KILOMETER]
        assertNotNull(oneK)
        assertEquals(224.8, oneK, 0.5)
        assertEquals(ride.id, fixtures.runningRecords()[BenchmarkDistance.ONE_KILOMETER]?.first)
    }

    @Test
    fun `deleting the record holder promotes the next-fastest run, and a batch delete of the rest clears the record`() {
        val holder = fixtures.importActivity("Holder", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val second = fixtures.importActivity("Second", "2026-04-11T08:00:00Z", steadyDetails(5000f, 1300))
        val third = fixtures.importActivity("Third", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1400))

        assertEquals(emptyList(), deleteAndRefresh(holder))
        assertEquals(heldBy(second, 1300.0), fiveKRecord())

        assertEquals(emptyList(), deleteAndRefresh(second, third))
        assertEquals(emptyMap(), fixtures.runningRecords())
    }

    @Test
    fun `a batch refresh of reclassified Activities keeps every Personal Record correct`() {
        val ride = fixtures.importActivity("Ride", "2026-04-09T08:00:00Z", steadyDetails(5000f, 1100), sport = ActivitySport.RIDE)
        val holder = fixtures.importActivity("Holder", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val runnerUp = fixtures.importActivity("Runner-up", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1500))

        fixtures.inTransaction {
            bestEffortRefresh.refresh(
                listOf(
                    fixtures.reload(ride).apply { sport = ActivitySport.RUN },
                    fixtures.reload(holder).apply { sport = ActivitySport.HIKE },
                ),
            )
        }

        assertEquals(heldBy(ride, 1100.0), fiveKRecord())
        assertEquals(emptyMap(), storedEfforts(holder))
        assertEquals(1500.0, storedEfforts(runnerUp)[BenchmarkDistance.FIVE_KILOMETERS])
    }

    @Test
    fun `an Activity in a sport without Benchmark Distances never gets Best Efforts or Personal Records`() {
        val ride = fixtures.importActivity("Long ride", "2026-04-10T08:00:00Z", steadyDetails(40000f, 4000), sport = ActivitySport.RIDE)

        assertEquals(emptyMap(), storedEfforts(ride))
        ActivitySport.entries.forEach { sport ->
            assertEquals(emptyList(), personalRecordRepository.findByUserIdAndSport(runner.id!!, sport))
        }
    }

    @Test
    fun `one athlete's changes never affect another athlete's Personal Records`() {
        val mine = fixtures.importActivity("Mine", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1500))
        val theirs = fixtures.importActivity("Theirs", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1000), owner = otherRunner)
        assertEquals(heldBy(mine, 1500.0), fiveKRecord())

        deleteAndRefresh(mine)

        assertEquals(emptyMap(), fixtures.runningRecords())
        assertEquals(heldBy(theirs, 1000.0), fiveKRecord(otherRunner))
    }

    @Test
    fun `refreshing twice gives the same Best Efforts and Personal Records`() {
        val holder = fixtures.importActivity("Holder", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        fixtures.importActivity("Runner-up", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1500))
        val effortsBefore = storedEfforts(holder)
        val recordsBefore = fixtures.runningRecords()

        val returned = fixtures.inTransaction { bestEffortRefresh.refresh(fixtures.reload(holder)) }

        assertEquals(effortsBefore, storedEfforts(holder))
        assertEquals(effortsBefore, returned.associate { it.benchmarkDistance to it.elapsedTimeSeconds })
        assertEquals(recordsBefore, fixtures.runningRecords())
    }

    @Test
    fun `a rolled-back import and refresh followed by a retry gives the same result as a clean import`() {
        val runnerUp = fixtures.importActivity("Runner-up", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1500))
        val details = steadyDetails(5000f, 1200)

        val rolledBack =
            transactionTemplate.execute { status ->
                val stored = fixtures.storeActivity("Retried", "2026-04-10T08:00:00Z", details)
                bestEffortRefresh.refresh(stored, details)
                status.setRollbackOnly()
                stored
            }!!
        assertEquals(false, activityRepository.existsById(rolledBack.id!!))
        assertEquals(heldBy(runnerUp, 1500.0), fiveKRecord())

        val retried = fixtures.importActivity("Retried", "2026-04-10T08:00:00Z", details)
        val clean = fixtures.importActivity("Clean", "2026-04-10T08:00:00Z", details, owner = otherRunner)

        assertEquals(storedEfforts(clean), storedEfforts(retried))
        assertEquals(
            fixtures.runningRecords(otherRunner).mapValues { (_, record) -> record.second },
            fixtures.runningRecords().mapValues { (_, record) -> record.second },
        )
        assertEquals(
            setOf(retried.id!!),
            fixtures
                .runningRecords()
                .values
                .map { it.first }
                .toSet(),
        )
    }

    @Test
    fun `a full recalibration breaks a tie between runners-up in favour of the earlier Activity`() {
        val holder = fixtures.importActivity("Holder", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        fixtures.importActivity("Later runner-up", "2026-04-15T08:00:00Z", steadyDetails(5000f, 1500))
        val earlier = fixtures.importActivity("Earlier runner-up", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1500))

        deleteAndRefresh(holder)

        assertEquals(heldBy(earlier, 1500.0), fiveKRecord())
    }

    @Test
    fun `a batch delete of the record holder and another run promotes the surviving run`() {
        val holder = fixtures.importActivity("Holder", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))
        val second = fixtures.importActivity("Second", "2026-04-11T08:00:00Z", steadyDetails(5000f, 1300))
        val survivor = fixtures.importActivity("Survivor", "2026-04-12T08:00:00Z", steadyDetails(5000f, 1400))

        deleteAndRefresh(holder, second)

        assertEquals(heldBy(survivor, 1400.0), fiveKRecord())
        assertEquals(
            setOf(survivor.id!!),
            fixtures
                .runningRecords()
                .values
                .map { it.first }
                .toSet(),
        )
    }

    @Test
    fun `refresh refuses to run without the caller's transaction`() {
        val run = fixtures.storeActivity("Run", "2026-04-10T08:00:00Z", steadyDetails(5000f, 1200))

        assertThrows<IllegalTransactionStateException> { bestEffortRefresh.refresh(run) }
        assertThrows<IllegalTransactionStateException> { bestEffortRefresh.refresh(listOf(run)) }
    }
}
