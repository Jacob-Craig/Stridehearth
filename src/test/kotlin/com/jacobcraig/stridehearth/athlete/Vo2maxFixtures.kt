package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Flat 1 Hz runs for the **VO2max Estimate** with Resting HR 50 and Max HR 190 (a 140 bpm reserve), whose estimates
 * are worked out by hand: `raw = 3.5 + (VO2(60 v) - 3.5) / %HRR` with `VO2(v) = -4.60 + 0.182258 v + 0.000104 v²`.
 */
object Vo2maxFixtures {
    /** 40 min at 4.0 m/s and 162 bpm: %HRR 0.8, VO2(240) = 45.132, so 55.540. */
    const val FAST_RAW = 55.5404

    /** 40 min at 3.5 m/s and 148 bpm: %HRR 0.7, VO2(210) = 38.261, so 53.158. */
    const val EASY_RAW = 53.1580

    /** [seconds] of 1 Hz Activity Details at [speed] m/s and [hr] bpm on a flat course at 30 m with GPS. */
    fun telemetry(
        seconds: Int,
        speed: Float,
        hr: Short,
    ): FitTelemetry {
        val n = seconds + 1
        return FitTelemetry(
            t = IntArray(n) { it },
            lons = DoubleArray(n) { -1.5 },
            lats = DoubleArray(n) { 53.0 + it * speed / 111_195.0 },
            routeIndex = Array(n) { it },
            speed = Array(n) { speed },
            distance = Array(n) { it * speed },
            altitude = Array(n) { 30.0f },
            heartRate = Array(n) { hr },
        )
    }

    /** Imports a run started at 08:00 UTC on [day] and returns its id. */
    fun ActivityImportService.importRun(
        activityRepository: ActivityRepository,
        ownerId: Long,
        day: LocalDate,
        details: FitTelemetry,
        tag: String = "",
    ): Long {
        val externalId = "vo2max-$day-$tag"
        val seconds = details.t.last()
        importParsed(
            listOf(
                parsedActivity(
                    title = "VO2max fixture",
                    startedAt = day.atTime(8, 0).toInstant(ZoneOffset.UTC),
                    distance = (details.distance.last() ?: 0f) / 1000.0,
                    timeTakenSeconds = seconds,
                    externalId = externalId,
                    telemetry = details,
                ),
            ),
            ownerId,
        )
        return activityRepository.findByUserIdAndSourceAndExternalId(ownerId, ActivitySource.FIT_UPLOAD, externalId)!!.id!!
    }

    fun fast() = telemetry(2400, 4.0f, 162)

    fun easy() = telemetry(2400, 3.5f, 148)

    /** A 5.2 km race at 5.0 m/s: too short for an estimate of its own, but a 5K Best Effort of 1000 s (VDOT 61.59). */
    fun race() = telemetry(1040, 5.0f, 185)

    const val RACE_VDOT = 61.591
}
