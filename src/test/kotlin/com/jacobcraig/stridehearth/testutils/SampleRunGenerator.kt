package com.jacobcraig.stridehearth.testutils

import com.garmin.fit.ActivityMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import java.io.File
import java.time.Instant
import com.garmin.fit.Activity as FitActivityType
import com.garmin.fit.File as FitFile

/**
 * Makes the synthetic sample run that the test suite imports as `runs/sample.fit`, and the `runs/corrupt_sample.fit`
 * cut from it (#126, ADR-0012).
 *
 * The run is fake, and says so: its FIT `file_id` names the "development" manufacturer and the product
 * [PRODUCT_NAME]. It is just over 10 km at a smoothly varying pace around the perimeter paths of Hyde Park,
 * London, a public park picked only because everyone knows it, with GPS, altitude, heart rate, power and calories.
 * It keeps the shape the tests rely on: one session, one valid Lap, and a trailing zero-distance Lap that the
 * parser filters out, just as some watches record one at the end of a session.
 *
 * Everything is computed from fixed inputs with [StrictMath], so it is the same on every JVM, byte for byte.
 * `./gradlew test -PregenerateSampleRun --tests '*SampleRunGeneratorTest'` rewrites the committed fixtures.
 */
object SampleRunGenerator {
    const val PRODUCT_NAME = "Stridehearth synthetic sample run"

    /** Start of the run: 14:00 London summer time, so the parser titles it "Afternoon Run". */
    val START: Instant = Instant.parse("2026-06-06T13:00:00Z")

    /** The run's local time is UTC+1 (British Summer Time). */
    private const val LOCAL_OFFSET_SECONDS = 3600L

    /** Long enough for every running Benchmark Distance up to 10 km to have a Best Effort. */
    private const val TARGET_DISTANCE_METRES = 10_200.0

    private const val CALORIES = 655

    /** Fraction of [sampleRun] kept by [corruptSampleRun]: it ends mid-record, with no closing CRC. */
    private const val CORRUPT_FRACTION = 0.75

    /** A box around Hyde Park that every point of the run stays inside. */
    val NEUTRAL_AREA = LatLonBox(south = 51.500, north = 51.515, west = -0.180, east = -0.150)

    /**
     * The loop, clockwise from Hyde Park Corner: up the Park Lane side, west along the Bayswater Road side,
     * south down West Carriage Drive over the Serpentine, and back east along Rotten Row.
     */
    private val LOOP: List<LatLon> =
        listOf(
            LatLon(51.50290, -0.15350),
            LatLon(51.50560, -0.15470),
            LatLon(51.50890, -0.15640),
            LatLon(51.51190, -0.15830),
            LatLon(51.51240, -0.15990),
            LatLon(51.51200, -0.16450),
            LatLon(51.51150, -0.16930),
            LatLon(51.51100, -0.17330),
            LatLon(51.50900, -0.17400),
            LatLon(51.50620, -0.17440),
            LatLon(51.50380, -0.17460),
            LatLon(51.50300, -0.17380),
            LatLon(51.50340, -0.16900),
            LatLon(51.50350, -0.16400),
            LatLon(51.50320, -0.15900),
        )

    data class LatLon(
        val lat: Double,
        val lon: Double,
    )

    data class LatLonBox(
        val south: Double,
        val north: Double,
        val west: Double,
        val east: Double,
    ) {
        fun contains(
            lat: Double,
            lon: Double,
        ): Boolean = lat in south..north && lon in west..east
    }

    /** The synthetic sample run, as the bytes of a FIT activity file. */
    fun sampleRun(): ByteArray {
        val samples = samples()
        val end = samples.last()
        val endTime = START.plusSeconds(end.elapsedSeconds.toLong())
        val duration = end.elapsedSeconds.toFloat()

        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)
        encoder.write(
            FileIdMesg().apply {
                type = FitFile.ACTIVITY
                manufacturer = Manufacturer.DEVELOPMENT
                product = 0
                productName = PRODUCT_NAME
                timeCreated = fitTime(endTime)
            },
        )
        encoder.write(timerEvent(START, EventType.START))
        for (sample in samples) {
            encoder.write(
                RecordMesg().apply {
                    timestamp = fitTime(START.plusSeconds(sample.elapsedSeconds.toLong()))
                    positionLat = semicircles(sample.position.lat)
                    positionLong = semicircles(sample.position.lon)
                    distance = sample.distanceMetres.toFloat()
                    speed = sample.speed.toFloat()
                    enhancedSpeed = sample.speed.toFloat()
                    enhancedAltitude = sample.altitude.toFloat()
                    heartRate = sample.heartRate.toShort()
                    power = sample.power
                },
            )
        }
        encoder.write(timerEvent(endTime, EventType.STOP_ALL))
        encoder.write(
            LapMesg().apply {
                event = Event.LAP
                eventType = EventType.STOP
                startTime = fitTime(START)
                timestamp = fitTime(endTime)
                totalElapsedTime = duration
                totalTimerTime = duration
                totalDistance = end.distanceMetres.toFloat()
                lapTrigger = LapTrigger.MANUAL
            },
        )
        // The degenerate Lap some watches write as the session ends: no distance, a fraction of a second long.
        encoder.write(
            LapMesg().apply {
                event = Event.LAP
                eventType = EventType.STOP
                startTime = fitTime(endTime)
                timestamp = fitTime(endTime)
                totalElapsedTime = 0.4f
                totalTimerTime = 0.4f
                totalDistance = 0f
                lapTrigger = LapTrigger.SESSION_END
            },
        )
        encoder.write(
            SessionMesg().apply {
                event = Event.SESSION
                eventType = EventType.STOP
                sport = Sport.RUNNING
                subSport = SubSport.GENERIC
                startTime = fitTime(START)
                timestamp = fitTime(endTime)
                totalElapsedTime = duration
                totalTimerTime = duration
                totalDistance = end.distanceMetres.toFloat()
                totalCalories = CALORIES
            },
        )
        encoder.write(
            ActivityMesg().apply {
                timestamp = fitTime(endTime)
                localTimestamp = fitTime(endTime).timestamp + LOCAL_OFFSET_SECONDS
                totalTimerTime = duration
                numSessions = 1
                type = FitActivityType.MANUAL
                event = Event.ACTIVITY
                eventType = EventType.STOP
            },
        )
        return encoder.close()
    }

    /** [sampleRun] cut off part-way through its records, so it fails the FIT integrity check and needs repair. */
    fun corruptSampleRun(): ByteArray {
        val sample = sampleRun()
        return sample.copyOf((sample.size * CORRUPT_FRACTION).toInt())
    }

    /** Writes `sample.fit` and `corrupt_sample.fit` into [dir]. */
    fun writeFixtures(dir: File) {
        dir.mkdirs()
        File(dir, "sample.fit").writeBytes(sampleRun())
        File(dir, "corrupt_sample.fit").writeBytes(corruptSampleRun())
    }

    /** Converts FIT semicircles to degrees. */
    fun degrees(semicircles: Int): Double = semicircles * (180.0 / 2147483648.0)

    private fun semicircles(degrees: Double): Int = StrictMath.round(degrees * (2147483648.0 / 180.0)).toInt()

    private fun fitTime(instant: Instant) = DateTime(instant)

    private fun timerEvent(
        at: Instant,
        type: EventType,
    ) = EventMesg().apply {
        timestamp = fitTime(at)
        event = Event.TIMER
        eventType = type
        data = 0L
    }

    private class Sample(
        val elapsedSeconds: Int,
        val distanceMetres: Double,
        val position: LatLon,
        val speed: Double,
        val altitude: Double,
        val heartRate: Int,
        val power: Int,
    )

    /** One sample a second from the start until the run has covered [TARGET_DISTANCE_METRES]. */
    private fun samples(): List<Sample> {
        val loop = Loop(LOOP)
        val samples = mutableListOf<Sample>()
        var elapsed = 0
        var distance = 0.0
        while (true) {
            val t = elapsed.toDouble()
            // About 5:40 per km, drifting a little faster and slower every ten minutes.
            val speed = 2.95 + 0.15 * wave(t, period = 600.0)
            // Settles from 108 bpm to about 154 over the first ten minutes, then wanders a few beats.
            val heartRate = 108.0 + 46.0 * (1 - StrictMath.exp(-t / 300.0)) + 4.0 * wave(t, period = 900.0)
            val power = 255.0 + 18.0 * wave(t, period = 600.0) + 6.0 * wave(t, period = 47.0)
            // Hyde Park is gently sloped: highest on the Bayswater side, lowest by the Serpentine.
            val altitude = 30.0 + 6.0 * wave(loop.fraction(distance), period = 1.0)
            samples +=
                Sample(
                    elapsedSeconds = elapsed,
                    distanceMetres = distance,
                    position = loop.pointAt(distance),
                    speed = speed,
                    altitude = altitude,
                    heartRate = StrictMath.round(heartRate).toInt(),
                    power = StrictMath.round(power).toInt(),
                )
            if (distance >= TARGET_DISTANCE_METRES) return samples
            distance += speed
            elapsed++
        }
    }

    /** A sine wave of amplitude 1 over [period]. */
    private fun wave(
        x: Double,
        period: Double,
    ): Double = StrictMath.sin(2 * StrictMath.PI * x / period)

    /** A closed loop of waypoints, walked by distance and wrapped round as many times as needed. */
    private class Loop(
        waypoints: List<LatLon>,
    ) {
        private val points = waypoints + waypoints.first()
        private val legLengths = points.zipWithNext { a, b -> metresBetween(a, b) }
        private val length = legLengths.sum()

        fun fraction(distance: Double): Double = (distance % length) / length

        fun pointAt(distance: Double): LatLon {
            var remaining = distance % length
            for ((i, leg) in legLengths.withIndex()) {
                if (remaining <= leg) {
                    val f = remaining / leg
                    val a = points[i]
                    val b = points[i + 1]
                    return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                }
                remaining -= leg
            }
            return points.last()
        }

        private fun metresBetween(
            a: LatLon,
            b: LatLon,
        ): Double {
            val earthRadius = 6_371_000.0
            val dLat = StrictMath.toRadians(b.lat - a.lat)
            val dLon = StrictMath.toRadians(b.lon - a.lon) * StrictMath.cos(StrictMath.toRadians((a.lat + b.lat) / 2))
            return earthRadius * StrictMath.sqrt(dLat * dLat + dLon * dLon)
        }
    }
}
