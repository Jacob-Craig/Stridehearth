package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.BenchmarkDuration
import com.jacobcraig.stridehearth.analytics.CriticalSpeedFit
import com.jacobcraig.stridehearth.analytics.CriticalSpeedFitter
import com.jacobcraig.stridehearth.analytics.PeakEffortMetric
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One fitted point: the best Peak Effort at [benchmarkDuration] in the window, and the Activity holding it. */
data class CriticalSpeedPoint(
    val benchmarkDuration: BenchmarkDuration,
    val value: Double,
    val activityId: Long,
)

/** [metric]'s model for the window ending on [modelDate]: the [fit] and the [points] it was fitted from. */
data class CriticalSpeedModel(
    val metric: PeakEffortMetric,
    val modelDate: LocalDate,
    val fit: CriticalSpeedFit,
    val points: List<CriticalSpeedPoint>,
)

/** Picks a model's points from an athlete's Peak Efforts and fits them; pure. */
object CriticalSpeedWindow {
    /**
     * [metric]'s model for [date]: the best of [peaks] per fit duration among those on local days in
     * [date - ([CriticalSpeedFitter.WINDOW_DAYS] - 1), date], a tie going to the earlier Activity.
     */
    fun model(
        peaks: List<FitPeak>,
        metric: PeakEffortMetric,
        date: LocalDate,
    ): CriticalSpeedModel {
        val from = date.minusDays(CriticalSpeedFitter.WINDOW_DAYS - 1L)
        val best =
            peaks
                .filter { it.metric == metric && it.day >= from && it.day <= date }
                .groupBy { it.benchmarkDuration }
                .mapValues { (_, candidates) ->
                    candidates.minWith(
                        compareByDescending<FitPeak> { it.value }.thenBy { it.activityStartedAt }.thenBy { it.activityId },
                    )
                }
        val points =
            CriticalSpeedFitter.FIT_DURATIONS.mapNotNull { duration ->
                best[duration]?.let { CriticalSpeedPoint(duration, it.value, it.activityId) }
            }
        return CriticalSpeedModel(
            metric,
            date,
            CriticalSpeedFitter.fit(points.map { it.benchmarkDuration.seconds to it.value }),
            points,
        )
    }
}

/**
 * The **Critical Speed** rollup (#39): `athlete_critical_speed_model`, one model per metric for each athlete-local day
 * on which the athlete has a Run, fitted from their Run Peak Efforts of the [CriticalSpeedFitter.WINDOW_DAYS] days
 * up to it. A Grade-Adjusted speed model is stored for every such day; a power model only on days with a power Peak
 * Effort in the window. Days are athlete-local, in the **Athlete Time Zone**.
 *
 * A Peak Effort affects only the models of the 90 days from its own, so a change refits just those days. After a
 * refit of the athlete's latest model, a published model that differs from the Threshold in force on its day by
 * more than [ThresholdProposals.SAME_VALUE_TOLERANCE] (relative to the Threshold, as a speed for pace) becomes a
 * **Threshold Proposal**; one that agrees withdraws the pending one. It never records a Threshold itself.
 *
 * Its caller serialises it per athlete ([AthleteRollups], ADR-0016); it takes no lock of its own.
 */
@Component
class CriticalSpeedRollup(
    private val repository: CriticalSpeedModelRepository,
    private val athleteThresholds: AthleteThresholds,
    private val thresholdProposals: ThresholdProposals,
    private val jdbcClient: JdbcClient,
) {
    /**
     * Refits [userId]'s models on every day within [CriticalSpeedFitter.WINDOW_DAYS] days from the local day of each
     * of [changedAt], or every model when it is null, removing models of days that no longer have a Run.
     */
    fun refit(
        userId: Long,
        changedAt: Set<Instant>?,
    ) {
        val zone = zoneOf(userId)
        val ranges: List<Pair<LocalDate?, LocalDate?>> =
            if (changedAt == null) listOf(null to null) else affectedRanges(changedAt.map { it.atZone(zone).toLocalDate() })
        if (ranges.isEmpty()) return
        val refitted = mutableSetOf<LocalDate>()
        for ((from, to) in ranges) {
            val days = repository.findRunDays(userId, zone, from, to)
            val peaks = repository.findFitPeaks(userId, zone, from?.minusDays(CriticalSpeedFitter.WINDOW_DAYS - 1L), to)
            val models =
                days.flatMap { day ->
                    listOfNotNull(
                        CriticalSpeedWindow.model(peaks, PeakEffortMetric.GAP_SPEED, day),
                        CriticalSpeedWindow.model(peaks, PeakEffortMetric.POWER, day).takeIf { it.points.isNotEmpty() },
                    )
                }
            repository.replace(userId, from, to, models.map { StoredCriticalSpeedModel(it.metric, it.modelDate, it.fit) })
            refitted += days
        }
        proposeThresholds(userId, refitted)
    }

    /**
     * For each metric whose latest stored model was refitted ([refitted]) and is published, proposes its critical
     * value as the matching Threshold, unless the Threshold in force on the model's day already agrees with it.
     */
    private fun proposeThresholds(
        userId: Long,
        refitted: Set<LocalDate>,
    ) {
        if (refitted.isEmpty()) return
        val history by lazy { athleteThresholds.history(userId) }
        for ((metric, kind) in PROPOSED_KINDS) {
            val latest = repository.findLatest(userId, metric) ?: continue
            if (latest.modelDate !in refitted) continue
            val value = latest.fit.criticalValue ?: continue
            val inForce = history.valueOf(kind, latest.modelDate)
            if (inForce != null && ThresholdProposals.sameValue(value, inForce)) {
                thresholdProposals.withdraw(userId, kind, PROPOSAL_SOURCE)
            } else {
                thresholdProposals.propose(userId, kind, value, latest.modelDate, PROPOSAL_SOURCE)
            }
        }
    }

    private fun zoneOf(userId: Long): ZoneId =
        AthleteTimeZone.zoneOf(
            jdbcClient
                .sql("SELECT time_zone FROM app_user WHERE id = :u")
                .param("u", userId)
                .query { rs, _ -> rs.getString("time_zone") }
                .optional()
                .orElse(null),
        )

    companion object {
        /** The source a Critical Speed or Critical Power model's Threshold Proposals carry. */
        const val PROPOSAL_SOURCE = "CRITICAL_SPEED"

        private val PROPOSED_KINDS =
            listOf(
                PeakEffortMetric.GAP_SPEED to ThresholdKind.THRESHOLD_PACE,
                PeakEffortMetric.POWER to ThresholdKind.CRITICAL_POWER,
            )

        /**
         * The model days a change on each of [days] affects, `[day, day + WINDOW_DAYS - 1]`, merged where they overlap
         * or touch, ascending.
         */
        fun affectedRanges(days: Collection<LocalDate>): List<Pair<LocalDate, LocalDate>> {
            val merged = mutableListOf<Pair<LocalDate, LocalDate>>()
            for (day in days.distinct().sorted()) {
                val end = day.plusDays(CriticalSpeedFitter.WINDOW_DAYS - 1L)
                val last = merged.lastOrNull()
                if (last != null && day <= last.second.plusDays(1)) {
                    merged[merged.lastIndex] = last.first to maxOf(last.second, end)
                } else {
                    merged += day to end
                }
            }
            return merged
        }
    }
}
