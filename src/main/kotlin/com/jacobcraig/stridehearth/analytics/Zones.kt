package com.jacobcraig.stridehearth.analytics

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One heart-rate **Zone**: heart rates in `[lowerBpm, upperBpm)`. Z1 has no lower bound, and Z5 has no upper bound
 * unless MAX_HR is known.
 */
data class HeartRateZone(
    val zone: Int,
    val lowerBpm: Int?,
    val upperBpm: Int?,
    val seilerZone: Int,
)

/** One pace **Zone**, as speeds in `[lowerSpeedMps, upperSpeedMps)`. Z1 has no lower bound and Z5 no upper bound. */
data class PaceZone(
    val zone: Int,
    val lowerSpeedMps: Double?,
    val upperSpeedMps: Double?,
    val seilerZone: Int,
)

/**
 * Intensity **Zones** derived from an athlete's **Thresholds**, after Friel's five running zones, each also mapped
 * to Seiler's three (Z1-Z2 below the first threshold, Z3-Z4 between the thresholds, Z5 above the second). LTHR
 * approximates the second threshold, so Friel's Z5 is Seiler's zone 3.
 */
object Zones {
    /** Lower bounds of Friel's heart-rate Z2 to Z5, as fractions of LTHR. */
    private val HEART_RATE_FRACTIONS = listOf("0.85", "0.90", "0.95", "1.00").map(::BigDecimal)

    /**
     * Lower bounds of Friel's pace Z2 to Z5, as threshold pace (time per km) divided by pace: Z1 is slower than 129 %
     * of threshold pace, Z5 faster than 99 %. As speeds, the bounds are `vT / 1.29`, `vT / 1.14`, `vT / 1.06` and
     * `vT / 0.99`.
     */
    private val PACE_TIME_RATIOS = listOf(1.29, 1.14, 1.06, 0.99)

    /** Seiler's zone for each of Friel's five. */
    private val SEILER = listOf(1, 1, 2, 2, 3)

    /** Friel's five heart-rate zones for [lthr], with Z5 ending at [maxHr] when it is known. Bounds in whole bpm. */
    fun heartRate(
        lthr: Double,
        maxHr: Double?,
    ): List<HeartRateZone> {
        // Half-up on the exact decimal product, so 170 x 0.85 = 144.5 is 145 whatever its binary rounding.
        val lowers =
            HEART_RATE_FRACTIONS.map { fraction ->
                BigDecimal
                    .valueOf(lthr)
                    .multiply(fraction)
                    .setScale(0, RoundingMode.HALF_UP)
                    .toInt()
            }
        val bounds = listOf(null) + lowers + listOf(maxHr?.let { BigDecimal.valueOf(it).setScale(0, RoundingMode.HALF_UP).toInt() })
        return (0 until 5).map { i -> HeartRateZone(i + 1, bounds[i], bounds[i + 1], SEILER[i]) }
    }

    /** Friel's five pace zones for a threshold speed of [thresholdSpeedMps], as speed bounds in m/s. */
    fun pace(thresholdSpeedMps: Double): List<PaceZone> {
        val bounds = listOf(null) + PACE_TIME_RATIOS.map { thresholdSpeedMps / it } + listOf(null)
        return (0 until 5).map { i -> PaceZone(i + 1, bounds[i], bounds[i + 1], SEILER[i]) }
    }
}
