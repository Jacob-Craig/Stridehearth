package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.CriticalSpeedFit
import com.jacobcraig.stridehearth.analytics.CriticalSpeedFitter
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** An athlete's Grade-Adjusted speed model and, when they have power Peak Efforts in the window, their power model. */
data class CriticalSpeedModels(
    val pace: CriticalSpeedModel,
    val power: CriticalSpeedModel?,
)

/**
 * Reads an athlete's **Critical Speed** and **Critical Power** models (#39). It never writes: the stored models are
 * the rollup's ([CriticalSpeedRollup]), and a model for a day without one is fitted live, the same way.
 */
@Service
class CriticalSpeedService(
    private val repository: CriticalSpeedModelRepository,
    private val athleteTimeZone: AthleteTimeZone,
) {
    /** [userId]'s models for the window ending on their local [date], fitted live with their points. */
    @Transactional(readOnly = true)
    fun live(
        userId: Long,
        date: LocalDate,
    ): CriticalSpeedModels {
        val peaks = windowPeaks(userId, date)
        return CriticalSpeedModels(
            pace = CriticalSpeedWindow.model(peaks, PeakEffortMetric.GAP_SPEED, date),
            power = CriticalSpeedWindow.model(peaks, PeakEffortMetric.POWER, date).takeIf { it.points.isNotEmpty() },
        )
    }

    /** [userId]'s [metric] model in effect on their local [date]: the stored one for that day, else a live fit. */
    @Transactional(readOnly = true)
    fun inEffect(
        userId: Long,
        metric: PeakEffortMetric,
        date: LocalDate,
    ): CriticalSpeedFit =
        repository.find(userId, metric, date)?.fit
            ?: CriticalSpeedWindow.model(windowPeaks(userId, date), metric, date).fit

    private fun windowPeaks(
        userId: Long,
        date: LocalDate,
    ): List<FitPeak> =
        repository.findFitPeaks(
            userId,
            athleteTimeZone.of(userId),
            from = date.minusDays(CriticalSpeedFitter.WINDOW_DAYS - 1L),
            to = date,
        )
}
