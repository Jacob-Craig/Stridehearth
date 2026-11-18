package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityPeakEffortRepository
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivityService
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.BatchMetadataChange
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.CriticalSpeedStatus
import com.jacobcraig.stridehearth.analytics.PeakEffort
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
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
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **Critical Speed** rollup (#39): models stored per athlete-local day with a Run, refitted before commit whenever
 * Peak Efforts change within their 90-day window, through the real import, edit and delete paths, and the **Threshold
 * Proposals** a refit makes. Proposal tests store hand-made Peak Efforts from `v = CS + D′ / t` so the fitted values
 * are known exactly; they are marked in the same transaction, as the derived-data refresh marks them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CriticalSpeedRollupIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityService: ActivityService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var peakEffortRepository: ActivityPeakEffortRepository

    @Autowired
    private lateinit var repository: CriticalSpeedModelRepository

    @Autowired
    private lateinit var rollup: CriticalSpeedRollup

    @Autowired
    private lateinit var athleteRollups: AthleteRollups

    @Autowired
    private lateinit var athleteThresholds: AthleteThresholds

    @Autowired
    private lateinit var thresholdProposals: ThresholdProposals

    @Autowired
    private lateinit var worker: DerivedDataRecomputeWorker

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var runner: User
    private lateinit var otherRunner: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "cs_runner@example.com", passwordHash = "x", username = "cs_runner"))
        otherRunner = userRepository.save(User(email = "cs_other@example.com", passwordHash = "x", username = "cs_other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private val origin = LocalDate.parse("2025-01-01")

    private fun day(offset: Long): LocalDate = origin.plusDays(offset)

    private fun at(offset: Long): Instant = day(offset).atTime(8, 0).toInstant(ZoneOffset.UTC)

    /**
     * 21 minutes on the flat: [fast] m/s for the first 2 minutes, then 4 m/s. Every window starts at the beginning,
     * so its peak over `t` seconds is `4 + 120 (fast - 4) / t`: Critical Speed about 4 m/s, D′ about 120 (fast - 4) m.
     */
    private fun stepRun(fast: Double): FitTelemetry {
        val n = 21 * 60 + 1
        val speed = DoubleArray(n) { if (it in 1..120) fast else 4.0 }
        val distance = DoubleArray(n)
        for (i in 1 until n) distance[i] = distance[i - 1] + speed[i]
        return FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + distance[it] / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { speed[it].toFloat() },
            distance = Array(n) { distance[it].toFloat() },
            altitude = Array(n) { 30.0f },
        )
    }

    private fun import(
        offset: Long,
        fast: Double = 5.5,
        owner: User = runner,
    ): Activity {
        val externalId = "cs-$offset-$fast"
        activityImportService.importParsed(
            listOf(
                parsedActivity(
                    title = "Critical Speed fixture",
                    startedAt = at(offset),
                    distance = 5.2,
                    timeTakenSeconds = 1260,
                    externalId = externalId,
                    telemetry = stepRun(fast),
                ),
            ),
            owner.id!!,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(owner.id!!, ActivitySource.FIT_UPLOAD, externalId)!!
    }

    /**
     * A Run on day [offset] holding Peak Efforts at the five fit durations from `CS + D′ / t` (and `CP + W′ / t` when
     * [power] is given), stored and marked in one transaction as the derived-data refresh does.
     */
    private fun storedRun(
        offset: Long,
        critical: Double = 4.0,
        reserve: Double = 200.0,
        power: Pair<Double, Double>? = null,
        owner: User = runner,
    ): Activity =
        transactionTemplate.execute {
            val saved =
                activityRepository.save(
                    Activity(
                        title = "Stored peaks",
                        sport = ActivitySport.RUN,
                        startedAt = at(offset),
                        distance = 5.0,
                        timeTakenSeconds = 1500,
                        user = owner,
                        source = ActivitySource.FIT_UPLOAD,
                    ),
                )
            val durations = BenchmarkDuration.entries.filter { it.seconds in 120..1200 }
            val peaks =
                durations.map { peakAt(PeakEffortMetric.GAP_SPEED, it, critical + reserve / it.seconds) } +
                    power?.let { (cp, wPrime) -> durations.map { peakAt(PeakEffortMetric.POWER, it, cp + wPrime / it.seconds) } }.orEmpty()
            peakEffortRepository.replace(saved.id!!, peaks)
            athleteRollups.markPeaksChanged(owner.id!!, saved.startedAt)
            saved
        }!!

    private fun peakAt(
        metric: PeakEffortMetric,
        duration: BenchmarkDuration,
        value: Double,
    ) = PeakEffort(metric, duration, value, 0, duration.seconds, 0.0, duration.seconds.toDouble())

    private fun models(
        owner: User = runner,
        metric: PeakEffortMetric = PeakEffortMetric.GAP_SPEED,
    ): List<StoredCriticalSpeedModel> = repository.findAll(owner.id!!).filter { it.metric == metric }

    private fun model(
        offset: Long,
        owner: User = runner,
        metric: PeakEffortMetric = PeakEffortMetric.GAP_SPEED,
    ): StoredCriticalSpeedModel = assertNotNull(repository.find(owner.id!!, metric, day(offset)), "a model on day $offset")

    /** Each stored model's day and the transaction that wrote it, which changes whenever the row is rewritten. */
    private fun writers(owner: User = runner): Map<LocalDate, String> =
        jdbcTemplate
            .query(
                "SELECT model_date, xmin::text AS writer FROM athlete_critical_speed_model WHERE user_id = ? AND metric = 'GAP_SPEED'",
                { rs, _ -> rs.getObject("model_date", LocalDate::class.java) to rs.getString("writer") },
                owner.id,
            ).toMap()

    /** What a from-scratch refit stores, rolled back so the stored rows stay as they were. */
    private fun rebuilt(owner: User = runner): List<StoredCriticalSpeedModel> =
        transactionTemplate.execute { status ->
            rollup.refit(owner.id!!, changedAt = null)
            repository.findAll(owner.id!!).also { status.setRollbackOnly() }
        }!!

    private fun assertMatchesRebuild(owner: User = runner) = assertEquals(rebuilt(owner), repository.findAll(owner.id!!))

    private fun pending(owner: User = runner) = thresholdProposals.pending(owner.id!!)

    @Test
    fun `an import stores a model for its day, recovering the Run's Critical Speed and D'`() {
        import(0, fast = 5.5)

        val stored = model(0)
        assertEquals(CriticalSpeedStatus.MODEL, stored.fit.status)
        assertEquals(5, stored.fit.pointCount)
        // The step gives CS 4 m/s and D' 120 x 1.5 = 180 m before cleaning.
        assertEquals(4.0, assertNotNull(stored.fit.criticalValue), 0.04)
        assertEquals(180.0, assertNotNull(stored.fit.reserveCapacity), 9.0)
        assertTrue(assertNotNull(stored.fit.rSquared) > 0.999)
        assertEquals(emptyList(), models(metric = PeakEffortMetric.POWER), "no power, no power model")
        assertMatchesRebuild()
    }

    @Test
    fun `power peaks store a Critical Power model alongside`() {
        storedRun(0, power = 300.0 to 20_000.0)

        val power = model(0, metric = PeakEffortMetric.POWER)
        assertEquals(CriticalSpeedStatus.MODEL, power.fit.status)
        assertEquals(300.0, assertNotNull(power.fit.criticalValue), 1e-6)
        assertEquals(20_000.0, assertNotNull(power.fit.reserveCapacity), 1e-6)
    }

    @Test
    fun `a day without enough points stores an explicit no-model row`() {
        transactionTemplate.execute {
            val saved =
                activityRepository.save(
                    Activity(
                        title = "Short",
                        sport = ActivitySport.RUN,
                        startedAt = at(0),
                        distance = 1.0,
                        timeTakenSeconds = 200,
                        user = runner,
                        source = ActivitySource.FIT_UPLOAD,
                    ),
                )
            peakEffortRepository.replace(
                saved.id!!,
                listOf(peakAt(PeakEffortMetric.GAP_SPEED, BenchmarkDuration.TWO_MINUTES, 5.0)),
            )
            athleteRollups.markPeaksChanged(runner.id!!, saved.startedAt)
        }

        val stored = model(0)
        assertEquals(CriticalSpeedStatus.INSUFFICIENT_POINTS, stored.fit.status)
        assertEquals(1, stored.fit.pointCount)
        assertNull(stored.fit.criticalValue)
        assertNull(stored.fit.reserveCapacity)
    }

    @Test
    fun `importing an older Run refits only the model days within 90 days of it`() {
        listOf(0L, 50L, 100L, 200L).forEach { import(it) }
        val before = writers()

        import(60, fast = 6.0)

        val after = writers()
        assertEquals(setOf(day(0), day(50), day(60), day(100), day(200)), after.keys)
        assertEquals(before[day(0)], after[day(0)], "day 0 is before the change")
        assertEquals(before[day(50)], after[day(50)], "day 50 is before the change")
        assertEquals(before[day(200)], after[day(200)], "day 200 is more than 89 days after it")
        assertNotEquals(before[day(100)], after[day(100)], "day 100 is within 89 days of it")
        assertTrue(model(100).fit.reserveCapacity!! > model(50).fit.reserveCapacity!!, "the faster Run raises D' on day 100")
        assertMatchesRebuild()
    }

    @Test
    fun `deleting the Run that set the peaks refits the models it was in`() {
        val fast = import(0, fast = 6.0)
        import(5, fast = 5.0)
        val withFast = assertNotNull(model(5).fit.reserveCapacity)

        activityService.deleteActivity(fast.id!!, runner.id!!)

        assertNull(repository.find(runner.id!!, PeakEffortMetric.GAP_SPEED, day(0)), "no Run is left on day 0")
        val withoutFast = assertNotNull(model(5).fit.reserveCapacity)
        assertEquals(240.0, withFast, 12.0)
        assertEquals(120.0, withoutFast, 6.0)
        assertMatchesRebuild()
    }

    @Test
    fun `one batch delete of Runs more than 90 days apart refits both windows`() {
        val first = import(0, fast = 6.0)
        import(10, fast = 5.0)
        val second = import(200, fast = 6.0)
        import(210, fast = 5.0)
        assertEquals(240.0, model(10).fit.reserveCapacity!!, 12.0)
        assertEquals(240.0, model(210).fit.reserveCapacity!!, 12.0)

        activityService.batchDeleteActivities(listOf(first.id!!, second.id!!), runner.id!!)

        assertEquals(setOf(day(10), day(210)), writers().keys)
        assertEquals(120.0, model(10).fit.reserveCapacity!!, 6.0)
        assertEquals(120.0, model(210).fit.reserveCapacity!!, 6.0)
        assertMatchesRebuild()
    }

    @Test
    fun `reclassifying a Run as a Walk removes its peaks from the models`() {
        val fast = import(0, fast = 6.0)
        import(5, fast = 5.0)

        activityService.batchUpdateActivities(listOf(fast.id!!), runner.id!!, BatchMetadataChange.of(null, ActivitySport.WALK)!!)

        assertEquals(setOf(day(5)), writers().keys)
        assertEquals(120.0, model(5).fit.reserveCapacity!!, 6.0)
        assertMatchesRebuild()
    }

    @Test
    fun `another athlete's peaks never reach a model`() {
        storedRun(0, critical = 5.0, owner = otherRunner)
        storedRun(1, critical = 4.0)

        assertEquals(4.0, model(1).fit.criticalValue!!, 1e-9)
        assertEquals(listOf(day(1)), models().map { it.modelDate })
    }

    @Test
    fun `the worker builds the models of athletes whose rollups predate them`() {
        import(0)
        jdbcTemplate.update("DELETE FROM athlete_critical_speed_model WHERE user_id = ?", runner.id)
        jdbcTemplate.update("UPDATE athlete_analytics_state SET rollup_version = 1 WHERE user_id = ?", runner.id)

        worker.drain(onlyUserIds = listOf(runner.id!!))

        assertEquals(CriticalSpeedStatus.MODEL, model(0).fit.status)
        assertEquals(4.0, model(0).fit.criticalValue!!, 0.04)
        assertEquals(
            ROLLUP_VERSION,
            jdbcTemplate.queryForObject("SELECT rollup_version FROM athlete_analytics_state WHERE user_id = ?", Int::class.java, runner.id),
        )
    }

    @Test
    fun `a model with no Threshold Pace proposes one, and records no Threshold`() {
        storedRun(0)

        val proposal = pending().single()
        assertEquals(ThresholdKind.THRESHOLD_PACE, proposal.kind)
        assertEquals(4.0, proposal.value, 1e-9)
        assertEquals(day(0), proposal.effectiveDate)
        assertEquals(CriticalSpeedRollup.PROPOSAL_SOURCE, proposal.source)
        assertEquals(emptyList(), athleteThresholds.entries(runner.id!!))
    }

    @Test
    fun `CS 3_1 percent from the Threshold Pace, either side, proposes it and leaves the Threshold unchanged`() {
        // |CS - vT| / vT = 0.031 with CS 4.0 m/s: vT = 4 / 1.031 (CS faster) or 4 / 0.969 (CS slower).
        listOf(4.0 / 1.031, 4.0 / 0.969).forEachIndexed { i, threshold ->
            val owner = if (i == 0) runner else otherRunner
            athleteThresholds.record(owner.id!!, ThresholdKind.THRESHOLD_PACE, day(-10), threshold)

            storedRun(0, owner = owner)

            assertEquals(4.0, pending(owner).single().value, 1e-9)
            assertEquals(listOf(threshold), athleteThresholds.entries(owner.id!!).map { it.value })
        }
    }

    @Test
    fun `CS 2_9 percent from the Threshold Pace, either side, proposes nothing`() {
        listOf(4.0 / 1.029, 4.0 / 0.971).forEachIndexed { i, threshold ->
            val owner = if (i == 0) runner else otherRunner
            athleteThresholds.record(owner.id!!, ThresholdKind.THRESHOLD_PACE, day(-10), threshold)

            storedRun(0, owner = owner)

            assertEquals(emptyList(), pending(owner))
        }
    }

    @Test
    fun `a later refit replaces the pending proposal rather than adding one`() {
        storedRun(0)
        storedRun(30, critical = 4.4)

        val proposal = pending().single()
        assertEquals(4.4, proposal.value, 1e-9)
        assertEquals(day(30), proposal.effectiveDate)
    }

    @Test
    fun `only a refit of the latest model proposes`() {
        storedRun(100, critical = 4.0)
        thresholdProposals.dismiss(runner.id!!, pending().single().id)

        // An older Run refits days 0 to 89 only; the latest model, day 100, is untouched.
        storedRun(0, critical = 5.0)

        assertEquals(emptyList(), pending())
    }

    @Test
    fun `accepting the proposal records the Threshold Pace`() {
        storedRun(0)

        val entry = thresholdProposals.accept(runner.id!!, pending().single().id)

        assertEquals(ThresholdKind.THRESHOLD_PACE, entry.kind)
        assertEquals(4.0, athleteThresholds.asOf(runner.id!!, day(0)).thresholdSpeedMps!!, 1e-9)
        assertEquals(emptyList(), pending())
    }

    @Test
    fun `a dismissed proposal is not offered again within 3 percent`() {
        storedRun(0)
        thresholdProposals.dismiss(runner.id!!, pending().single().id)

        storedRun(10, critical = 4.1)
        assertEquals(emptyList(), pending(), "4.1 is within 3 % of the dismissed 4.0")

        storedRun(20, critical = 4.4)
        assertEquals(4.4, pending().single().value, 1e-9)
    }

    @Test
    fun `a model that now agrees with the Threshold Pace withdraws the pending proposal`() {
        athleteThresholds.record(runner.id!!, ThresholdKind.THRESHOLD_PACE, day(-10), 4.4)
        storedRun(0, critical = 4.0)
        assertEquals(4.0, pending().single().value, 1e-9)

        storedRun(10, critical = 4.35)

        assertEquals(emptyList(), pending())
    }

    @Test
    fun `a Critical Power model proposes the Critical Power Threshold the same way`() {
        athleteThresholds.record(runner.id!!, ThresholdKind.THRESHOLD_PACE, day(-10), 4.0)
        athleteThresholds.record(runner.id!!, ThresholdKind.CRITICAL_POWER, day(-10), 300.0 / 1.031)

        storedRun(0, power = 300.0 to 20_000.0)

        val proposal = pending().single()
        assertEquals(ThresholdKind.CRITICAL_POWER, proposal.kind)
        assertEquals(300.0, proposal.value, 1e-6)
    }
}
