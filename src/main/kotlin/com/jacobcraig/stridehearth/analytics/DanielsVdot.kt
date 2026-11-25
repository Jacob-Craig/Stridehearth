package com.jacobcraig.stridehearth.analytics

import kotlin.math.exp

/**
 * Jack Daniels' and Jimmy Gilbert's running equations (Oxygen Power, 1979), which underlie Daniels' **VDOT** tables.
 * Pure: no Spring, no I/O.
 *
 * - [vo2Cost]: the oxygen cost, in ml/kg/min, of running at a speed in m/min.
 * - [fractionOfMax]: the share of VO2max that can be sustained for a race lasting that many minutes.
 * - [vdot]: the VDOT of a race, its oxygen cost over the share of VO2max its duration allows.
 * - [timeFor]: the inverse, the race time a VDOT predicts over a distance.
 */
object DanielsVdot {
    /** The oxygen cost, in ml/kg/min, of running at [metersPerMinute]: `-4.60 + 0.182258 v + 0.000104 v²`. */
    fun vo2Cost(metersPerMinute: Double): Double = -4.60 + 0.182258 * metersPerMinute + 0.000104 * metersPerMinute * metersPerMinute

    /** The share of VO2max sustainable for [minutes]: `0.8 + 0.1894393 e^(-0.012778 t) + 0.2989558 e^(-0.1932605 t)`. */
    fun fractionOfMax(minutes: Double): Double = 0.8 + 0.1894393 * exp(-0.012778 * minutes) + 0.2989558 * exp(-0.1932605 * minutes)

    /** The VDOT of running [meters] in [seconds]. */
    fun vdot(
        meters: Double,
        seconds: Double,
    ): Double {
        val minutes = seconds / 60
        return vo2Cost(meters / minutes) / fractionOfMax(minutes)
    }

    /** The time, in seconds and to within [TIME_TOLERANCE_SECONDS], in which [vdot] runs [meters]. */
    fun timeFor(
        vdot: Double,
        meters: Double,
    ): Double {
        // VDOT falls as time rises over every realistic race, so bisect.
        var fast = 1.0
        var slow = 7.0 * 24 * 3600
        while (slow - fast > TIME_TOLERANCE_SECONDS) {
            val mid = (fast + slow) / 2
            if (vdot(meters, mid) > vdot) fast = mid else slow = mid
        }
        return (fast + slow) / 2
    }

    /** How close [timeFor] solves. */
    const val TIME_TOLERANCE_SECONDS = 0.01
}
