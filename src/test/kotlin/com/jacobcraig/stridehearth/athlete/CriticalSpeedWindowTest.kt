package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.CriticalSpeedStatus
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

/** Which Peak Efforts a day's **Critical Speed** model is fitted from, and which model days a change affects. */
class CriticalSpeedWindowTest {
    private val day = LocalDate.parse("2026-06-01")

    private fun peak(
        daysBefore: Long,
        duration: BenchmarkDuration,
        value: Double,
        activityId: Long = daysBefore + 1,
        metric: PeakEffortMetric = PeakEffortMetric.GAP_SPEED,
    ) = FitPeak(
        day = day.minusDays(daysBefore),
        metric = metric,
        benchmarkDuration = duration,
        value = value,
        activityId = activityId,
        activityStartedAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(activityId),
    )

    @Test
    fun `the window holds the day 89 days before and not the day 90 days before`() {
        val model =
            CriticalSpeedWindow.model(
                listOf(
                    peak(0, BenchmarkDuration.TWO_MINUTES, 5.0),
                    peak(89, BenchmarkDuration.FIVE_MINUTES, 4.5),
                    peak(90, BenchmarkDuration.TEN_MINUTES, 4.3),
                    // After the model's day: never counts.
                    peak(-1, BenchmarkDuration.TWENTY_MINUTES, 4.2),
                ),
                PeakEffortMetric.GAP_SPEED,
                day,
            )

        assertEquals(listOf(BenchmarkDuration.TWO_MINUTES, BenchmarkDuration.FIVE_MINUTES), model.points.map { it.benchmarkDuration })
        assertEquals(CriticalSpeedStatus.INSUFFICIENT_POINTS, model.fit.status)
    }

    @Test
    fun `each duration's best peak is fitted, a tie going to the earlier Activity, and only fit durations count`() {
        val model =
            CriticalSpeedWindow.model(
                listOf(
                    peak(10, BenchmarkDuration.TWO_MINUTES, 5.0, activityId = 7),
                    peak(5, BenchmarkDuration.TWO_MINUTES, 5.0, activityId = 3),
                    peak(5, BenchmarkDuration.TWO_MINUTES, 5.5, activityId = 9),
                    peak(5, BenchmarkDuration.THREE_MINUTES, 4.9, activityId = 7),
                    peak(10, BenchmarkDuration.THREE_MINUTES, 4.9, activityId = 4),
                    peak(5, BenchmarkDuration.TWO_MINUTES, 400.0, activityId = 1, metric = PeakEffortMetric.POWER),
                ),
                PeakEffortMetric.GAP_SPEED,
                day,
            )

        assertEquals(
            listOf(
                CriticalSpeedPoint(BenchmarkDuration.TWO_MINUTES, 5.5, 9),
                CriticalSpeedPoint(BenchmarkDuration.THREE_MINUTES, 4.9, 4),
            ),
            model.points,
        )
    }

    @Test
    fun `hyperbolic peaks across several Runs in the window give the model`() {
        val peaks = CRITICAL_SPEED_DURATIONS.mapIndexed { i, duration -> peak(i * 20L, duration, 4.0 + 200.0 / duration.seconds) }

        val model = CriticalSpeedWindow.model(peaks, PeakEffortMetric.GAP_SPEED, day)

        assertEquals(CriticalSpeedStatus.MODEL, model.fit.status)
        assertEquals(4.0, model.fit.criticalValue!!, 1e-9)
        assertEquals(200.0, model.fit.reserveCapacity!!, 1e-6)
        assertEquals(5, model.fit.pointCount)
    }

    @Test
    fun `a change affects the 90 model days from its own, and changes far apart refit both windows`() {
        assertEquals(
            listOf(
                LocalDate.parse("2015-03-01") to LocalDate.parse("2015-05-29"),
                LocalDate.parse("2020-06-01") to LocalDate.parse("2020-08-29"),
            ),
            CriticalSpeedRollup.affectedRanges(listOf(LocalDate.parse("2020-06-01"), LocalDate.parse("2015-03-01"))),
        )
    }

    @Test
    fun `overlapping or touching affected ranges merge`() {
        assertEquals(
            listOf(LocalDate.parse("2026-01-01") to LocalDate.parse("2026-05-29")),
            CriticalSpeedRollup.affectedRanges(
                listOf(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-01"), LocalDate.parse("2026-01-01")),
            ),
        )
        // 2026-01-01 + 89 = 2026-03-31; a change on 2026-04-01 starts the very next day.
        assertEquals(
            listOf(LocalDate.parse("2026-01-01") to LocalDate.parse("2026-06-29")),
            CriticalSpeedRollup.affectedRanges(listOf(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-04-01"))),
        )
    }

    private companion object {
        val CRITICAL_SPEED_DURATIONS =
            listOf(
                BenchmarkDuration.TWO_MINUTES,
                BenchmarkDuration.THREE_MINUTES,
                BenchmarkDuration.FIVE_MINUTES,
                BenchmarkDuration.TEN_MINUTES,
                BenchmarkDuration.TWENTY_MINUTES,
            )
    }
}
