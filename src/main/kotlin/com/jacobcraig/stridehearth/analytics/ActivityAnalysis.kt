package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry

/**
 * Everything derived from one **Activity**'s Activity Details in a single pure pass (ADR-0014): its **Cleaned
 * Activity Details** (ADR-0013), then the analytics computed from them. Later analytics extend this result, so
 * every derived value of an Activity comes from the same cleaning.
 *
 * [gapSpeed] is the **Grade-Adjusted Pace** stream, parallel to `cleaned.t`; `null` for a sport outside
 * [GradeAdjustedPace.GAP_SPORTS] or when no sample has a cleaned speed. [runMetrics] (Moving Time, Normalized Graded
 * Pace, Efficiency Factor and Aerobic Decoupling) exist for a Run only, and so do [peakEfforts]: one per
 * **Benchmark Duration** the Run spans over its GAP stream, plus power peaks when any power sample was recorded.
 * GAP peaks read the cleaned stream, so a GPS spike cannot produce one; power is read as recorded, except that a
 * sample above [MAX_RUN_POWER_WATTS] or below zero is read as missing.
 *
 * No Spring and no I/O.
 */
class ActivityAnalysis private constructor(
    val cleaned: CleanedTelemetry,
    val gapSpeed: Array<Float?>?,
    val runMetrics: RunMetrics?,
    val peakEfforts: List<PeakEffort>,
) {
    companion object {
        /**
         * The highest power, in W, a Run's power peaks believe. Running power meters report well under this even
         * in an all-out sprint, so a sample above it (or below zero) is a glitch: it is read as missing, which
         * counts as zero and can only lower a window, never set a peak.
         */
        const val MAX_RUN_POWER_WATTS = 1500

        /** Cleans [details] for [sport], then computes every derived stream from the result. */
        fun analyse(
            details: FitTelemetry,
            sport: ActivitySport,
        ): ActivityAnalysis {
            val cleaned = TelemetryCleaner.clean(details, sport)
            val gapSpeed = if (sport in GradeAdjustedPace.GAP_SPORTS) GradeAdjustedPace.gapSpeed(cleaned) else null
            val runMetrics = if (sport == ActivitySport.RUN) RunMetrics.of(cleaned, gapSpeed) else null
            val peakEfforts = if (sport == ActivitySport.RUN) peakEfforts(details, cleaned, gapSpeed) else emptyList()
            return ActivityAnalysis(cleaned, gapSpeed, runMetrics, peakEfforts)
        }

        private fun peakEfforts(
            details: FitTelemetry,
            cleaned: CleanedTelemetry,
            gapSpeed: Array<Float?>?,
        ): List<PeakEffort> {
            val gapPeaks = gapSpeed?.let { PeakEffortDetector.detect(cleaned.t, it, PeakEffortMetric.GAP_SPEED) }.orEmpty()
            val powerPeaks =
                if (details.power.any { it != null }) {
                    val plausible = Array(details.power.size) { i -> details.power[i]?.takeIf { it in 0..MAX_RUN_POWER_WATTS } }
                    PeakEffortDetector.detect(details.t, plausible, PeakEffortMetric.POWER)
                } else {
                    emptyList()
                }
            return gapPeaks + powerPeaks
        }
    }
}
