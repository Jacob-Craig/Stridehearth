package com.jacobcraig.stridehearth.analytics

import com.jacobcraig.stridehearth.system.fit.FitTelemetry

/**
 * The heat and altitude an **Activity** was done in, from its own Activity Details (#43, ADR-0017). Every value is
 * null when the Activity Details cannot support it.
 *
 * - [deviceTemperatureC]: the median of the device's recorded `temperature` samples, in °C, once at least
 *   [MIN_SAMPLES] samples cover at least [MIN_TEMPERATURE_COVERAGE] of the Activity. Temperature is not cleaned:
 *   the median already resists a watch warming up at the start or a spell indoors.
 * - [ambientTemperatureC]: the **Ambient Temperature**, the device temperature less the **Wrist-Heat Correction**
 *   ([WRIST_HEAT_CORRECTION_C]). Where a device is worn is never recorded, so the correction always applies.
 * - [meanAltitudeM]: the **Mean Altitude**, the mean of the cleaned altitude samples once there are [MIN_SAMPLES].
 */
data class ActivityConditions(
    val deviceTemperatureC: Double?,
    val ambientTemperatureC: Double?,
    val meanAltitudeM: Double?,
) {
    companion object {
        /** The fewest samples a temperature or altitude is computed from. */
        const val MIN_SAMPLES = 60

        /** The smallest share of the Activity's samples the temperature samples must cover. */
        const val MIN_TEMPERATURE_COVERAGE = 0.5

        /** How much warmer than the air, in °C, a wrist-worn device reads: subtracted from every device temperature. */
        const val WRIST_HEAT_CORRECTION_C = 6.0

        /** The conditions of the Activity whose stored Activity Details are [details] and whose cleaned ones are [cleaned]. */
        fun of(
            details: FitTelemetry,
            cleaned: CleanedTelemetry,
        ): ActivityConditions {
            val device = deviceTemperature(details)
            return ActivityConditions(
                deviceTemperatureC = device,
                ambientTemperatureC = device?.let { it - WRIST_HEAT_CORRECTION_C },
                meanAltitudeM =
                    cleaned.altitude
                        .filterNotNull()
                        .takeIf { it.size >= MIN_SAMPLES }
                        ?.map { it.toDouble() }
                        ?.average(),
            )
        }

        private fun deviceTemperature(details: FitTelemetry): Double? {
            val samples =
                details.temperature
                    .filterNotNull()
                    .map { it.toDouble() }
                    .sorted()
            if (samples.size < MIN_SAMPLES || samples.size < MIN_TEMPERATURE_COVERAGE * details.t.size) return null
            val middle = samples.size / 2
            return if (samples.size % 2 == 1) samples[middle] else (samples[middle - 1] + samples[middle]) / 2
        }
    }
}
