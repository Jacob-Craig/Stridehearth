package com.jacobcraig.stridehearth.analytics

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A race distance **Race Prognosis** predicts. Its own enum, not a Benchmark Distance: Best Efforts and Personal
 * Records stop at the Half Marathon.
 */
@Schema(enumAsRef = true)
enum class RaceDistance(
    val meters: Double,
    val displayName: String,
) {
    FIVE_K(5000.0, "5K"),
    TEN_K(10000.0, "10K"),
    HALF_MARATHON(21097.5, "Half Marathon"),
    MARATHON(42195.0, "Marathon"),
}

/**
 * Race-day conditions for **Course Derating**: the Ambient Temperature in °C and the course's total climb in m (a
 * course assumed to finish at the altitude it starts at). Either may be absent, which costs nothing.
 */
data class RaceConditions(
    val temperatureC: Double? = null,
    val elevationGainM: Double? = null,
) {
    companion object {
        val NONE = RaceConditions()
    }
}

/**
 * A predicted finish time over [distance]: the [vdotSeconds] the Effective VO2max runs it in on Daniels' tables,
 * times the [enduranceFactor] Marathon Shape costs and the [courseFactor] of Course Derating.
 */
data class RacePrediction(
    val distance: RaceDistance,
    val vdotSeconds: Double,
    val enduranceFactor: Double,
    val courseFactor: Double,
) {
    val predictedSeconds: Double = vdotSeconds * enduranceFactor * courseFactor
}

/**
 * **Race Prognosis** (#45), pure: no Spring, no I/O.
 *
 * - The base time is [DanielsVdot.timeFor] the athlete's Effective VO2max.
 * - The endurance penalty multiplies it by 1 + [ENDURANCE_PENALTY_AT_MARATHON] · (d − 5 km) / (42.195 km − 5 km) ·
 *   (1 − Marathon Shape / 100): nothing for the 5K or at a Marathon Shape of 100%, and up to +20% for the marathon
 *   at 0%, growing linearly with distance in between.
 * - **Course Derating** multiplies it by the Heat Derating of the race-day temperature ([HeatAltitude.heatPaceFactor])
 *   and by 1 + [ELEVATION_FLAT_METERS_PER_METER] · climb / d. On gentle grades a metre of climb costs about 6 m of
 *   flat running and a metre of descent gives back about 2 m (Minetti), so a course that returns to its start costs
 *   about 4 m per metre climbed.
 */
object RacePrognosis {
    /** The endurance penalty for a marathon at a Marathon Shape of 0%. */
    const val ENDURANCE_PENALTY_AT_MARATHON = 0.20

    /** Flat metres one metre of climb costs on a course that returns to its start altitude. */
    const val ELEVATION_FLAT_METERS_PER_METER = 4.0

    private val PENALTY_FREE_METERS = RaceDistance.FIVE_K.meters
    private val PENALTY_SPAN_METERS = RaceDistance.MARATHON.meters - PENALTY_FREE_METERS

    /** The predicted time over [distance] at an Effective VO2max of [vo2max] and Marathon Shape of [marathonShapePct]. */
    fun predict(
        vo2max: Double,
        marathonShapePct: Double,
        distance: RaceDistance,
        conditions: RaceConditions = RaceConditions.NONE,
    ) = RacePrediction(
        distance = distance,
        vdotSeconds = DanielsVdot.timeFor(vo2max, distance.meters),
        enduranceFactor = enduranceFactor(marathonShapePct, distance),
        courseFactor = courseFactor(distance, conditions),
    )

    /** [predict] for every [RaceDistance], shortest first. */
    fun predictAll(
        vo2max: Double,
        marathonShapePct: Double,
        conditions: RaceConditions = RaceConditions.NONE,
    ): List<RacePrediction> = RaceDistance.entries.map { predict(vo2max, marathonShapePct, it, conditions) }

    /** How much longer than its VDOT time a Marathon Shape of [marathonShapePct] (clamped to 0-100) runs [distance]. */
    fun enduranceFactor(
        marathonShapePct: Double,
        distance: RaceDistance,
    ): Double {
        val shortfall = 1 - marathonShapePct.coerceIn(0.0, 100.0) / 100
        val reach = maxOf(0.0, distance.meters - PENALTY_FREE_METERS) / PENALTY_SPAN_METERS
        return 1 + ENDURANCE_PENALTY_AT_MARATHON * reach * shortfall
    }

    /** The **Course Derating** of [conditions] over [distance]; 1 without any. */
    fun courseFactor(
        distance: RaceDistance,
        conditions: RaceConditions,
    ): Double {
        val heat = conditions.temperatureC?.let(HeatAltitude::heatPaceFactor) ?: 1.0
        val climb = conditions.elevationGainM?.let { 1 + ELEVATION_FLAT_METERS_PER_METER * it / distance.meters } ?: 1.0
        return heat * climb
    }
}
