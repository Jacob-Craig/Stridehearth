package com.jacobcraig.stridehearth.analytics

/**
 * **Heat Derating** and the altitude loss of VO2max, for predictions such as Race Prognosis and VO2max to adjust by
 * (#43). Pure: no Spring, no I/O.
 *
 * - [heatPaceFactor]: how much slower the same effort runs in the heat, as a multiplier of time (and a divisor of
 *   speed): 1.5% per 5 °C of **Ambient Temperature** above [HEAT_NEUTRAL_C]. Temperature only, because the device
 *   records no humidity (ADR-0017).
 * - [altitudeVo2maxFactor]: the fraction of sea-level VO2max available at an altitude, losing 6.3% per 1000 m above
 *   [ALTITUDE_THRESHOLD_M] (Wehrlin & Hallén 2006), the altitude above which World Athletics flags marks as
 *   altitude-assisted.
 *
 * **Heat Acclimation** and **Altitude Acclimation** do not change either factor yet.
 */
object HeatAltitude {
    /** The Ambient Temperature, in °C, at and below which heat costs nothing. */
    const val HEAT_NEUTRAL_C = 20.0

    /** The time penalty per °C above [HEAT_NEUTRAL_C]: 1.5% per 5 °C. */
    const val HEAT_PENALTY_PER_C = 0.003

    /** The altitude, in m, at and below which VO2max is that of sea level. */
    const val ALTITUDE_THRESHOLD_M = 1000.0

    /** The fraction of VO2max lost per metre above [ALTITUDE_THRESHOLD_M]: 6.3% per 1000 m. */
    const val ALTITUDE_VO2MAX_LOSS_PER_M = 6.3e-5

    /** The time multiplier for running at an Ambient Temperature of [temperatureC]; 1 at or below 20 °C. */
    fun heatPaceFactor(temperatureC: Double): Double = 1 + HEAT_PENALTY_PER_C * maxOf(0.0, temperatureC - HEAT_NEUTRAL_C)

    /** The fraction of sea-level VO2max available at [altitudeM] metres; 1 at or below 1000 m. */
    fun altitudeVo2maxFactor(altitudeM: Double): Double = 1 - ALTITUDE_VO2MAX_LOSS_PER_M * maxOf(0.0, altitudeM - ALTITUDE_THRESHOLD_M)
}
