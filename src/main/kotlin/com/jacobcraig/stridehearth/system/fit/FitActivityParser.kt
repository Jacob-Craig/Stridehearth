package com.jacobcraig.stridehearth.system.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.ActivityMesgListener
import com.garmin.fit.DateTime
import com.garmin.fit.Decode
import com.garmin.fit.FileIdMesg
import com.garmin.fit.FileIdMesgListener
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.LapMesg
import com.garmin.fit.LapMesgListener
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.MesgDefinitionListener
import com.garmin.fit.MesgListener
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import com.garmin.fit.Sport
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.InvalidActivityFileException
import com.jacobcraig.stridehearth.system.ParsedActivity
import com.jacobcraig.stridehearth.system.ParsedActivityLap
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.UnsupportedActivityFileTypeException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.InputStreamSource
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import com.garmin.fit.File as FitFile

/**
 * Deep parser converting raw Garmin FIT activity files into domain [ParsedActivity] objects.
 *
 * Responsibilities:
 * - Checks file integrity and decodes messages via Garmin SDK's [Decode] and [MesgBroadcaster].
 * - Enforces file type constraint: only [FitFile.ACTIVITY] files are accepted; other FIT types are rejected immediately.
 * - Single-retry repair: if integrity check or decoding fails due to corruption, attempts a single repair via [repairer].
 * - Slices [RecordMesg] timeline once per session, supplying sliced records to both [FitTelemetryMapper] and fallback HR calculation.
 * - Internalizes title generation, sport mapping, local time offset, and duplicate-detecting external IDs.
 */
@Component
class FitActivityParser
    @Autowired
    constructor(
        private val repairer: FitFileRepairer,
    ) {
        /** Secondary constructor for testing or when repair is disabled. */
        constructor() : this(NoOpFitFileRepairer)

        companion object {
            /**
             * The [ParserVersion] of this parser's decoding logic, stamped on every [ParsedActivity] it produces.
             * Bump it whenever decoding the same file would produce different Activity Details or Laps.
             */
            val PARSER_VERSION = ParserVersion(1)

            private const val MAX_OFFSET_SECONDS = 18 * 60 * 60
        }

        /**
         * Parses a FIT activity file, streaming it (twice: integrity check, then decode) rather than
         * loading it into memory, as ADR-0001 requires. Only a file that needs repair is read into memory.
         *
         * @param file a re-openable source of the FIT bytes, such as an uploaded file or a staged file on disk.
         * @return list of parsed activities (one per valid session in the file).
         * @throws UnsupportedActivityFileTypeException if the file is a valid FIT file but not an ACTIVITY.
         * @throws InvalidActivityFileException if the file cannot be decoded/repaired or contains no valid sessions.
         */
        fun parse(file: InputStreamSource): List<ParsedActivity> {
            try {
                file.inputStream.use { checkIntegrity(it) }
                return file.inputStream.use { decodeStream(it) }
            } catch (e: UnsupportedActivityFileTypeException) {
                throw e
            } catch (original: InvalidActivityFileException) {
                return repairAndRetry(file.inputStream.use { it.readAllBytes() }, original)
            }
        }

        private fun repairAndRetry(
            bytes: ByteArray,
            original: InvalidActivityFileException,
        ): List<ParsedActivity> {
            val repaired = repairer.repair(bytes) ?: throw original
            try {
                return decodeBytes(repaired)
            } catch (retry: InvalidActivityFileException) {
                retry.addSuppressed(original)
                throw retry
            }
        }

        private fun decodeBytes(bytes: ByteArray): List<ParsedActivity> {
            ByteArrayInputStream(bytes).use { checkIntegrity(it) }
            return ByteArrayInputStream(bytes).use { decodeStream(it) }
        }

        private fun checkIntegrity(stream: InputStream) {
            val ok =
                try {
                    Decode().checkFileIntegrity(stream)
                } catch (e: FitRuntimeException) {
                    throw InvalidActivityFileException("Could not decode FIT file: ${e.message}", e)
                }
            if (!ok) {
                throw InvalidActivityFileException("FIT file failed integrity check")
            }
        }

        private fun decodeStream(stream: InputStream): List<ParsedActivity> {
            val decode = Decode()
            val broadcaster = MesgBroadcaster()

            var fileId: FileIdMesg? = null
            val sessions = mutableListOf<SessionMesg>()
            var activityMesg: ActivityMesg? = null
            val records = mutableListOf<RecordMesg>()
            val laps = mutableListOf<LapMesg>()

            broadcaster.addListener(
                FileIdMesgListener { mesg ->
                    if (fileId == null) fileId = mesg
                },
            )
            broadcaster.addListener(
                ActivityMesgListener { mesg ->
                    if (activityMesg == null) activityMesg = mesg
                },
            )
            broadcaster.addListener(
                SessionMesgListener { mesg ->
                    sessions.add(mesg)
                },
            )
            broadcaster.addListener(
                RecordMesgListener { mesg ->
                    records.add(mesg)
                },
            )
            broadcaster.addListener(
                LapMesgListener { mesg ->
                    laps.add(mesg)
                },
            )

            decode.addListener(broadcaster as MesgListener)
            decode.addListener(broadcaster as MesgDefinitionListener)

            try {
                decode.read(stream)
            } catch (e: FitRuntimeException) {
                throw InvalidActivityFileException("Could not decode FIT file: ${e.message}", e)
            }

            val fileIdMesg = fileId ?: throw InvalidActivityFileException("FIT file has no file_id message")
            if (fileIdMesg.type != FitFile.ACTIVITY) {
                throw UnsupportedActivityFileTypeException("FIT file type is ${fileIdMesg.type}, expected ACTIVITY")
            }

            val zone = localOffset(activityMesg)
            val activities =
                sessions
                    .mapNotNull { session ->
                        toParsedActivity(session, fileIdMesg, zone, records, laps)
                    }.ifEmpty {
                        throw InvalidActivityFileException("FIT file contains no valid sessions")
                    }
            records.clear()
            laps.clear()
            return activities
        }

        private fun toParsedActivity(
            session: SessionMesg,
            fileId: FileIdMesg,
            zone: ZoneOffset,
            records: List<RecordMesg>,
            laps: List<LapMesg>,
        ): ParsedActivity? {
            val start = session.startTime ?: return null
            val distanceMetres = session.totalDistance ?: return null
            val durationSeconds = session.totalTimerTime ?: session.totalElapsedTime ?: return null

            val from = start.timestamp
            val to =
                session.totalElapsedTime?.let { from + it.toLong() }
                    ?: session.timestamp?.timestamp
            val sessionRecords =
                records.filter { record ->
                    val t = record.timestamp?.timestamp
                    t == null || (t >= from && (to == null || t <= to))
                }

            val candidateLaps = correlateLaps(session, laps)
            val parsedLaps =
                candidateLaps
                    .filter(::isLapValid)
                    .mapIndexed { index, lap ->
                        parseLap(index + 1, lap, sessionRecords)
                    }

            val startedAt = start.instant
            val sport = toSport(session.sport)
            return ParsedActivity(
                sport = sport,
                title = generateTitle(sport, startedAt.atOffset(zone).toLocalTime()),
                startedAt = startedAt,
                distance = distanceMetres / 1000.0,
                timeTakenSeconds = durationSeconds.roundToInt(),
                averagePaceKmh = averageSpeedKmh(session, distanceMetres, durationSeconds),
                averageHeartRate = averageHeartRate(session, sessionRecords),
                calories = session.totalCalories,
                externalId = externalId(fileId, start),
                telemetry = FitTelemetryMapper.map(start, sessionRecords),
                laps = parsedLaps,
                parserVersion = PARSER_VERSION,
            )
        }

        private fun correlateLaps(
            session: SessionMesg,
            laps: List<LapMesg>,
        ): List<LapMesg> {
            if (session.firstLapIndex != null && session.numLaps != null) {
                val first = session.firstLapIndex.toInt()
                val num = session.numLaps.toInt()
                if (first >= 0 && num >= 0 && first + num <= laps.size) {
                    return laps.subList(first, first + num)
                }
            }
            val from = session.startTime?.timestamp ?: 0L
            val to = session.totalElapsedTime?.let { from + it.toLong() } ?: session.timestamp?.timestamp
            return laps.filter { lap ->
                val lapStart = lap.startTime?.timestamp
                lapStart != null && lapStart >= from && (to == null || lapStart <= to)
            }
        }

        private fun isLapValid(lap: LapMesg): Boolean =
            lap.startTime != null &&
                (lap.totalDistance != null && lap.totalDistance > 0f) &&
                ((lap.totalTimerTime ?: lap.totalElapsedTime ?: 0f) > 0f)

        private fun parseLap(
            lapNumber: Int,
            lap: LapMesg,
            sessionRecords: List<RecordMesg>,
        ): ParsedActivityLap {
            val duration = lap.totalTimerTime ?: lap.totalElapsedTime ?: 0f
            val lapFrom = lap.startTime.timestamp
            val lapTo = lap.totalElapsedTime?.let { lapFrom + it.toLong() } ?: lap.timestamp?.timestamp
            val lapRecords =
                sessionRecords.filter { r ->
                    val t = r.timestamp?.timestamp
                    t != null && t >= lapFrom && (lapTo == null || t <= lapTo)
                }

            val recordedSpeed = (lap.enhancedAvgSpeed ?: lap.avgSpeed)?.takeIf { it > 0f }?.let { metresPerSecondToKmh(it) }
            val averagePaceKmh =
                when {
                    recordedSpeed != null -> recordedSpeed
                    duration > 0f && (lap.totalDistance ?: 0f) > 0f -> metresPerSecondToKmh(lap.totalDistance / duration)
                    else -> null
                }

            val averageHeartRate =
                if (lap.avgHeartRate != null && lap.avgHeartRate > 0) {
                    lap.avgHeartRate.toInt()
                } else {
                    averageHeartRateFromRecords(lapRecords)
                }

            val elevationGainMeters =
                if (lap.totalAscent != null) {
                    lap.totalAscent.toDouble()
                } else {
                    val alts = lapRecords.mapNotNull { (it.enhancedAltitude ?: it.altitude)?.toDouble() }
                    if (alts.size < 2) {
                        null
                    } else {
                        var gain = 0.0
                        for (i in 1 until alts.size) {
                            val diff = alts[i] - alts[i - 1]
                            if (diff > 0.0) {
                                gain += diff
                            }
                        }
                        gain
                    }
                }

            return ParsedActivityLap(
                lapNumber = lapNumber,
                startedAt = lap.startTime.instant,
                distance = lap.totalDistance / 1000.0,
                timeTakenSeconds = (lap.totalTimerTime ?: lap.totalElapsedTime)!!.roundToInt(),
                averagePaceKmh = averagePaceKmh,
                averageHeartRate = averageHeartRate,
                elevationGainMeters = elevationGainMeters,
            )
        }

        private fun generateTitle(
            sport: ActivitySport,
            localStart: LocalTime,
        ): String = "${timeOfDay(localStart)} ${sport.label}"

        private fun timeOfDay(time: LocalTime): String =
            when (time.hour) {
                in 5..11 -> "Morning"
                in 12..16 -> "Afternoon"
                in 17..20 -> "Evening"
                else -> "Night"
            }

        private fun toSport(sport: Sport?): ActivitySport =
            when (sport) {
                Sport.RUNNING -> ActivitySport.RUN
                Sport.WALKING -> ActivitySport.WALK
                Sport.HIKING -> ActivitySport.HIKE
                Sport.CYCLING, Sport.E_BIKING -> ActivitySport.RIDE
                Sport.SWIMMING -> ActivitySport.SWIM
                else -> ActivitySport.WORKOUT
            }

        private fun externalId(
            fileId: FileIdMesg?,
            sessionStart: DateTime,
        ): String? {
            if (fileId == null) return null
            val timeCreated = fileId.timeCreated?.timestamp ?: return null
            val device =
                fileId.serialNumber?.toString()
                    ?: run {
                        val manufacturer = fileId.manufacturer ?: return null
                        val product = fileId.product ?: return null
                        "$manufacturer-$product"
                    }
            return "$device-$timeCreated-${sessionStart.timestamp}"
        }

        private fun localOffset(activity: ActivityMesg?): ZoneOffset {
            val local = activity?.localTimestamp ?: return ZoneOffset.UTC
            val utc = activity.timestamp?.timestamp ?: return ZoneOffset.UTC
            val offset = local - utc
            if (abs(offset) > MAX_OFFSET_SECONDS) return ZoneOffset.UTC
            return ZoneOffset.ofTotalSeconds(offset.toInt())
        }

        private fun averageSpeedKmh(
            session: SessionMesg,
            distanceMetres: Float,
            durationSeconds: Float,
        ): Double? {
            val recorded = (session.enhancedAvgSpeed ?: session.avgSpeed)?.let { metresPerSecondToKmh(it) }
            if (recorded != null) return recorded
            if (durationSeconds <= 0) return null
            return metresPerSecondToKmh(distanceMetres / durationSeconds)
        }

        private fun metresPerSecondToKmh(metresPerSecond: Float): Double = metresPerSecond * 3.6

        private fun averageHeartRate(
            session: SessionMesg,
            sessionRecords: List<RecordMesg>,
        ): Int? = session.avgHeartRate?.toInt() ?: averageHeartRateFromRecords(sessionRecords)

        private fun averageHeartRateFromRecords(sessionRecords: List<RecordMesg>): Int? {
            val rates =
                sessionRecords
                    .mapNotNull { it.heartRate?.toInt() }
                    .filter { it > 0 }
            return if (rates.isEmpty()) null else rates.average().roundToInt()
        }
    }
