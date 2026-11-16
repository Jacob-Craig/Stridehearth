package com.jacobcraig.stridehearth.system.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.FileIdMesg
import com.garmin.fit.LapMesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.InvalidActivityFileException
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.UnsupportedActivityFileTypeException
import com.jacobcraig.stridehearth.users.User
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.garmin.fit.File as FitFile

class FitActivityParserTest {
    private val sampleBytes = ClassPathResource("runs/sample.fit").inputStream.use { it.readAllBytes() }
    private val corruptBytes = ClassPathResource("runs/corrupt_sample.fit").inputStream.use { it.readAllBytes() }
    private val garbageBytes = "definitely not a fit file".toByteArray()

    private val startInstant = Instant.parse("2024-06-01T06:00:00Z")

    private class CountingRepairer(
        private val output: ByteArray?,
    ) : FitFileRepairer {
        var calls = 0

        override fun repair(bytes: ByteArray): ByteArray? {
            calls++
            return output
        }
    }

    private fun encodeFitFile(vararg mesgs: com.garmin.fit.Mesg): ByteArray =
        BufferEncoder(com.garmin.fit.Fit.ProtocolVersion.V2_0).run {
            mesgs.forEach { write(it) }
            close()
        }

    private fun fileId(
        type: FitFile = FitFile.ACTIVITY,
        serial: Long? = 1234L,
        manufacturer: Int? = 1,
        product: Int? = 42,
        timeCreated: Instant = startInstant,
    ) = FileIdMesg().apply {
        this.type = type
        serialNumber = serial
        this.manufacturer = manufacturer
        this.product = product
        this.timeCreated = DateTime(timeCreated)
    }

    private fun session(
        startAt: Instant? = startInstant,
        distance: Float? = 5000f,
        timer: Float? = 1800f,
        elapsed: Float? = 1800f,
        speed: Float? = 2.778f,
        avgHr: Short? = null,
        sport: Sport = Sport.RUNNING,
    ) = SessionMesg().apply {
        startAt?.let { startTime = DateTime(it) }
        distance?.let { totalDistance = it }
        timer?.let { totalTimerTime = it }
        elapsed?.let { totalElapsedTime = it }
        speed?.let { enhancedAvgSpeed = it }
        avgHr?.let { avgHeartRate = it }
        this.sport = sport
    }

    private fun lap(
        startAt: Instant? = startInstant,
        distance: Float? = 5000f,
        timer: Float? = 1800f,
        elapsed: Float? = 1800f,
        enhancedSpeed: Float? = 2.778f,
        plainSpeed: Float? = null,
        avgHr: Short? = null,
        ascent: Int? = null,
    ) = LapMesg().apply {
        startAt?.let { startTime = DateTime(it) }
        distance?.let { totalDistance = it }
        timer?.let { totalTimerTime = it }
        elapsed?.let { totalElapsedTime = it }
        enhancedSpeed?.let { enhancedAvgSpeed = it }
        plainSpeed?.let { avgSpeed = it }
        avgHr?.let { avgHeartRate = it }
        ascent?.let { totalAscent = it }
    }

    @Test
    fun `parse File with valid file succeeds`(
        @TempDir tempDir: File,
    ) {
        val file = File(tempDir, "sample.fit").apply { writeBytes(sampleBytes) }
        val parser = FitActivityParser()

        val activities = parser.parse(FileSystemResource(file))

        assertEquals(1, activities.size)
        val activity = activities.single()
        assertEquals(ActivitySport.RUN, activity.sport)
        assertEquals(10.20, activity.distance, 0.02)
        assertNotNull(activity.externalId)
        assertNotNull(activity.telemetry)
        assertTrue(activity.telemetry.t.isNotEmpty())
        assertTrue(activity.title.endsWith("Run"))
    }

    @Test
    fun `every parsed Activity is stamped with the parser's current Parser Version`() {
        val activities = FitActivityParser().parse(ByteArrayResource(sampleBytes))

        assertEquals(listOf(ParserVersion(1)), activities.map { it.parserVersion })
        assertEquals(ParserVersion(1), FitActivityParser.PARSER_VERSION, "Parser Version must remain 1 unless deliberately bumped")
    }

    @Test
    fun `parse InputStream with valid stream succeeds`() {
        val parser = FitActivityParser()

        val activities = parser.parse(ByteArrayResource(sampleBytes))

        assertEquals(1, activities.size)
        val activity = activities.single()
        assertEquals(ActivitySport.RUN, activity.sport)
        assertEquals(10.20, activity.distance, 0.02)
        assertNotNull(activity.externalId)
        assertNotNull(activity.telemetry)
        assertTrue(activity.telemetry.t.isNotEmpty())
        assertTrue(activity.title.endsWith("Run"))
    }

    @Test
    fun `valid file does not invoke repairer`(
        @TempDir tempDir: File,
    ) {
        val file = File(tempDir, "sample.fit").apply { writeBytes(sampleBytes) }
        val repairer = CountingRepairer(null)
        val parser = FitActivityParser(repairer)

        val activities = parser.parse(FileSystemResource(file))

        assertEquals(1, activities.size)
        assertEquals(0, repairer.calls)
    }

    @Test
    fun `parse File performs single retry repair on damaged file`(
        @TempDir tempDir: File,
    ) {
        val file = File(tempDir, "damaged.fit").apply { writeBytes(corruptBytes) }
        val repairer = CountingRepairer(sampleBytes)
        val parser = FitActivityParser(repairer)

        val activities = parser.parse(FileSystemResource(file))

        assertEquals(1, activities.size)
        assertEquals(1, repairer.calls)
    }

    @Test
    fun `parse InputStream performs single retry repair on damaged stream`() {
        val repairer = CountingRepairer(sampleBytes)
        val parser = FitActivityParser(repairer)

        val activities = parser.parse(ByteArrayResource(garbageBytes))

        assertEquals(1, activities.size)
        assertEquals(1, repairer.calls)
    }

    @Test
    fun `parse File rejects non-ACTIVITY file without repair attempt`(
        @TempDir tempDir: File,
    ) {
        val workoutBytes = encodeFitFile(fileId(type = FitFile.WORKOUT))
        val file = File(tempDir, "workout.fit").apply { writeBytes(workoutBytes) }
        val repairer = CountingRepairer(sampleBytes)
        val parser = FitActivityParser(repairer)

        val ex =
            assertFailsWith<UnsupportedActivityFileTypeException> {
                parser.parse(FileSystemResource(file))
            }

        assertTrue(ex.message!!.contains("WORKOUT"))
        assertEquals(0, repairer.calls)
    }

    @Test
    fun `parse InputStream rejects non-ACTIVITY file without repair attempt`() {
        val courseBytes = encodeFitFile(fileId(type = FitFile.COURSE))
        val repairer = CountingRepairer(sampleBytes)
        val parser = FitActivityParser(repairer)

        val ex =
            assertFailsWith<UnsupportedActivityFileTypeException> {
                parser.parse(ByteArrayResource(courseBytes))
            }

        assertTrue(ex.message!!.contains("COURSE"))
        assertEquals(0, repairer.calls)
    }

    @Test
    fun `parse File attaches original exception as suppressed when retry fails`(
        @TempDir tempDir: File,
    ) {
        val file = File(tempDir, "garbage.fit").apply { writeBytes(garbageBytes) }
        val repairer = CountingRepairer("still corrupted".toByteArray())
        val parser = FitActivityParser(repairer)

        val ex =
            assertFailsWith<InvalidActivityFileException> {
                parser.parse(FileSystemResource(file))
            }

        assertEquals(1, repairer.calls)
        assertEquals(1, ex.suppressed.size)
        assertTrue(ex.suppressed.first() is InvalidActivityFileException)
    }

    @Test
    fun `parse InputStream attaches original exception as suppressed when retry fails`() {
        val repairer = CountingRepairer("still corrupted".toByteArray())
        val parser = FitActivityParser(repairer)

        val ex =
            assertFailsWith<InvalidActivityFileException> {
                parser.parse(ByteArrayResource(garbageBytes))
            }

        assertEquals(1, repairer.calls)
        assertEquals(1, ex.suppressed.size)
        assertTrue(ex.suppressed.first() is InvalidActivityFileException)
    }

    @Test
    fun `parse rethrows original exception when repairer returns null`(
        @TempDir tempDir: File,
    ) {
        val file = File(tempDir, "corrupt.fit").apply { writeBytes(corruptBytes) }
        val repairer = CountingRepairer(null)
        val parser = FitActivityParser(repairer)

        val ex =
            assertFailsWith<InvalidActivityFileException> {
                parser.parse(FileSystemResource(file))
            }

        assertEquals(1, repairer.calls)
        assertEquals(0, ex.suppressed.size)
    }

    @Test
    fun `empty sessions throws InvalidActivityFileException`() {
        val emptyBytes = encodeFitFile(fileId(), session(startAt = null)) // missing startAt -> skipped
        val parser = FitActivityParser()

        val ex =
            assertFailsWith<InvalidActivityFileException> {
                parser.parse(ByteArrayResource(emptyBytes))
            }

        assertEquals("FIT file contains no valid sessions", ex.message)
    }

    @Test
    fun `title generation uses local time offset from activity message`() {
        // 06:00 UTC with +10h offset (10*3600 = 36000s) -> 16:00 local time -> "Afternoon Run"
        val activity =
            ActivityMesg().apply {
                timestamp = DateTime(startInstant)
                localTimestamp = DateTime(startInstant).timestamp + 10 * 3600
            }
        val bytes = encodeFitFile(fileId(), activity, session())
        val parser = FitActivityParser()

        val result = parser.parse(ByteArrayResource(bytes)).single()

        assertEquals("Afternoon Run", result.title)
    }

    @Test
    fun `title generation falls back to UTC without activity message`() {
        // 06:00 UTC -> 06:00 local time -> "Morning Run"
        val bytes = encodeFitFile(fileId(), session())
        val parser = FitActivityParser()

        val result = parser.parse(ByteArrayResource(bytes)).single()

        assertEquals("Morning Run", result.title)
    }

    @Test
    fun `title generation maps all times of day correctly`() {
        val times =
            listOf(
                Instant.parse("2024-06-01T07:00:00Z") to "Morning",
                Instant.parse("2024-06-01T13:00:00Z") to "Afternoon",
                Instant.parse("2024-06-01T18:00:00Z") to "Evening",
                Instant.parse("2024-06-01T22:00:00Z") to "Night",
            )
        val parser = FitActivityParser()

        for ((instant, expectedPrefix) in times) {
            val bytes = encodeFitFile(fileId(timeCreated = instant), session(startAt = instant))
            val result = parser.parse(ByteArrayResource(bytes)).single()
            assertEquals("$expectedPrefix Run", result.title)
        }
    }

    @Test
    fun `maps sports to correct domain sports`() {
        val testCases =
            listOf(
                Sport.RUNNING to ActivitySport.RUN,
                Sport.CYCLING to ActivitySport.RIDE,
                Sport.E_BIKING to ActivitySport.RIDE,
                Sport.SWIMMING to ActivitySport.SWIM,
                Sport.WALKING to ActivitySport.WALK,
                Sport.HIKING to ActivitySport.HIKE,
                Sport.GENERIC to ActivitySport.WORKOUT,
            )
        val parser = FitActivityParser()

        for ((fitSport, expectedDomainSport) in testCases) {
            val bytes = encodeFitFile(fileId(), session(sport = fitSport))
            val result = parser.parse(ByteArrayResource(bytes)).single()
            assertEquals(expectedDomainSport, result.sport)
        }
    }

    @Test
    fun `external ID calculation uses serial or fallback product`() {
        val parser = FitActivityParser()
        val created = DateTime(startInstant).timestamp

        // With serial
        val withSerialBytes = encodeFitFile(fileId(serial = 9999L), session())
        val result1 = parser.parse(ByteArrayResource(withSerialBytes)).single()
        assertEquals("9999-$created-$created", result1.externalId)

        // Without serial
        val withoutSerialBytes = encodeFitFile(fileId(serial = null, manufacturer = 1, product = 42), session())
        val result2 = parser.parse(ByteArrayResource(withoutSerialBytes)).single()
        assertEquals("1-42-$created-$created", result2.externalId)
    }

    @Test
    fun `average heart rate uses session avgHeartRate when present`() {
        val bytes = encodeFitFile(fileId(), session(avgHr = 150.toShort()))
        val parser = FitActivityParser()

        val result = parser.parse(ByteArrayResource(bytes)).single()

        assertEquals(150, result.averageHeartRate)
    }

    @Test
    fun `average heart rate falls back to records within session window`() {
        val rec1 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(10))
                heartRate = 140.toShort()
            }
        val rec2 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(20))
                heartRate = 160.toShort()
            }
        // Outside the 1800s window
        val recOutside =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(2000))
                heartRate = 190.toShort()
            }
        // Zero HR should be ignored
        val recZero =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(30))
                heartRate = 0.toShort()
            }

        val bytes = encodeFitFile(fileId(), session(avgHr = null, elapsed = 1800f), rec1, rec2, recOutside, recZero)
        val parser = FitActivityParser()

        val result = parser.parse(ByteArrayResource(bytes)).single()

        // Average of 140 and 160 = 150
        assertEquals(150, result.averageHeartRate)
        // Telemetry only contains sliced session records
        assertNotNull(result.telemetry)
        assertEquals(3, result.telemetry.t.size) // rec1, rec2, recZero within window
    }

    @Test
    fun `average heart rate is null when session and records lack heart rate`() {
        val bytes = encodeFitFile(fileId(), session(avgHr = null))
        val parser = FitActivityParser()

        val result = parser.parse(ByteArrayResource(bytes)).single()

        assertNull(result.averageHeartRate)
    }

    @Test
    fun `truncated file throws InvalidActivityFileException`() {
        val truncated = sampleBytes.copyOf(sampleBytes.size / 2)
        val parser = FitActivityParser()

        assertFailsWith<InvalidActivityFileException> {
            parser.parse(ByteArrayResource(truncated))
        }
    }

    @Test
    fun `converts units and prefers timer time over elapsed time`() {
        val s =
            session(
                distance = 5240f,
                timer = 1800f,
                elapsed = 1900f,
                speed = 2.5f,
                sport = Sport.RUNNING,
            )
        val bytes = encodeFitFile(fileId(), s)
        val parser = FitActivityParser()

        val activity = parser.parse(ByteArrayResource(bytes)).single()

        assertEquals(ActivitySport.RUN, activity.sport)
        assertEquals(startInstant, activity.startedAt)
        assertEquals(5.24, activity.distance, 1e-4)
        assertEquals(1800, activity.timeTakenSeconds)
        assertEquals(9.0, activity.averagePaceKmh!!, 1e-4)
        assertNull(activity.averageHeartRate)
        assertNull(activity.calories)
    }

    @Test
    fun `falls back to elapsed time and plain avg speed`() {
        val s =
            SessionMesg().apply {
                startTime = DateTime(startInstant)
                totalDistance = 5240f
                totalElapsedTime = 1900f
                avgSpeed = 3.0f
                sport = Sport.RUNNING
            }
        val bytes = encodeFitFile(fileId(), s)
        val parser = FitActivityParser()

        val activity = parser.parse(ByteArrayResource(bytes)).single()

        assertEquals(1900, activity.timeTakenSeconds)
        assertEquals(10.8, activity.averagePaceKmh!!, 1e-4)
    }

    @Test
    fun `calculates speed from distance and duration when speeds are omitted`() {
        val s =
            SessionMesg().apply {
                startTime = DateTime(startInstant)
                totalDistance = 5000f
                totalTimerTime = 1800f
                sport = Sport.RUNNING
            }
        val bytes = encodeFitFile(fileId(), s)
        val parser = FitActivityParser()

        val activity = parser.parse(ByteArrayResource(bytes)).single()

        assertEquals(5.0, activity.distance, 1e-4)
        assertEquals(1800, activity.timeTakenSeconds)
        assertEquals(10.0, activity.averagePaceKmh!!, 1e-4)
    }

    @Test
    fun `multiple sessions produce distinct external ids`() {
        val s1 = session(startAt = startInstant, sport = Sport.RUNNING)
        val s2 = session(startAt = startInstant.plusSeconds(3600), sport = Sport.CYCLING)
        val bytes = encodeFitFile(fileId(), s1, s2)
        val parser = FitActivityParser()

        val result = parser.parse(ByteArrayResource(bytes))

        assertEquals(2, result.size)
        assertEquals(ActivitySport.RUN, result[0].sport)
        assertEquals(ActivitySport.RIDE, result[1].sport)
        assertNotEquals(result[0].externalId, result[1].externalId)
        assertTrue(result[1].title.endsWith("Ride"))
    }

    @Test
    fun `external id is null when file id lacks timeCreated`() {
        val fid =
            FileIdMesg().apply {
                type = FitFile.ACTIVITY
                serialNumber = 1234L
            }
        val bytes = encodeFitFile(fid, session())
        val parser = FitActivityParser()

        val activity = parser.parse(ByteArrayResource(bytes)).single()

        assertNull(activity.externalId)
    }

    @Test
    fun `file without file id throws InvalidActivityFileException`() {
        val bytes = encodeFitFile(session())
        val parser = FitActivityParser()

        val ex =
            assertFailsWith<InvalidActivityFileException> {
                parser.parse(ByteArrayResource(bytes))
            }

        assertEquals("FIT file has no file_id message", ex.message)
    }

    @Test
    fun `skips sessions missing required fields`() {
        val invalidMissingStart =
            SessionMesg().apply {
                totalDistance = 5000f
                totalTimerTime = 1800f
            }
        val invalidMissingDistance =
            SessionMesg().apply {
                startTime = DateTime(startInstant)
                totalTimerTime = 1800f
            }
        val invalidMissingTime =
            SessionMesg().apply {
                startTime = DateTime(startInstant)
                totalDistance = 5000f
            }
        val valid = session()

        val bytes = encodeFitFile(fileId(), invalidMissingStart, invalidMissingDistance, invalidMissingTime, valid)
        val parser = FitActivityParser()

        val activities = parser.parse(ByteArrayResource(bytes))

        assertEquals(1, activities.size)
    }

    @Test
    fun `title generation covers all time of day boundary buckets`() {
        val cases =
            listOf(
                "00:00" to "Night",
                "04:59" to "Night",
                "05:00" to "Morning",
                "11:59" to "Morning",
                "12:00" to "Afternoon",
                "16:59" to "Afternoon",
                "17:00" to "Evening",
                "20:59" to "Evening",
                "21:00" to "Night",
                "23:59" to "Night",
            )
        val parser = FitActivityParser()
        for ((timeStr, expectedPrefix) in cases) {
            val instant = Instant.parse("2024-06-01T$timeStr:00Z")
            val bytes = encodeFitFile(fileId(timeCreated = instant), session(startAt = instant))
            val result = parser.parse(ByteArrayResource(bytes)).single()
            assertEquals("$expectedPrefix Run", result.title)
        }
    }

    @Test
    fun `title generation uses sport label for each sport`() {
        val parser = FitActivityParser()
        val sports =
            listOf(
                Sport.RUNNING to "Morning Run",
                Sport.CYCLING to "Morning Ride",
                Sport.SWIMMING to "Morning Swim",
                Sport.WALKING to "Morning Walk",
                Sport.HIKING to "Morning Hike",
                Sport.GENERIC to "Morning Workout",
            )
        for ((sport, expectedTitle) in sports) {
            val instant = Instant.parse("2024-06-01T07:00:00Z")
            val bytes = encodeFitFile(fileId(timeCreated = instant), session(startAt = instant, sport = sport))
            val result = parser.parse(ByteArrayResource(bytes)).single()
            assertEquals(expectedTitle, result.title)
        }
    }

    @Test
    fun `toActivity maps ParsedActivity to Activity entity correctly`() {
        val dummyUser =
            User(
                email = "test@test.com",
                passwordHash = "hash",
                username = "userName",
                firstName = "first",
            ).apply { id = 1L }
        val parser = FitActivityParser()

        val parsed = parser.parse(ByteArrayResource(sampleBytes)).single()
        val activity = parsed.toActivity(dummyUser, ActivitySource.FIT_UPLOAD)

        assertEquals(dummyUser, activity.user)
        assertEquals(ActivitySource.FIT_UPLOAD, activity.source)
        assertEquals(ActivitySport.RUN, activity.sport)
        assertNotNull(activity.externalId)
        assertTrue(activity.externalId!!.split("-").size in 3..4)
        assertEquals(10.20, activity.distance, 0.01)
        assertTrue(activity.title.endsWith("Run"))
    }

    @Test
    fun `sample fit has 1 valid lap with trailing degenerate lap filtered out`() {
        val parser = FitActivityParser()
        val activity = parser.parse(ByteArrayResource(sampleBytes)).single()

        assertEquals(1, activity.laps.size)
        val lap = activity.laps.single()
        assertEquals(1, lap.lapNumber)
        assertEquals(10.20, lap.distance, 0.02)
        assertEquals(activity.startedAt, lap.startedAt)
        assertEquals(activity.timeTakenSeconds, lap.timeTakenSeconds)
        assertNotNull(lap.averagePaceKmh)
        assertTrue(lap.averagePaceKmh > 0.0)
        assertEquals(150, lap.averageHeartRate)
        assertNotNull(lap.elevationGainMeters)
        assertTrue(lap.elevationGainMeters > 0.0)
    }

    @Test
    fun `multi-lap parsing with explicit summary fields parses all laps correctly`() {
        val s =
            session(distance = 10000f, timer = 3600f, elapsed = 3600f).apply {
                firstLapIndex = 0
                numLaps = 2
            }
        val lap1 =
            lap(
                startAt = startInstant,
                distance = 5000f,
                timer = 1800f,
                elapsed = 1800f,
                enhancedSpeed = 2.778f, // 10.0008 km/h
                avgHr = 145.toShort(),
                ascent = 35,
            )
        val lap2 =
            lap(
                startAt = startInstant.plusSeconds(1800),
                distance = 5000f,
                timer = 1800f,
                elapsed = 1800f,
                enhancedSpeed = null,
                plainSpeed = 3.0f, // 10.8 km/h
                avgHr = 155.toShort(),
                ascent = 25,
            )

        val bytes = encodeFitFile(fileId(), s, lap1, lap2)
        val parser = FitActivityParser()
        val activity = parser.parse(ByteArrayResource(bytes)).single()

        assertEquals(2, activity.laps.size)

        val parsedLap1 = activity.laps[0]
        assertEquals(1, parsedLap1.lapNumber)
        assertEquals(startInstant, parsedLap1.startedAt)
        assertEquals(5.0, parsedLap1.distance, 1e-4)
        assertEquals(1800, parsedLap1.timeTakenSeconds)
        assertEquals(10.0008, parsedLap1.averagePaceKmh!!, 1e-4)
        assertEquals(145, parsedLap1.averageHeartRate)
        assertEquals(35.0, parsedLap1.elevationGainMeters)

        val parsedLap2 = activity.laps[1]
        assertEquals(2, parsedLap2.lapNumber)
        assertEquals(startInstant.plusSeconds(1800), parsedLap2.startedAt)
        assertEquals(5.0, parsedLap2.distance, 1e-4)
        assertEquals(1800, parsedLap2.timeTakenSeconds)
        assertEquals(10.8, parsedLap2.averagePaceKmh!!, 1e-4)
        assertEquals(155, parsedLap2.averageHeartRate)
        assertEquals(25.0, parsedLap2.elevationGainMeters)
    }

    @Test
    fun `filtering degenerate laps discards invalid laps and re-indexes sequentially from 1`() {
        val s = session(distance = 3000f, timer = 900f, elapsed = 900f)

        // 1. Missing startTime
        val lapNullStart =
            LapMesg().apply {
                totalDistance = 1000f
                totalTimerTime = 300f
            }
        // 2. Valid Lap 1
        val validLap1 = lap(startAt = startInstant, distance = 1000f, timer = 300f)

        // 3. Zero distance
        val lapZeroDist = lap(startAt = startInstant.plusSeconds(300), distance = 0f, timer = 100f)

        // 4. Null distance
        val lapNullDist = lap(startAt = startInstant.plusSeconds(400), distance = null, timer = 100f)

        // 5. Zero duration (both timer and elapsed = 0)
        val lapZeroDuration = lap(startAt = startInstant.plusSeconds(500), distance = 500f, timer = 0f, elapsed = 0f)

        // 6. Null duration (both timer and elapsed = null)
        val lapNullDuration = lap(startAt = startInstant.plusSeconds(550), distance = 500f, timer = null, elapsed = null)

        // 7. Valid Lap 2
        val validLap2 = lap(startAt = startInstant.plusSeconds(600), distance = 2000f, timer = 600f)

        // 8. Trailing 0.0m session end lap
        val lapTrailingZero = lap(startAt = startInstant.plusSeconds(1200), distance = 0f, timer = 0f, elapsed = 0f)

        val bytes =
            encodeFitFile(
                fileId(),
                s,
                lapNullStart,
                validLap1,
                lapZeroDist,
                lapNullDist,
                lapZeroDuration,
                lapNullDuration,
                validLap2,
                lapTrailingZero,
            )
        val parser = FitActivityParser()
        val activity = parser.parse(ByteArrayResource(bytes)).single()

        assertEquals(2, activity.laps.size)
        assertEquals(1, activity.laps[0].lapNumber)
        assertEquals(1.0, activity.laps[0].distance, 1e-4)
        assertEquals(300, activity.laps[0].timeTakenSeconds)

        assertEquals(2, activity.laps[1].lapNumber)
        assertEquals(2.0, activity.laps[1].distance, 1e-4)
        assertEquals(600, activity.laps[1].timeTakenSeconds)
    }

    @Test
    fun `fallback HR and elevation calculation from RecordMesgs when LapMesg summary fields are omitted`() {
        val s = session(distance = 3000f, timer = 1200f, elapsed = 1200f)

        val lap1 =
            lap(
                startAt = startInstant,
                distance = 1500f,
                timer = 600f,
                elapsed = 600f,
                enhancedSpeed = null,
                plainSpeed = null,
                avgHr = null,
                ascent = null,
            )
        val lap2 =
            lap(
                startAt = startInstant.plusSeconds(600),
                distance = 1500f,
                timer = 600f,
                elapsed = 600f,
                enhancedSpeed = null,
                plainSpeed = null,
                avgHr = 0.toShort(), // 0 avgHr should also trigger fallback to records
                ascent = null,
            )

        // Lap 1 records: +10s (HR 140, alt 100.0), +30s (HR 160, alt 110.0), +50s (HR 0, alt 105.0), +70s (HR 150, alt 115.0)
        // HR average: (140 + 160 + 150) / 3 = 150
        // Elevation gain: (110 - 100) + (115 - 105) = 10 + 10 = 20.0
        val r1 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(10))
                heartRate = 140.toShort()
                altitude = 100.0f
            }
        val r2 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(30))
                heartRate = 160.toShort()
                altitude = 110.0f
            }
        val r3 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(50))
                heartRate = 0.toShort() // positive only
                altitude = 105.0f // descent
            }
        val r4 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(70))
                heartRate = 150.toShort()
                enhancedAltitude = 115.0f // enhancedAltitude preferred
            }

        // Lap 2 record: only 1 altitude point -> elevationGainMeters should be null; no HR -> averageHeartRate null
        val r5 =
            RecordMesg().apply {
                timestamp = DateTime(startInstant.plusSeconds(610))
                altitude = 120.0f
            }

        val bytes = encodeFitFile(fileId(), s, lap1, lap2, r1, r2, r3, r4, r5)
        val parser = FitActivityParser()
        val activity = parser.parse(ByteArrayResource(bytes)).single()

        assertEquals(2, activity.laps.size)

        val parsedLap1 = activity.laps[0]
        assertEquals(1, parsedLap1.lapNumber)
        // Calculated speed: (1500m / 600s) * 3.6 = 9.0 km/h
        assertEquals(9.0, parsedLap1.averagePaceKmh!!, 1e-4)
        assertEquals(150, parsedLap1.averageHeartRate)
        assertEquals(20.0, parsedLap1.elevationGainMeters!!, 1e-4)

        val parsedLap2 = activity.laps[1]
        assertEquals(2, parsedLap2.lapNumber)
        assertEquals(9.0, parsedLap2.averagePaceKmh!!, 1e-4)
        assertNull(parsedLap2.averageHeartRate)
        assertNull(parsedLap2.elevationGainMeters)
    }

    @Test
    fun `multi-session correlation uses firstLapIndex and numLaps when valid`() {
        val s1 =
            session(startAt = startInstant, distance = 2000f, timer = 600f, elapsed = 600f).apply {
                firstLapIndex = 0
                numLaps = 2
            }
        val s2 =
            session(startAt = startInstant.plusSeconds(7200), distance = 1000f, timer = 300f, elapsed = 300f).apply {
                firstLapIndex = 2
                numLaps = 1
            }

        val lap0 = lap(startAt = startInstant, distance = 1000f, timer = 300f)
        val lap1 = lap(startAt = startInstant.plusSeconds(300), distance = 1000f, timer = 300f)
        val lap2 = lap(startAt = startInstant.plusSeconds(7200), distance = 1000f, timer = 300f)

        val bytes = encodeFitFile(fileId(), s1, s2, lap0, lap1, lap2)
        val parser = FitActivityParser()
        val activities = parser.parse(ByteArrayResource(bytes))

        assertEquals(2, activities.size)

        assertEquals(2, activities[0].laps.size)
        assertEquals(1, activities[0].laps[0].lapNumber)
        assertEquals(startInstant, activities[0].laps[0].startedAt)
        assertEquals(2, activities[0].laps[1].lapNumber)
        assertEquals(startInstant.plusSeconds(300), activities[0].laps[1].startedAt)

        assertEquals(1, activities[1].laps.size)
        assertEquals(1, activities[1].laps[0].lapNumber)
        assertEquals(startInstant.plusSeconds(7200), activities[1].laps[0].startedAt)
    }

    @Test
    fun `multi-session correlation falls back to timestamp window when indices missing or out of bounds`() {
        // s1 has missing firstLapIndex / numLaps
        val s1 = session(startAt = startInstant, distance = 1000f, timer = 300f, elapsed = 300f)
        // s2 has out of bounds firstLapIndex
        val s2 =
            session(startAt = startInstant.plusSeconds(5000), distance = 1000f, timer = 300f, elapsed = 300f).apply {
                firstLapIndex = 99
                numLaps = 5
            }

        val lapA = lap(startAt = startInstant.plusSeconds(10), distance = 1000f, timer = 290f)
        val lapB = lap(startAt = startInstant.plusSeconds(5010), distance = 1000f, timer = 290f)

        val bytes = encodeFitFile(fileId(), s1, s2, lapA, lapB)
        val parser = FitActivityParser()
        val activities = parser.parse(ByteArrayResource(bytes))

        assertEquals(2, activities.size)

        assertEquals(1, activities[0].laps.size)
        assertEquals(1, activities[0].laps[0].lapNumber)
        assertEquals(startInstant.plusSeconds(10), activities[0].laps[0].startedAt)

        assertEquals(1, activities[1].laps.size)
        assertEquals(1, activities[1].laps[0].lapNumber)
        assertEquals(startInstant.plusSeconds(5010), activities[1].laps[0].startedAt)
    }
}
