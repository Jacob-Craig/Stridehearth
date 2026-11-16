package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.DetailsUpgrade
import com.jacobcraig.stridehearth.activities.history.LapsUpgrade
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParsedActivity
import com.jacobcraig.stridehearth.system.ParsedActivityLap
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.util.AopTestUtils
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Re-importing an Activity's file through the public import: the Import Outcome, and that Best Efforts,
 * Laps, Personal Records and history change only when the Activity Details module reports a change.
 * Upgrades are simulated with parsed fixtures stamped with a newer Parser Version.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ActivityReimportIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @MockitoSpyBean
    private lateinit var bestEffortRefresh: BestEffortRefresh

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityLapRepository: ActivityLapRepository

    @Autowired
    private lateinit var activityBestEffortRepository: ActivityBestEffortRepository

    @Autowired
    private lateinit var activityHistory: ActivityHistory

    @Autowired
    private lateinit var personalRecordRepository: PersonalRecordRepository

    /** The spy itself, behind the transactional proxy, so stubbing and verifying do not open a transaction. */
    private val refreshSpy: BestEffortRefresh get() = AopTestUtils.getUltimateTargetObject(bestEffortRefresh)

    private lateinit var runner: User

    private val current = FitActivityParser.PARSER_VERSION
    private val newer = ParserVersion(FitActivityParser.PARSER_VERSION.value + 1)
    private val older = ParserVersion(FitActivityParser.PARSER_VERSION.value - 1)

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "reimport@example.com", passwordHash = "x", username = "reimport_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun run(
        seconds: Int,
        version: ParserVersion,
        lapCount: Int = 0,
        withDetails: Boolean = true,
    ) = ParsedActivity(
        sport = ActivitySport.RUN,
        title = "Reimported Run",
        startedAt = Instant.parse("2026-04-01T08:00:00Z"),
        distance = 5.0,
        timeTakenSeconds = seconds,
        externalId = "reimport-run",
        telemetry =
            FitTelemetry(
                t = intArrayOf(0, seconds),
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOfNulls(2),
                distance = arrayOf(0.0f, 5000.0f),
            ).takeIf { withDetails },
        laps =
            (1..lapCount).map {
                ParsedActivityLap(
                    lapNumber = it,
                    startedAt = Instant.parse("2026-04-01T08:00:00Z"),
                    distance = 5.0 / lapCount,
                    timeTakenSeconds = seconds / lapCount,
                )
            },
        parserVersion = version,
    )

    private fun import(parsed: ParsedActivity): ImportOutcome = activityImportService.importParsed(listOf(parsed), runner.id!!)

    /** Everything a re-import may change for [activityId], with row ids so a rewrite is visible. */
    private fun storedState(activityId: Long): List<Any> =
        listOf(
            activityBestEffortRepository
                .findByActivityId(activityId)
                .map {
                    Triple(it.id, it.benchmarkDistance, it.elapsedTimeSeconds)
                }.sortedBy { it.first },
            activityLapRepository.findByActivityIdOrderByLapNumberAsc(activityId).map { Triple(it.id, it.lapNumber, it.timeTakenSeconds) },
            personalRecordRepository.findByUserIdAndSport(runner.id!!, ActivitySport.RUN),
            activityHistory.entriesFor(activityId).map { it.id to it.change },
        )

    private fun historyOf(activityId: Long): List<ActivityChange?> = activityHistory.entriesFor(activityId).map { it.change }

    private fun refreshesOf(activityId: Long) = argThat<Activity> { id == activityId }

    @Test
    fun `re-importing a file stamped with a newer Parser Version upgrades its details, Laps, Best Efforts and history`() {
        val activity = assertIs<ImportOutcome.Created>(import(run(1500, current, lapCount = 1))).created.single()
        val fiveKmBefore =
            activityBestEffortRepository.findByActivityId(activity.id!!).single {
                it.benchmarkDistance ==
                    BenchmarkDistance.FIVE_KILOMETERS
            }

        val outcome = assertIs<ImportOutcome.Upgraded>(import(run(1300, newer, lapCount = 2)))

        assertEquals(
            listOf(ActivityDetailsChange.Changed(DetailsChange.Upgraded(current, newer), LapsChange.Replaced(2))),
            outcome.upgrades.map { it.change },
        )
        assertEquals(
            listOf(
                ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = current.value, lapCount = 1),
                ActivityChange.Upgraded(DetailsUpgrade.ParserVersionUpgraded(current.value, newer.value), LapsUpgrade.Replaced(2)),
            ),
            historyOf(activity.id!!),
        )
        assertEquals(2, activityLapRepository.findByActivityIdOrderByLapNumberAsc(activity.id!!).size)
        val fiveKmAfter =
            activityBestEffortRepository.findByActivityId(activity.id!!).single {
                it.benchmarkDistance ==
                    BenchmarkDistance.FIVE_KILOMETERS
            }
        assertEquals(1500.0, fiveKmBefore.elapsedTimeSeconds, 1e-6)
        assertEquals(1300.0, fiveKmAfter.elapsedTimeSeconds, 1e-6)
    }

    @Test
    fun `a declined offer - a duplicate or an older Parser Version - changes no Best Efforts, Laps, Personal Records or history`() {
        val activity = assertIs<ImportOutcome.Created>(import(run(1500, current, lapCount = 1))).created.single()
        val before = storedState(activity.id!!)
        clearInvocations(refreshSpy)

        val duplicate = import(run(1200, current, lapCount = 3))
        val olderVersion = import(run(1100, older, lapCount = 3))

        assertEquals(
            ImportOutcome.Duplicate(listOf(activity)).duplicates.map {
                it.id
            },
            assertIs<ImportOutcome.Duplicate>(duplicate).duplicates.map { it.id },
        )
        assertEquals(listOf(activity.id), assertIs<ImportOutcome.Duplicate>(olderVersion).duplicates.map { it.id })
        assertEquals(before, storedState(activity.id!!))
        verify(refreshSpy, never()).refresh(any<Activity>(), anyOrNull())
    }

    @Test
    fun `two concurrent imports of the same newer-stamped file produce exactly one upgrade, one history entry and one refresh`() {
        val activity = assertIs<ImportOutcome.Created>(import(run(1500, current))).created.single()
        clearInvocations(refreshSpy)
        val firstHasUpgraded = CountDownLatch(1)
        val firstRefresh = AtomicBoolean(true)
        doAnswer { invocation ->
            val result = invocation.callRealMethod()
            if (firstRefresh.getAndSet(false)) {
                firstHasUpgraded.countDown()
                // Hold the first import's uncommitted upgrade so the second one runs while it is in flight.
                Thread.sleep(500)
            }
            result
        }.whenever(refreshSpy).refresh(refreshesOf(activity.id!!), anyOrNull())

        val pool = Executors.newFixedThreadPool(2)
        val outcomes =
            try {
                val first = pool.submit(Callable { import(run(1300, newer)) })
                val second =
                    pool.submit(
                        Callable {
                            firstHasUpgraded.await(10, TimeUnit.SECONDS)
                            import(run(1300, newer))
                        },
                    )
                listOf(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }

        assertEquals(1, outcomes.filterIsInstance<ImportOutcome.Upgraded>().size, "outcomes: $outcomes")
        assertEquals(1, outcomes.filterIsInstance<ImportOutcome.Duplicate>().size, "outcomes: $outcomes")
        assertEquals(1, historyOf(activity.id!!).count { it is ActivityChange.Upgraded })
        verify(refreshSpy, times(1)).refresh(refreshesOf(activity.id!!), anyOrNull())
    }

    @Test
    fun `re-importing with details an Activity first imported without them backfills them and refreshes its Best Efforts`() {
        val activity = assertIs<ImportOutcome.Created>(import(run(1500, current, withDetails = false))).created.single()
        assertEquals(emptyList(), activityBestEffortRepository.findByActivityId(activity.id!!))
        clearInvocations(refreshSpy)

        val outcome = assertIs<ImportOutcome.Upgraded>(import(run(1500, current)))

        assertEquals(
            listOf(ActivityDetailsChange.Changed(DetailsChange.Backfilled(current), laps = null)),
            outcome.upgrades.map { it.change },
        )
        assertEquals(
            listOf(
                ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = null, lapCount = 0),
                ActivityChange.Upgraded(DetailsUpgrade.Backfilled(current.value), laps = null),
            ),
            historyOf(activity.id!!),
        )
        verify(refreshSpy, times(1)).refresh(refreshesOf(activity.id!!), anyOrNull())
        val fiveKm =
            activityBestEffortRepository.findByActivityId(activity.id!!).single {
                it.benchmarkDistance == BenchmarkDistance.FIVE_KILOMETERS
            }
        assertEquals(1500.0, fiveKm.elapsedTimeSeconds, 1e-6)
    }

    @Test
    fun `re-importing the same Parser Version with Laps for an Activity that has none backfills only the Laps, without a refresh`() {
        val activity = assertIs<ImportOutcome.Created>(import(run(1500, current))).created.single()
        val bestEffortsBefore = storedState(activity.id!!).first()
        clearInvocations(refreshSpy)

        val outcome = assertIs<ImportOutcome.Upgraded>(import(run(1500, current, lapCount = 3)))

        assertEquals(
            listOf(ActivityDetailsChange.Changed(details = null, laps = LapsChange.Backfilled(3))),
            outcome.upgrades.map { it.change },
        )
        assertEquals(
            listOf(
                ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = current.value, lapCount = 0),
                ActivityChange.Upgraded(details = null, laps = LapsUpgrade.Backfilled(3)),
            ),
            historyOf(activity.id!!),
        )
        assertEquals(3, activityLapRepository.findByActivityIdOrderByLapNumberAsc(activity.id!!).size)
        assertEquals(bestEffortsBefore, storedState(activity.id!!).first())
        verify(refreshSpy, never()).refresh(any<Activity>(), anyOrNull())
    }
}
