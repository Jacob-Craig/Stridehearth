package com.jacobcraig.stridehearth.analytics

/** Whether an athlete-local day had an Activity that counts as heat exposure, and one that counts as altitude exposure. */
data class DayExposure(
    val heat: Boolean,
    val altitude: Boolean,
)

/** **Heat Acclimation** and **Altitude Acclimation**, in percent, at the end of the day before a series starts. */
data class AcclimationSeed(
    val heatPct: Double,
    val altitudePct: Double,
) {
    companion object {
        val ZERO = AcclimationSeed(0.0, 0.0)
    }
}

/** One day of acclimation: each percentage at the end of the day, and whether the day was exposed. */
data class AcclimationDay(
    val heatPct: Double,
    val altitudePct: Double,
    val heatExposed: Boolean,
    val altitudeExposed: Boolean,
)

/**
 * **Heat Acclimation** and **Altitude Acclimation** over consecutive athlete-local days (#43). Pure: no Spring, no I/O.
 *
 * Each is linear and tracked on its own: an exposed day adds 100/[BUILD_DAYS] points and a day without exposure
 * takes away 100/[DECAY_DAYS], clamped to 0..100, so 14 exposed days in a row build it fully and 21 days without
 * exposure lose it all. The percentages are counted internally in whole steps of 100/42 (42 being the least common
 * multiple of 14 and 21), so a full build or decay lands exactly on 100 or 0.
 */
object AcclimationCalculator {
    /** The Ambient Temperature, in °C, an Activity must exceed to count as heat exposure. */
    const val HEAT_EXPOSURE_C = 22.0

    /** The Mean Altitude, in m, an Activity must exceed to count as altitude exposure. */
    const val ALTITUDE_EXPOSURE_M = 800.0

    /** The shortest Activity, in seconds of elapsed time, that counts as exposure. */
    const val MIN_EXPOSURE_SECONDS = 1200

    /** Exposed days to build acclimation from 0 to 100%. */
    const val BUILD_DAYS = 14

    /** Unexposed days to lose acclimation from 100% to 0. */
    const val DECAY_DAYS = 21

    private const val FULL_STEPS = 42
    private const val BUILD_STEPS = FULL_STEPS / BUILD_DAYS
    private const val DECAY_STEPS = FULL_STEPS / DECAY_DAYS

    /** The acclimation at the end of each day of [exposures], continuing from [seed]. */
    fun series(
        exposures: List<DayExposure>,
        seed: AcclimationSeed = AcclimationSeed.ZERO,
    ): List<AcclimationDay> {
        var heat = steps(seed.heatPct)
        var altitude = steps(seed.altitudePct)
        return exposures.map { day ->
            heat = next(heat, day.heat)
            altitude = next(altitude, day.altitude)
            AcclimationDay(percent(heat), percent(altitude), day.heat, day.altitude)
        }
    }

    private fun next(
        steps: Int,
        exposed: Boolean,
    ): Int = if (exposed) minOf(FULL_STEPS, steps + BUILD_STEPS) else maxOf(0, steps - DECAY_STEPS)

    private fun steps(pct: Double): Int = Math.round(pct * FULL_STEPS / 100).toInt().coerceIn(0, FULL_STEPS)

    private fun percent(steps: Int): Double = steps * 100.0 / FULL_STEPS
}
