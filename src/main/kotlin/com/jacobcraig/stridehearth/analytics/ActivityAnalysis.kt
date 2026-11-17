package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitTelemetry

/**
 * Everything derived from one **Activity**'s Activity Details in a single pure pass (ADR-0014): its **Cleaned
 * Activity Details** (ADR-0013), then the analytics computed from them. Later analytics extend this result, so
 * every derived value of an Activity comes from the same cleaning.
 *
 * [gapSpeed] is the **Grade-Adjusted Pace** stream, parallel to `cleaned.t`; `null` for a sport outside
 * [GradeAdjustedPace.GAP_SPORTS] or when no sample has a cleaned speed.
 *
 * No Spring and no I/O.
 */
class ActivityAnalysis private constructor(
    val cleaned: CleanedTelemetry,
    val gapSpeed: Array<Float?>?,
) {
    companion object {
        /** Cleans [details] for [sport], then computes every derived stream from the result. */
        fun analyse(
            details: FitTelemetry,
            sport: ActivitySport,
        ): ActivityAnalysis {
            val cleaned = TelemetryCleaner.clean(details, sport)
            val gapSpeed = if (sport in GradeAdjustedPace.GAP_SPORTS) GradeAdjustedPace.gapSpeed(cleaned) else null
            return ActivityAnalysis(cleaned, gapSpeed)
        }
    }
}
