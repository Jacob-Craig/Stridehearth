package com.jacobcraig.stridehearth.analytics

/**
 * **Cleaned Activity Details**: the speed, distance, heart-rate and altitude series of one Activity after
 * **Telemetry Cleaning** (see [TelemetryCleaner]). Derived analytics read these, never the stored series.
 *
 * Every array is parallel to [t] and has exactly its length; a sample is `null` where the cleaned series has
 * no value (the sensor was absent, or an anomaly was removed and the gap was too long to interpolate).
 * They are computed on demand and never stored.
 *
 * - [speed]: metres per second.
 * - [distance]: cumulative metres, never decreasing when [hasGps].
 * - [heartRate]: beats per minute.
 * - [altitude]: metres, smoothed over distance when [hasGps].
 * - [hasGps]: whether the Activity Details have any GPS fix. Without one, speed, distance and altitude are
 *   the recorded series, untouched.
 */
data class CleanedTelemetry(
    val t: IntArray,
    val speed: Array<Float?>,
    val distance: Array<Float?>,
    val heartRate: Array<Short?>,
    val altitude: Array<Float?>,
    val hasGps: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CleanedTelemetry) return false
        return hasGps == other.hasGps &&
            t.contentEquals(other.t) &&
            speed.contentEquals(other.speed) &&
            distance.contentEquals(other.distance) &&
            heartRate.contentEquals(other.heartRate) &&
            altitude.contentEquals(other.altitude)
    }

    override fun hashCode(): Int {
        var result = t.contentHashCode()
        result = 31 * result + speed.contentHashCode()
        result = 31 * result + distance.contentHashCode()
        result = 31 * result + heartRate.contentHashCode()
        result = 31 * result + altitude.contentHashCode()
        result = 31 * result + hasGps.hashCode()
        return result
    }
}
