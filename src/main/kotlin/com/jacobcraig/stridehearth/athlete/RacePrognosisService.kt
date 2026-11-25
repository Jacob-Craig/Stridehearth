package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.analytics.FitnessDay
import com.jacobcraig.stridehearth.analytics.RaceConditions
import com.jacobcraig.stridehearth.analytics.RacePrediction
import com.jacobcraig.stridehearth.analytics.RacePrognosis
import org.springframework.stereotype.Service
import java.time.LocalDate

/** The **Race Prognosis** on one of an athlete's days, and the Effective VO2max and Marathon Shape it comes from. */
data class RacePrognosisDay(
    val day: LocalDate,
    val effectiveVo2max: Double,
    val marathonShapePct: Double,
    val predictions: List<RacePrediction>,
)

/** An athlete's **Race Prognosis**: [current] on their today (with Course Derating), and each day with one. */
data class AthleteRacePrognosis(
    val current: RacePrognosisDay?,
    val days: List<RacePrognosisDay>,
)

/**
 * Reads an athlete's **Race Prognosis** (#45). It is not stored: each day is the pure [RacePrognosis] of the Effective
 * VO2max and Marathon Shape [AthleteFitnessService] reads for it, continued to today, so it never needs a rebuild of
 * its own.
 */
@Service
class RacePrognosisService(
    private val fitnessService: AthleteFitnessService,
) {
    /**
     * [userId]'s prognosis on each day from [from] to [to] (bounded as [AthleteFitnessService.series]) that has an
     * Effective VO2max, and on [today] derated for race-day [conditions]; the days are never derated.
     */
    fun prognosis(
        userId: Long,
        from: LocalDate?,
        to: LocalDate?,
        today: LocalDate,
        conditions: RaceConditions,
    ): AthleteRacePrognosis {
        val series = fitnessService.series(userId, from, to, today)
        return AthleteRacePrognosis(
            current = series.current?.let { prognosisOn(it, conditions) },
            days = series.days.mapNotNull { prognosisOn(it, RaceConditions.NONE) },
        )
    }

    /** The prognosis on [day], or null when it has no Effective VO2max (and so no Marathon Shape). */
    private fun prognosisOn(
        day: FitnessDay,
        conditions: RaceConditions,
    ): RacePrognosisDay? {
        val vo2max = day.effectiveVo2max ?: return null
        val shape = day.marathonShape?.pct ?: return null
        return RacePrognosisDay(day.day, vo2max, shape, RacePrognosis.predictAll(vo2max, shape, conditions))
    }
}
