package com.jacobcraig.stridehearth.testutils

import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParsedActivity
import com.jacobcraig.stridehearth.system.ParsedActivityLap
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import java.time.Instant

/**
 * A [ParsedActivity] fixture stamped, unless told otherwise, with the FIT parser's current [ParserVersion],
 * as if the FIT parser had produced it. [ParsedActivity] itself has no default, so production parsers stamp
 * their version explicitly.
 */
fun parsedActivity(
    sport: ActivitySport = ActivitySport.RUN,
    title: String,
    startedAt: Instant,
    distance: Double,
    timeTakenSeconds: Int,
    averagePaceKmh: Double? = null,
    averageHeartRate: Int? = null,
    calories: Int? = null,
    externalId: String?,
    telemetry: FitTelemetry? = null,
    laps: List<ParsedActivityLap> = emptyList(),
    parserVersion: ParserVersion = FitActivityParser.PARSER_VERSION,
) = ParsedActivity(
    sport = sport,
    title = title,
    startedAt = startedAt,
    distance = distance,
    timeTakenSeconds = timeTakenSeconds,
    averagePaceKmh = averagePaceKmh,
    averageHeartRate = averageHeartRate,
    calories = calories,
    externalId = externalId,
    telemetry = telemetry,
    laps = laps,
    parserVersion = parserVersion,
)
