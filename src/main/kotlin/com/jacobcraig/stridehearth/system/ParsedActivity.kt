package com.jacobcraig.stridehearth.system

import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.users.User
import java.time.Instant

/**
 * One activity read from an imported file, in the app's own units, independent of the file format.
 * It is not yet saved to the database.
 *
 * Parsers (e.g. [com.jacobcraig.stridehearth.system.fit.FitActivityParser]) produce these, and
 * [com.jacobcraig.stridehearth.activities.ActivityImportService] turns them into [Activity] entities with [toActivity].
 * For FIT files, each one comes from a single `session` message.
 *
 * A `data class` is a class that exists to hold values. Kotlin automatically generates `equals`,
 * `hashCode`, `toString` and `copy` for it. All fields are `val` (read-only), so an instance never
 * changes after it is created.
 *
 * @property sport the activity sport (e.g. [ActivitySport.RUN]).
 * @property title display name (e.g. "Morning Run").
 * @property startedAt the moment the activity started (UTC).
 * @property distance distance covered, in **kilometres**.
 * @property timeTakenSeconds duration in seconds. For FIT files this is timer time, which excludes pauses.
 * @property averagePaceKmh average **speed** in km/h (despite the name, not minutes per km). `null` if unknown.
 * @property averageHeartRate average heart rate in beats per minute. `null` if no heart-rate sensor was used.
 * @property calories energy burned in kcal. `null` if unknown.
 * @property externalId stable ID from the source file, used to skip duplicate imports. For FIT files
 *   it is `"<device>-<fileCreated>-<sessionStart>"`. `null` turns off duplicate detection.
 * @property telemetry decoded time-series data and route coordinates, or `null` if the activity file had no records.
 * @property laps list of device-recorded lap intervals for this activity.
 * @property parserVersion the [ParserVersion] that decoded [telemetry] and [laps]. Each parser stamps its own
 *   version explicitly; test fixtures may stamp any version.
 */
data class ParsedActivity(
    val sport: ActivitySport = ActivitySport.RUN,
    val title: String,
    val startedAt: Instant,
    val distance: Double,
    val timeTakenSeconds: Int,
    val averagePaceKmh: Double? = null,
    val averageHeartRate: Int? = null,
    val calories: Int? = null,
    val externalId: String?, // e.g. FIT file_id composite
    val telemetry: FitTelemetry? = null,
    val laps: List<ParsedActivityLap> = emptyList(),
    val parserVersion: ParserVersion,
) {
    /**
     * Creates a new, unsaved [Activity] database entity from this activity.
     *
     * @param owner the user the activity belongs to.
     * @param source where the activity came from (e.g. [ActivitySource.FIT_UPLOAD]).
     * @return an [Activity] with no `id` yet. The database assigns one when it is saved.
     */
    fun toActivity(
        owner: User,
        source: ActivitySource,
    ): Activity =
        Activity(
            user = owner,
            source = source,
            sport = sport,
            title = title,
            startedAt = startedAt,
            distance = distance,
            timeTakenSeconds = timeTakenSeconds,
            averagePaceKmh = averagePaceKmh,
            averageHeartRate = averageHeartRate,
            calories = calories,
            externalId = externalId,
        )
}

/**
 * One discrete device-recorded lap parsed from an activity file.
 *
 * @property lapNumber sequential 1-based index of the lap within the activity.
 * @property startedAt the moment the lap started (UTC).
 * @property distance distance covered in the lap, in kilometres.
 * @property timeTakenSeconds duration in seconds (timer duration excluding pauses).
 * @property averagePaceKmh average speed in km/h. `null` if unknown or non-positive.
 * @property averageHeartRate average heart rate in beats per minute during this lap.
 * @property elevationGainMeters positive elevation gain in metres.
 */
data class ParsedActivityLap(
    val lapNumber: Int,
    val startedAt: Instant,
    val distance: Double,
    val timeTakenSeconds: Int,
    val averagePaceKmh: Double? = null,
    val averageHeartRate: Int? = null,
    val elevationGainMeters: Double? = null,
)
