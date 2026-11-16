package com.jacobcraig.stridehearth.testutils

import com.garmin.fit.Decode
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.jacobcraig.stridehearth.system.InvalidActivityFileException
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the synthetic sample run in `src/test/resources/runs` (#126, ADR-0012): it is reproducible, it is what
 * [SampleRunGenerator] makes today, and it never leaves the neutral area around Hyde Park.
 *
 * `./gradlew test -PregenerateSampleRun --tests '*SampleRunGeneratorTest'` rewrites both fixtures from the generator.
 */
class SampleRunGeneratorTest {
    private val runsDir = File(TestProcessUtils.findProjectRoot(), "src/test/resources/runs")
    private val sampleFile = File(runsDir, "sample.fit")
    private val corruptFile = File(runsDir, "corrupt_sample.fit")

    init {
        if (System.getProperty("sample.run.regenerate") == "true") {
            SampleRunGenerator.writeFixtures(runsDir)
        }
    }

    @Test
    fun `generating twice gives byte-identical fixtures`() {
        assertContentEquals(SampleRunGenerator.sampleRun(), SampleRunGenerator.sampleRun())
        assertContentEquals(SampleRunGenerator.corruptSampleRun(), SampleRunGenerator.corruptSampleRun())
    }

    @Test
    fun `the committed fixtures are exactly what the generator makes`() {
        val hint = "is stale. Run `./gradlew test -PregenerateSampleRun --tests '*SampleRunGeneratorTest'` and commit it."
        assertContentEquals(SampleRunGenerator.sampleRun(), sampleFile.readBytes(), "sample.fit $hint")
        assertContentEquals(SampleRunGenerator.corruptSampleRun(), corruptFile.readBytes(), "corrupt_sample.fit $hint")
    }

    @Test
    fun `the corrupt fixture is a truncated copy of the sample run that cannot be parsed without repair`() {
        val sample = sampleFile.readBytes()
        val corrupt = corruptFile.readBytes()

        assertTrue(corrupt.size < sample.size)
        assertContentEquals(sample.copyOf(corrupt.size), corrupt)
        assertEquals(1, FitActivityParser().parse(ByteArrayResource(sample)).size)
        assertFailsWith<InvalidActivityFileException> { FitActivityParser().parse(ByteArrayResource(corrupt)) }
    }

    @Test
    fun `every GPS point in both fixtures is inside the neutral area around Hyde Park`() {
        for (file in listOf(sampleFile, corruptFile)) {
            val points = gpsPoints(file.readBytes())
            assertTrue(points.size > 1000, "${file.name} has only ${points.size} GPS points")
            val outside = points.filterNot { (lat, lon) -> SampleRunGenerator.NEUTRAL_AREA.contains(lat, lon) }
            assertEquals(emptyList(), outside, "${file.name} has GPS points outside ${SampleRunGenerator.NEUTRAL_AREA}")
        }
    }

    /** Every recorded (lat, lon) in [bytes], reading as far as a truncated file allows. */
    private fun gpsPoints(bytes: ByteArray): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        val broadcaster = MesgBroadcaster(Decode())
        broadcaster.addListener(
            RecordMesgListener { record: RecordMesg ->
                val lat = record.positionLat
                val lon = record.positionLong
                if (lat != null && lon != null) points += SampleRunGenerator.degrees(lat) to SampleRunGenerator.degrees(lon)
            },
        )
        try {
            broadcaster.run(ByteArrayInputStream(bytes))
        } catch (_: FitRuntimeException) {
            // The corrupt fixture ends mid-record; the points before the cut are what it holds.
        }
        return points
    }
}
