package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.athlete.Vo2maxFixtures.importRun
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A Run dated after the athlete's today (a device clock ahead) is stored, but the rollup caps its rows at today. Once
 * that day arrives, [AthleteFitnessService] must count the Run in the days it continues past the stored rows (#64).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AthleteFitnessContinuationIntegrationTest {
    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    /** The rollup's today is fixed; the service is then asked about the day after, as if the clock had moved on. */
    @TestBean
    private lateinit var clock: Clock

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var athleteThresholds: AthleteThresholds

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var fitnessService: AthleteFitnessService

    private lateinit var runner: User

    private val today: LocalDate = LocalDate.ofInstant(NOW, ZoneOffset.UTC)
    private val tomorrow: LocalDate = today.plusDays(1)

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        runner = userRepository.save(User(email = "continuation@example.com", passwordHash = "x", username = "continuation"))
        athleteThresholds.record(runner.id!!, ThresholdKind.MAX_HR, LocalDate.parse("2026-01-01"), 190.0)
        athleteThresholds.record(runner.id!!, ThresholdKind.RESTING_HR, LocalDate.parse("2026-01-01"), 50.0)
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.delete(runner)
    }

    @Test
    fun `a Run dated tomorrow counts once tomorrow arrives`() {
        activityImportService.importRun(activityRepository, runner.id!!, today, Vo2maxFixtures.easy())
        val ahead = activityImportService.importRun(activityRepository, runner.id!!, tomorrow, Vo2maxFixtures.fast(), "ahead")

        val series = fitnessService.series(runner.id!!, null, null, tomorrow)

        // Two 40-minute Runs in the 30-day window: the time-weighted mean of their estimates.
        val expected = (Vo2maxFixtures.EASY_RAW + Vo2maxFixtures.FAST_RAW) / 2
        assertEquals(expected, assertNotNull(assertNotNull(series.current).effectiveVo2max), 0.05)
        assertEquals(listOf(today, tomorrow), series.days.map { it.day })
        assertEquals(ahead, series.runs.last().activityId)
    }

    @Test
    fun `an athlete whose only Run was dated tomorrow has an Effective VO2max once it arrives`() {
        activityImportService.importRun(activityRepository, runner.id!!, tomorrow, Vo2maxFixtures.fast())

        assertEquals(null, fitnessService.series(runner.id!!, null, null, today).current)

        val series = fitnessService.series(runner.id!!, null, null, tomorrow)
        assertEquals(Vo2maxFixtures.FAST_RAW, assertNotNull(assertNotNull(series.current).effectiveVo2max), 0.05)
        assertEquals(listOf(tomorrow), series.days.map { it.day })
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-03-30T12:00:00Z")

        @JvmStatic
        fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
