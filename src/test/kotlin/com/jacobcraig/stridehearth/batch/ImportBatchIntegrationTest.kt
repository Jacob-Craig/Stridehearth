package com.jacobcraig.stridehearth.batch

import com.garmin.fit.ActivityMesg
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityDetailsRepository
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.acceptParsedFixtures
import com.jacobcraig.stridehearth.activities.importParsed
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.users.AppUserDetails
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.Duration
import java.time.Instant
import com.garmin.fit.File as FitFile

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "stridehearth.storage.temp-dir=build/temp-test-batches",
    ],
)
@WithUserDetails(value = "batch_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ImportBatchIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoSpyBean
    private lateinit var diskSpaceChecker: DefaultDiskSpaceChecker

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var importBatchRepository: ImportBatchRepository

    @Autowired
    private lateinit var importBatchItemRepository: ImportBatchItemRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoSpyBean
    private lateinit var fitActivityParser: com.jacobcraig.stridehearth.system.fit.FitActivityParser

    @Value("\${stridehearth.storage.temp-dir:./temp-batches}")
    private lateinit var tempDirProperty: String

    private lateinit var testUser: User
    private lateinit var otherUser: User

    @BeforeEach
    fun setUp() {
        fitActivityParser.acceptParsedFixtures()
        testUser =
            userRepository.save(
                User(email = "batch_runner@example.com", passwordHash = "x", username = "batch_runner"),
            )
        otherUser =
            userRepository.save(
                User(email = "other_user@example.com", passwordHash = "x", username = "other_user"),
            )
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        importBatchItemRepository.deleteAll()
        importBatchRepository.deleteAll()
        userRepository.deleteAll()
        File(tempDirProperty).deleteRecursively()
    }

    @Test
    fun `upload loose FIT file creates batch, processes asynchronously and cleans up storage`() {
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val multipartFile = MockMultipartFile("files", "sample.fit", "application/octet-stream", fitBytes)

        val postResult =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(multipartFile)
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.batchId").isNumber)
                .andExpect(jsonPath("$.totalFiles").value(1))
                .andReturn()

        val jsonResponse: JsonNode = objectMapper.readTree(postResult.response.contentAsString)
        val batchId = jsonResponse.get("batchId").asLong()

        val summary = awaitBatchCompletion(batchId)

        assertEquals("COMPLETED", summary.get("status").asString())
        assertEquals(1, summary.get("totalFiles").asInt())
        assertEquals(1, summary.get("processedFiles").asInt())
        assertEquals(1, summary.get("successfulFiles").asInt())
        assertEquals(0, summary.get("duplicateFiles").asInt())
        assertEquals(0, summary.get("failedFiles").asInt())

        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(1, activities.size, "Exactly one activity should be persisted for user")
        val activity = activities[0]
        assertEquals("Afternoon Run", activity.title)

        val telemetryJson = activityDetailsRepository.findTelemetryJson(activity.id!!, testUser.id!!)
        assertNotNull(telemetryJson, "Activity details telemetry should be persisted")
        assertTrue(telemetryJson!!.contains("\"t\":"), "Telemetry JSON must contain timeline")

        val batchDir = File(tempDirProperty, batchId.toString())
        assertFalse(batchDir.exists(), "Batch directory should be deleted upon batch finalization")
    }

    @Test
    fun `re-uploading identical FIT file marks item as DUPLICATE without creating new activity`() {
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val file1 = MockMultipartFile("files", "sample.fit", "application/octet-stream", fitBytes)

        // First upload
        val post1 =
            mockMvc
                .perform(multipart("/api/activities/import").file(file1).with(csrf()))
                .andExpect(status().isAccepted)
                .andReturn()
        val batchId1 = objectMapper.readTree(post1.response.contentAsString).get("batchId").asLong()
        val summary1 = awaitBatchCompletion(batchId1)
        assertEquals("COMPLETED", summary1.get("status").asString())
        assertEquals(1, summary1.get("successfulFiles").asInt())

        val initialActivityCount = activityRepository.findByUserId(testUser.id!!).size
        assertEquals(1, initialActivityCount)

        // Second upload of identical file in a new batch
        val file2 = MockMultipartFile("files", "sample.fit", "application/octet-stream", fitBytes)
        val post2 =
            mockMvc
                .perform(multipart("/api/activities/import").file(file2).with(csrf()))
                .andExpect(status().isAccepted)
                .andReturn()
        val batchId2 = objectMapper.readTree(post2.response.contentAsString).get("batchId").asLong()
        val summary2 = awaitBatchCompletion(batchId2)

        assertEquals("COMPLETED", summary2.get("status").asString())
        assertEquals(1, summary2.get("totalFiles").asInt())
        assertEquals(1, summary2.get("processedFiles").asInt())
        assertEquals(0, summary2.get("successfulFiles").asInt())
        assertEquals(1, summary2.get("duplicateFiles").asInt())
        assertEquals(0, summary2.get("failedFiles").asInt())

        val afterActivityCount = activityRepository.findByUserId(testUser.id!!).size
        assertEquals(1, afterActivityCount, "Duplicate file should not insert additional activity")

        val batchDir = File(tempDirProperty, batchId2.toString())
        assertFalse(batchDir.exists(), "Duplicate batch directory should be deleted on finalization")
    }

    @Test
    fun `uploading a corrupt FIT file records FAILED item and increments failedFiles counter`() {
        val corruptBytes = ClassPathResource("runs/corrupt_sample.fit").inputStream.readBytes()
        val corruptFile = MockMultipartFile("files", "corrupt_sample.fit", "application/octet-stream", corruptBytes)

        val postResult =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(corruptFile)
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andReturn()

        val batchId = objectMapper.readTree(postResult.response.contentAsString).get("batchId").asLong()
        val summary = awaitBatchCompletion(batchId)

        assertEquals("COMPLETED", summary.get("status").asString())
        assertEquals(1, summary.get("totalFiles").asInt())
        assertEquals(1, summary.get("processedFiles").asInt())
        assertEquals(0, summary.get("successfulFiles").asInt())
        assertEquals(0, summary.get("duplicateFiles").asInt())
        assertEquals(1, summary.get("failedFiles").asInt())

        val items = importBatchItemRepository.findByBatchId(batchId)
        assertEquals(1, items.size)
        assertEquals(ImportBatchItemStatus.FAILED, items[0].status)
        assertNotNull(items[0].errorMessage, "Error message should describe corruption failure")

        val batchDir = File(tempDirProperty, batchId.toString())
        assertFalse(batchDir.exists(), "Batch directory should be deleted even when items fail")
    }

    @Test
    fun `multi-file upload with valid and corrupt files isolates failures and increments respective counters`() {
        val validBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val corruptBytes = ClassPathResource("runs/corrupt_sample.fit").inputStream.readBytes()

        val validFile = MockMultipartFile("files", "valid.fit", "application/octet-stream", validBytes)
        val corruptFile = MockMultipartFile("files", "corrupt.fit", "application/octet-stream", corruptBytes)

        val postResult =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(validFile)
                        .file(corruptFile)
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.totalFiles").value(2))
                .andReturn()

        val batchId = objectMapper.readTree(postResult.response.contentAsString).get("batchId").asLong()
        val summary = awaitBatchCompletion(batchId)

        assertEquals("COMPLETED", summary.get("status").asString())
        assertEquals(2, summary.get("totalFiles").asInt())
        assertEquals(2, summary.get("processedFiles").asInt())
        assertEquals(1, summary.get("successfulFiles").asInt())
        assertEquals(0, summary.get("duplicateFiles").asInt())
        assertEquals(1, summary.get("failedFiles").asInt())

        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(1, activities.size, "Valid file must be saved despite the other file failing")

        val batchDir = File(tempDirProperty, batchId.toString())
        assertFalse(batchDir.exists(), "Batch directory should be deleted upon finalization")
    }

    @Test
    fun `upload with empty files returns 400 Bad Request`() {
        val emptyFile = MockMultipartFile("files", "empty.fit", "application/octet-stream", ByteArray(0))

        mockMvc
            .perform(
                multipart("/api/activities/import")
                    .file(emptyFile)
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `uploading ZIP archive with Zip Slip path traversal is rejected with 400 Bad Request`() {
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val zipBytes = createZipArchive(mapOf("../evil.fit" to fitBytes))
        val zipFile = MockMultipartFile("files", "malicious.zip", "application/zip", zipBytes)

        mockMvc
            .perform(
                multipart("/api/activities/import")
                    .file(zipFile)
                    .with(csrf()),
            ).andExpect(status().isBadRequest)

        val batchCount = importBatchRepository.count()
        assertEquals(0, batchCount, "No batch should be created when Zip Slip traversal is detected")
    }

    @Test
    fun `uploading ZIP archive exceeding 1,000 files is rejected with 400 Bad Request`() {
        val entries = (1..1001).associate { "workout_$it.fit" to byteArrayOf(1, 2) }
        val zipBytes = createZipArchive(entries)
        val zipFile = MockMultipartFile("files", "too_many_files.zip", "application/zip", zipBytes)

        mockMvc
            .perform(
                multipart("/api/activities/import")
                    .file(zipFile)
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("exceeds limit of 1000 files")))

        val batchCount = importBatchRepository.count()
        assertEquals(0, batchCount, "No batch should be created when archive exceeds file count limit")
    }

    @Test
    fun `uploading ZIP archive exceeding 500MB uncompressed size is rejected with 400 Bad Request`() {
        // 501MB = 525,336,576 bytes
        val zipBytes = createZipArchiveWithHeaderSize("large.fit", 501L * 1024 * 1024)
        val zipFile = MockMultipartFile("files", "too_large.zip", "application/zip", zipBytes)

        mockMvc
            .perform(
                multipart("/api/activities/import")
                    .file(zipFile)
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("exceeds limit of 524288000 bytes")))

        val batchCount = importBatchRepository.count()
        assertEquals(0, batchCount, "No batch should be created when archive exceeds uncompressed size limit")
    }

    @Test
    fun `uploading ZIP archive when usable disk space is insufficient is rejected with 400 Bad Request`() {
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val zipBytes = createZipArchive(mapOf("sample.fit" to fitBytes))
        val zipFile = MockMultipartFile("files", "sample.zip", "application/zip", zipBytes)

        // Simulate insufficient disk space: usableSpace <= uncompressedSize + 500MB
        org.mockito.Mockito
            .doReturn(100L)
            .`when`(diskSpaceChecker)
            .getUsableSpace(org.mockito.kotlin.any<java.nio.file.Path>())

        try {
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(zipFile)
                        .with(csrf()),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient disk space")))

            val batchCount = importBatchRepository.count()
            assertEquals(0, batchCount, "No batch should be created when disk space is insufficient")
        } finally {
            org.mockito.Mockito.reset(diskSpaceChecker)
        }
    }

    private fun createZipArchiveWithHeaderSize(
        entryName: String,
        claimedUncompressedSize: Long,
    ): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            val entry = java.util.zip.ZipEntry(entryName)
            zos.putNextEntry(entry)
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()
        }
        val bytes = baos.toByteArray()
        for (i in 0 until bytes.size - 28) {
            if (bytes[i] == 0x50.toByte() &&
                bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x01.toByte() &&
                bytes[i + 3] == 0x02.toByte()
            ) {
                val offset = i + 24
                bytes[offset] = (claimedUncompressedSize and 0xFF).toByte()
                bytes[offset + 1] = ((claimedUncompressedSize shr 8) and 0xFF).toByte()
                bytes[offset + 2] = ((claimedUncompressedSize shr 16) and 0xFF).toByte()
                bytes[offset + 3] = ((claimedUncompressedSize shr 24) and 0xFF).toByte()
                break
            }
        }
        return bytes
    }

    private fun createZipArchive(entries: Map<String, ByteArray>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                val entry = java.util.zip.ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(content)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `uploading ZIP archive silently filters non-FIT entries and OS metadata while staging valid FIT files`() {
        val validBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val corruptBytes = ClassPathResource("runs/corrupt_sample.fit").inputStream.readBytes()

        val zipBytes =
            createZipArchive(
                mapOf(
                    "activities/valid.fit" to validBytes,
                    "corrupt.fit" to corruptBytes,
                    "__MACOSX/._valid.fit" to byteArrayOf(1, 2, 3),
                    "__MACOSX/activities/._valid.fit" to byteArrayOf(1, 2, 3),
                    ".DS_Store" to byteArrayOf(0, 0, 0),
                    "activities/.DS_Store" to byteArrayOf(0, 0, 0),
                    "notes.txt" to "Garmin export info".toByteArray(),
                    "data.csv" to "col1,col2".toByteArray(),
                ),
            )
        val zipFile = MockMultipartFile("files", "activities.zip", "application/zip", zipBytes)

        val postResult =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(zipFile)
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.batchId").isNumber)
                .andExpect(jsonPath("$.totalFiles").value(2))
                .andReturn()

        val batchId = objectMapper.readTree(postResult.response.contentAsString).get("batchId").asLong()
        val summary = awaitBatchCompletion(batchId)

        assertEquals("COMPLETED", summary.get("status").asString())
        assertEquals(2, summary.get("totalFiles").asInt())
        assertEquals(2, summary.get("processedFiles").asInt())
        assertEquals(1, summary.get("successfulFiles").asInt())
        assertEquals(0, summary.get("duplicateFiles").asInt())
        assertEquals(1, summary.get("failedFiles").asInt())

        // Verify only the 2 valid FIT entries were tracked as items
        val items = importBatchItemRepository.findByBatchId(batchId)
        assertEquals(2, items.size)
        assertTrue(items.all { it.originalFilename.endsWith(".fit") })

        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(1, activities.size, "Valid FIT activity must be saved")

        val batchDir = File(tempDirProperty, batchId.toString())
        assertFalse(batchDir.exists(), "Batch directory should be deleted upon finalization")
    }

    @Test
    fun `uploading ZIP archive containing only non-FIT or OS metadata files is rejected with 400 Bad Request`() {
        val zipBytes =
            createZipArchive(
                mapOf(
                    "__MACOSX/._sample.fit" to byteArrayOf(1, 2, 3),
                    ".DS_Store" to byteArrayOf(0, 0, 0),
                    "readme.txt" to "no workouts here".toByteArray(),
                    "archive.tar" to byteArrayOf(4, 5, 6),
                ),
            )
        val zipFile = MockMultipartFile("files", "useless.zip", "application/zip", zipBytes)

        mockMvc
            .perform(
                multipart("/api/activities/import")
                    .file(zipFile)
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No valid .fit files")))

        val batchCount = importBatchRepository.count()
        assertEquals(0, batchCount, "No batch should be created when archive contains no valid FIT files")
    }

    @Test
    fun `accessing non-existent batch or another user's batch returns 404`() {
        // Non-existent batch
        mockMvc
            .perform(get("/api/activities/import/batches/999999"))
            .andExpect(status().isNotFound)

        // Batch owned by testUser
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val file = MockMultipartFile("files", "sample.fit", "application/octet-stream", fitBytes)
        val post =
            mockMvc
                .perform(multipart("/api/activities/import").file(file).with(csrf()))
                .andExpect(status().isAccepted)
                .andReturn()
        val batchId = objectMapper.readTree(post.response.contentAsString).get("batchId").asLong()

        // Access by otherUser must return 404
        val otherUserDetails = AppUserDetails(otherUser.id!!, otherUser.username, otherUser.passwordHash)
        mockMvc
            .perform(
                get("/api/activities/import/batches/$batchId")
                    .with(user(otherUserDetails)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `uploading multisport triathlon FIT file imports each recorded session as an individual activity`() {
        val triathlonBytes = createTriathlonFitBytes(deviceSerial = 987654L)
        val file = MockMultipartFile("files", "triathlon.fit", "application/octet-stream", triathlonBytes)

        val post =
            mockMvc
                .perform(multipart("/api/activities/import").file(file).with(csrf()))
                .andExpect(status().isAccepted)
                .andReturn()
        val batchId = objectMapper.readTree(post.response.contentAsString).get("batchId").asLong()

        val summary = awaitBatchCompletion(batchId)
        assertEquals("COMPLETED", summary.get("status").asString())
        assertEquals(1, summary.get("totalFiles").asInt())
        assertEquals(1, summary.get("processedFiles").asInt())
        assertEquals(1, summary.get("successfulFiles").asInt())
        assertEquals(0, summary.get("duplicateFiles").asInt())
        assertEquals(0, summary.get("failedFiles").asInt())

        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(3, activities.size, "Triathlon file must produce 3 distinct individual activities")

        val sports = activities.map { it.sport }.toSet()
        assertTrue(sports.contains(ActivitySport.SWIM), "Must contain swim session")
        assertTrue(sports.contains(ActivitySport.RIDE), "Must contain bike session")
        assertTrue(sports.contains(ActivitySport.RUN), "Must contain run session")

        val swim = activities.first { it.sport == ActivitySport.SWIM }
        assertEquals(1.5, swim.distance, 0.01)
        assertEquals(1800, swim.timeTakenSeconds)

        val bike = activities.first { it.sport == ActivitySport.RIDE }
        assertEquals(40.0, bike.distance, 0.01)
        assertEquals(3600, bike.timeTakenSeconds)

        val run = activities.first { it.sport == ActivitySport.RUN }
        assertEquals(10.0, run.distance, 0.01)
        assertEquals(2400, run.timeTakenSeconds)

        // Verify telemetry was saved for each session
        for (activity in activities) {
            val telemetryJson = activityDetailsRepository.findTelemetryJson(activity.id!!, testUser.id!!)
            assertNotNull(telemetryJson, "Telemetry must be persisted for ${activity.sport}")
            assertTrue(telemetryJson!!.contains("\"t\":"), "Telemetry must have timeline for ${activity.sport}")
        }

        // Verify that the batch item tracks all 3 resulting activities
        val batchItem = importBatchItemRepository.findByBatchId(batchId).single()
        assertEquals(3, batchItem.activities.size, "Batch item must track all 3 resulting multisport activities")

        // Re-uploading identical triathlon file should mark it DUPLICATE and not insert new activities
        val file2 = MockMultipartFile("files", "triathlon.fit", "application/octet-stream", triathlonBytes)
        val post2 =
            mockMvc
                .perform(multipart("/api/activities/import").file(file2).with(csrf()))
                .andExpect(status().isAccepted)
                .andReturn()
        val batchId2 = objectMapper.readTree(post2.response.contentAsString).get("batchId").asLong()

        val summary2 = awaitBatchCompletion(batchId2)
        assertEquals(1, summary2.get("duplicateFiles").asInt())
        assertEquals(0, summary2.get("successfulFiles").asInt())
        assertEquals(
            3,
            activityRepository.findByUserId(testUser.id!!).size,
            "Re-uploading duplicate triathlon must preserve existing activities",
        )
    }

    @Test
    fun `uploading stationary indoor activity without GPS records telemetry and persists with null route geometry`() {
        val indoorBytes = createStationaryIndoorFitBytes(deviceSerial = 334455L)
        val file = MockMultipartFile("files", "indoor_ride.fit", "application/octet-stream", indoorBytes)

        val post =
            mockMvc
                .perform(multipart("/api/activities/import").file(file).with(csrf()))
                .andExpect(status().isAccepted)
                .andReturn()
        val batchId = objectMapper.readTree(post.response.contentAsString).get("batchId").asLong()

        val summary = awaitBatchCompletion(batchId)
        assertEquals("COMPLETED", summary.get("status").asString())
        assertEquals(1, summary.get("successfulFiles").asInt())

        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(1, activities.size, "Indoor activity must be persisted")
        val activity = activities[0]
        assertEquals(ActivitySport.RIDE, activity.sport)

        val telemetryJson = activityDetailsRepository.findTelemetryJson(activity.id!!, testUser.id!!)
        assertNotNull(telemetryJson, "Indoor activity telemetry must be persisted")

        val rootNode = objectMapper.readTree(telemetryJson)
        assertTrue(rootNode.get("route").isNull, "Indoor activity route geometry must be null")

        val telemetryNode = rootNode.get("telemetry")
        assertNotNull(telemetryNode, "Telemetry object must exist")
        assertTrue(telemetryNode.get("t").isArray)
        assertTrue(telemetryNode.get("heartRate").isArray)
        assertEquals(145, telemetryNode.get("heartRate").get(0).asInt())
        assertEquals(210, telemetryNode.get("power").get(0).asInt())
        assertEquals(90, telemetryNode.get("cadence").get(0).asInt())
    }

    @Test
    fun `duplicate activity preserves original user data modifications and increments duplicateFiles counter`() {
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val file1 = MockMultipartFile("files", "sample.fit", "application/octet-stream", fitBytes)

        val post1 =
            mockMvc
                .perform(multipart("/api/activities/import").file(file1).with(csrf()))
                .andExpect(status().isAccepted)
                .andReturn()
        val batchId1 = objectMapper.readTree(post1.response.contentAsString).get("batchId").asLong()
        awaitBatchCompletion(batchId1)

        val originalActivity = activityRepository.findByUserId(testUser.id!!).single()
        val originalTitle = originalActivity.title
        val modifiedTitle = "Custom Marathon Championship 2026"
        originalActivity.title = modifiedTitle
        activityRepository.save(originalActivity)

        // Upload duplicate file in batch 2
        val file2 = MockMultipartFile("files", "sample.fit", "application/octet-stream", fitBytes)
        val post2 =
            mockMvc
                .perform(multipart("/api/activities/import").file(file2).with(csrf()))
                .andExpect(status().isAccepted)
                .andReturn()
        val batchId2 = objectMapper.readTree(post2.response.contentAsString).get("batchId").asLong()
        val summary2 = awaitBatchCompletion(batchId2)

        assertEquals("COMPLETED", summary2.get("status").asString())
        assertEquals(1, summary2.get("totalFiles").asInt())
        assertEquals(1, summary2.get("processedFiles").asInt())
        assertEquals(0, summary2.get("successfulFiles").asInt())
        assertEquals(1, summary2.get("duplicateFiles").asInt())
        assertEquals(0, summary2.get("failedFiles").asInt())

        // Verify original user data modification was NOT overwritten
        val afterActivities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(1, afterActivities.size)
        assertEquals(modifiedTitle, afterActivities[0].title, "User edited title must be preserved upon duplicate upload")

        // Verify batch item status and linkage
        val items = importBatchItemRepository.findByBatchId(batchId2)
        assertEquals(1, items.size)
        assertEquals(ImportBatchItemStatus.DUPLICATE, items[0].status)
        assertNull(items[0].errorMessage, "A DUPLICATE item carries no message")
        assertEquals(listOf(originalActivity.id), items[0].activities.map { it.id }, "Duplicate item links to the existing activity")
    }

    @Test
    fun `uploading identical files within the same batch marks duplicate item as DUPLICATE and saves exactly one activity`() {
        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val fileA = MockMultipartFile("files", "sample_a.fit", "application/octet-stream", fitBytes)
        val fileB = MockMultipartFile("files", "sample_b.fit", "application/octet-stream", fitBytes)

        val post =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(fileA)
                        .file(fileB)
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.totalFiles").value(2))
                .andReturn()

        val batchId = objectMapper.readTree(post.response.contentAsString).get("batchId").asLong()
        val summary = awaitBatchCompletion(batchId)

        assertEquals("COMPLETED", summary.get("status").asString())
        assertEquals(2, summary.get("totalFiles").asInt())
        assertEquals(2, summary.get("processedFiles").asInt())
        assertEquals(1, summary.get("successfulFiles").asInt())
        assertEquals(1, summary.get("duplicateFiles").asInt())
        assertEquals(0, summary.get("failedFiles").asInt())

        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(1, activities.size, "Exactly one activity should be created from duplicate items in the same batch")

        val items = importBatchItemRepository.findByBatchId(batchId)
        assertEquals(2, items.size)
        val statuses = items.map { it.status }
        assertTrue(statuses.contains(ImportBatchItemStatus.SUCCESS))
        assertTrue(statuses.contains(ImportBatchItemStatus.DUPLICATE))
    }

    @Test
    fun `batch with truncated, corrupt and valid files isolates faults without stopping batch and increments failedFiles`() {
        val validBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val corruptBytes = ClassPathResource("runs/corrupt_sample.fit").inputStream.readBytes()
        val truncatedBytes = validBytes.copyOf(100) // truncated FIT bytes

        val validFile = MockMultipartFile("files", "valid.fit", "application/octet-stream", validBytes)
        val corruptFile = MockMultipartFile("files", "corrupt.fit", "application/octet-stream", corruptBytes)
        val truncatedFile = MockMultipartFile("files", "truncated.fit", "application/octet-stream", truncatedBytes)

        val post =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(validFile)
                        .file(corruptFile)
                        .file(truncatedFile)
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.totalFiles").value(3))
                .andReturn()

        val batchId = objectMapper.readTree(post.response.contentAsString).get("batchId").asLong()
        val summary = awaitBatchCompletion(batchId)

        assertEquals("COMPLETED", summary.get("status").asString())
        assertEquals(3, summary.get("totalFiles").asInt())
        assertEquals(3, summary.get("processedFiles").asInt())
        assertEquals(1, summary.get("successfulFiles").asInt())
        assertEquals(0, summary.get("duplicateFiles").asInt())
        assertEquals(2, summary.get("failedFiles").asInt())

        val items = importBatchItemRepository.findByBatchId(batchId)
        assertEquals(3, items.size)

        val failedItems = items.filter { it.status == ImportBatchItemStatus.FAILED }
        assertEquals(2, failedItems.size)
        for (item in failedItems) {
            assertNotNull(item.errorMessage, "Failed item must have descriptive error message")
            assertTrue(item.errorMessage!!.isNotEmpty())
            assertTrue(item.activities.isEmpty(), "Failed item should not link to activity")
        }

        val successItems = items.filter { it.status == ImportBatchItemStatus.SUCCESS }
        assertEquals(1, successItems.size)
        assertEquals(1, successItems[0].activities.size)

        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(1, activities.size, "Valid activity must be saved despite other corrupt files")
    }

    @Test
    fun `mixed batch combining valid, triathlon, indoor, duplicate, corrupt and truncated files updates all running counters`() {
        // Pre-seed an activity from sample.fit to act as duplicate
        val sampleBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val seedFile = MockMultipartFile("files", "initial.fit", "application/octet-stream", sampleBytes)
        val seedPost =
            mockMvc
                .perform(multipart("/api/activities/import").file(seedFile).with(csrf()))
                .andExpect(status().isAccepted)
                .andReturn()
        val seedBatchId = objectMapper.readTree(seedPost.response.contentAsString).get("batchId").asLong()
        awaitBatchCompletion(seedBatchId)
        assertEquals(1, activityRepository.findByUserId(testUser.id!!).size)

        // Prepare mixed batch of 5 items:
        // 1. duplicate (sample.fit)
        // 2. triathlon.fit (multisport - 3 activities)
        // 3. indoor.fit (indoor - 1 activity, null route)
        // 4. corrupt.fit (corrupt sample)
        // 5. truncated.fit (truncated sample)
        val duplicateFile = MockMultipartFile("files", "duplicate.fit", "application/octet-stream", sampleBytes)
        val triathlonFile =
            MockMultipartFile("files", "triathlon.fit", "application/octet-stream", createTriathlonFitBytes(deviceSerial = 778899L))
        val indoorFile =
            MockMultipartFile("files", "indoor.fit", "application/octet-stream", createStationaryIndoorFitBytes(deviceSerial = 445566L))
        val corruptFile =
            MockMultipartFile(
                "files",
                "corrupt.fit",
                "application/octet-stream",
                ClassPathResource("runs/corrupt_sample.fit").inputStream.readBytes(),
            )
        val truncatedFile = MockMultipartFile("files", "truncated.fit", "application/octet-stream", sampleBytes.copyOf(120))

        val post =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(duplicateFile)
                        .file(triathlonFile)
                        .file(indoorFile)
                        .file(corruptFile)
                        .file(truncatedFile)
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.totalFiles").value(5))
                .andReturn()

        val batchId = objectMapper.readTree(post.response.contentAsString).get("batchId").asLong()
        val summary = awaitBatchCompletion(batchId)

        assertEquals("COMPLETED", summary.get("status").asString())
        assertEquals(5, summary.get("totalFiles").asInt())
        assertEquals(5, summary.get("processedFiles").asInt())
        assertEquals(2, summary.get("successfulFiles").asInt(), "Triathlon and indoor files must succeed")
        assertEquals(1, summary.get("duplicateFiles").asInt(), "Re-uploaded file must be marked duplicate")
        assertEquals(2, summary.get("failedFiles").asInt(), "Corrupt and truncated files must be marked failed")

        // Total activities in DB: 1 (pre-seeded) + 3 (triathlon) + 1 (indoor) = 5
        val allActivities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(5, allActivities.size, "Database should contain exactly 5 activities")

        val batchDir = File(tempDirProperty, batchId.toString())
        assertFalse(batchDir.exists(), "Temporary batch directory must be purged upon finalization")
    }

    private fun createTriathlonFitBytes(
        deviceSerial: Long = 987654L,
        startTime: Instant = Instant.parse("2026-06-01T08:00:00Z"),
    ): ByteArray {
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)
        encoder.write(
            FileIdMesg().apply {
                type = FitFile.ACTIVITY
                manufacturer = 1
                product = 1
                serialNumber = deviceSerial
                timeCreated = DateTime(startTime)
            },
        )

        encoder.write(
            ActivityMesg().apply {
                timestamp = DateTime(startTime)
                localTimestamp = DateTime(startTime).timestamp
                numSessions = 3
            },
        )

        // Swim session: 1500m, 1800s
        for (sec in 0..5) {
            encoder.write(
                RecordMesg().apply {
                    timestamp = DateTime(startTime.plusSeconds(sec.toLong()))
                    heartRate = (130 + sec).toShort()
                    distance = sec * 1.5f
                    speed = 1.5f
                    positionLat = ((51.5100 + sec * 0.0001) / (180.0 / 2147483648.0)).toInt()
                    positionLong = ((-0.1600 + sec * 0.0001) / (180.0 / 2147483648.0)).toInt()
                },
            )
        }
        encoder.write(
            SessionMesg().apply {
                sport = Sport.SWIMMING
                this.startTime = DateTime(startTime)
                timestamp = DateTime(startTime.plusSeconds(1800))
                totalDistance = 1500f
                totalTimerTime = 1800f
                totalElapsedTime = 1800f
                enhancedAvgSpeed = 0.833f
                avgHeartRate = 135.toShort()
            },
        )

        // Bike session: 40km, 3600s
        val bikeStart = startTime.plusSeconds(1860)
        for (sec in 0..5) {
            encoder.write(
                RecordMesg().apply {
                    timestamp = DateTime(bikeStart.plusSeconds(sec.toLong()))
                    heartRate = (150 + sec).toShort()
                    power = 220
                    cadence = 85.toShort()
                    distance = sec * 10f
                    speed = 10f
                    positionLat = ((51.5200 + sec * 0.0001) / (180.0 / 2147483648.0)).toInt()
                    positionLong = ((-0.1700 + sec * 0.0001) / (180.0 / 2147483648.0)).toInt()
                },
            )
        }
        encoder.write(
            SessionMesg().apply {
                sport = Sport.CYCLING
                this.startTime = DateTime(bikeStart)
                timestamp = DateTime(bikeStart.plusSeconds(3600))
                totalDistance = 40000f
                totalTimerTime = 3600f
                totalElapsedTime = 3600f
                enhancedAvgSpeed = 11.11f
                avgHeartRate = 155.toShort()
            },
        )

        // Run session: 10km, 2400s
        val runStart = bikeStart.plusSeconds(3660)
        for (sec in 0..5) {
            encoder.write(
                RecordMesg().apply {
                    timestamp = DateTime(runStart.plusSeconds(sec.toLong()))
                    heartRate = (165 + sec).toShort()
                    cadence = 175.toShort()
                    distance = sec * 4f
                    speed = 4f
                    positionLat = ((51.5300 + sec * 0.0001) / (180.0 / 2147483648.0)).toInt()
                    positionLong = ((-0.1800 + sec * 0.0001) / (180.0 / 2147483648.0)).toInt()
                },
            )
        }
        encoder.write(
            SessionMesg().apply {
                sport = Sport.RUNNING
                this.startTime = DateTime(runStart)
                timestamp = DateTime(runStart.plusSeconds(2400))
                totalDistance = 10000f
                totalTimerTime = 2400f
                totalElapsedTime = 2400f
                enhancedAvgSpeed = 4.16f
                avgHeartRate = 170.toShort()
            },
        )

        return encoder.close()
    }

    private fun createStationaryIndoorFitBytes(
        deviceSerial: Long = 112233L,
        startTime: Instant = Instant.parse("2026-06-01T18:00:00Z"),
    ): ByteArray {
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)
        encoder.write(
            FileIdMesg().apply {
                type = FitFile.ACTIVITY
                manufacturer = 1
                product = 1
                serialNumber = deviceSerial
                timeCreated = DateTime(startTime)
            },
        )

        encoder.write(
            ActivityMesg().apply {
                timestamp = DateTime(startTime)
                localTimestamp = DateTime(startTime).timestamp
                numSessions = 1
            },
        )

        for (sec in 0..5) {
            encoder.write(
                RecordMesg().apply {
                    timestamp = DateTime(startTime.plusSeconds(sec.toLong()))
                    heartRate = (145 + sec).toShort()
                    power = 210 + sec * 5
                    cadence = 90.toShort()
                    speed = 8.0f
                    distance = sec * 8.0f
                    // positionLat and positionLong are null (no GPS)
                },
            )
        }

        encoder.write(
            SessionMesg().apply {
                sport = Sport.CYCLING
                this.startTime = DateTime(startTime)
                timestamp = DateTime(startTime.plusSeconds(1800))
                totalDistance = 15000f
                totalTimerTime = 1800f
                totalElapsedTime = 1800f
                enhancedAvgSpeed = 8.33f
                avgHeartRate = 145.toShort()
            },
        )

        return encoder.close()
    }

    private fun awaitBatchCompletion(
        batchId: Long,
        timeout: Duration = Duration.ofSeconds(10),
    ): JsonNode {
        val deadline = Instant.now().plus(timeout)
        var lastJson: JsonNode? = null
        while (Instant.now().isBefore(deadline)) {
            val result =
                mockMvc
                    .perform(get("/api/activities/import/batches/$batchId"))
                    .andExpect(status().isOk)
                    .andReturn()

            val json = objectMapper.readTree(result.response.contentAsString)
            lastJson = json
            val status = json.get("status")?.asString()
            if (status == "COMPLETED" || status == "FAILED" || status == "CANCELLED" || status == "INTERRUPTED") {
                return json
            }
            Thread.sleep(100)
        }
        throw AssertionError("Batch $batchId did not complete within $timeout. Last response: $lastJson")
    }

    @Test
    fun `mixed batch with SUCCESS, UPGRADED, DUPLICATE, and FAILED items updates counters, polling response, and import history`() {
        val t1 = Instant.parse("2026-07-01T08:00:00Z")
        val t2 = Instant.parse("2026-07-02T08:00:00Z")
        val t3 = Instant.parse("2026-07-03T08:00:00Z")

        val fit1Bytes = createStationaryIndoorFitBytes(deviceSerial = 101L, startTime = t1)
        val fit2Bytes = createStationaryIndoorFitBytes(deviceSerial = 102L, startTime = t2)
        val fit3Bytes = createStationaryIndoorFitBytes(deviceSerial = 103L, startTime = t3)
        val corruptBytes = "NOT A VALID FIT FILE".toByteArray()

        // 1. Pre-seed activity for fit1 (to be DUPLICATE)
        val ext1 = fitActivityParser.parse(ByteArrayResource(fit1Bytes)).first().externalId!!
        val act1 =
            activityRepository.save(
                com.jacobcraig.stridehearth.activities.Activity(
                    title = "Existing Duplicate Run",
                    sport = ActivitySport.RUN,
                    source = com.jacobcraig.stridehearth.activities.ActivitySource.FIT_UPLOAD,
                    externalId = ext1,
                    startedAt = t1,
                    distance = 5.0,
                    timeTakenSeconds = 1800,
                    user = testUser,
                ),
            )
        // Add activity_details with distance at current parser version so it's a pure duplicate
        // (stored details lacking distance would be distance backfilled, even a one-sample series)
        activityDetailsRepository.upsert(
            act1.id!!,
            com.jacobcraig.stridehearth.system.fit.FitTelemetry(
                t = intArrayOf(0),
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOf(0),
                distance = arrayOf(0.0f),
            ),
            com.jacobcraig.stridehearth.system.fit.FitActivityParser.PARSER_VERSION,
        )

        // 2. Pre-seed activity for fit2 with older parser version = 0 (to be UPGRADED)
        val ext2 = fitActivityParser.parse(ByteArrayResource(fit2Bytes)).first().externalId!!
        val act2 =
            activityRepository.save(
                com.jacobcraig.stridehearth.activities.Activity(
                    title = "Pre-existing Run To Upgrade",
                    sport = ActivitySport.RIDE,
                    source = com.jacobcraig.stridehearth.activities.ActivitySource.FIT_UPLOAD,
                    externalId = ext2,
                    startedAt = t2,
                    distance = 5.0,
                    timeTakenSeconds = 1800,
                    user = testUser,
                ),
            )
        activityDetailsRepository.upsert(
            act2.id!!,
            com.jacobcraig.stridehearth.system.fit.FitTelemetry(
                t = intArrayOf(0),
                lons = doubleArrayOf(),
                lats = doubleArrayOf(),
                routeIndex = arrayOf(0),
            ),
            ParserVersion(0),
        )

        // 3. Upload all 4 files in a single batch
        val file1 = MockMultipartFile("files", "file1_dup.fit", "application/octet-stream", fit1Bytes)
        val file2 = MockMultipartFile("files", "file2_upg.fit", "application/octet-stream", fit2Bytes)
        val file3 = MockMultipartFile("files", "file3_new.fit", "application/octet-stream", fit3Bytes)
        val file4 = MockMultipartFile("files", "file4_corrupt.fit", "application/octet-stream", corruptBytes)

        val uploadResult =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(file1)
                        .file(file2)
                        .file(file3)
                        .file(file4)
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andReturn()

        val receipt = objectMapper.readTree(uploadResult.response.contentAsString)
        val batchId = receipt.get("batchId").asLong()

        // 4. Await batch completion
        val finalJson = awaitBatchCompletion(batchId)
        assertEquals("COMPLETED", finalJson.get("status").asString())
        assertEquals(4, finalJson.get("totalFiles").asInt())
        assertEquals(4, finalJson.get("processedFiles").asInt())
        assertEquals(1, finalJson.get("successfulFiles").asInt())
        assertEquals(1, finalJson.get("upgradedFiles").asInt())
        assertEquals(1, finalJson.get("duplicateFiles").asInt())
        assertEquals(1, finalJson.get("failedFiles").asInt())

        // 5. Verify polling endpoint itemUpgrades
        val itemUpgrades = finalJson.get("itemUpgrades")
        assertNotNull(itemUpgrades)
        assertEquals(1, itemUpgrades.size())
        assertEquals("file2_upg.fit", itemUpgrades[0].get("filename").asString())
        val changes = itemUpgrades[0].get("changes")
        assertEquals(1, changes.size())
        assertEquals("UPGRADED", changes[0].get("kind").asString())
        assertEquals("PARSER_VERSION_UPGRADED", changes[0].get("details").get("kind").asString())

        // 6. Verify the import history exposes the upgraded-files count and the per-file upgrade details
        mockMvc
            .perform(get("/api/activities/import/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(batchId))
            .andExpect(jsonPath("$[0].upgradedFiles").value(1))
            .andExpect(jsonPath("$[0].itemUpgrades.length()").value(1))
            .andExpect(jsonPath("$[0].itemUpgrades[0].filename").value("file2_upg.fit"))
            .andExpect(jsonPath("$[0].itemUpgrades[0].changes[0].kind").value("UPGRADED"))
            .andExpect(jsonPath("$[0].itemUpgrades[0].changes[0].details.kind").value("PARSER_VERSION_UPGRADED"))
    }

    @Test
    fun `a multisport file that creates one Activity and upgrades another is a MIXED item counted as both imported and upgraded`() {
        val triathlonBytes = createTriathlonFitBytes(deviceSerial = 246810L)
        val sessions = fitActivityParser.parse(ByteArrayResource(triathlonBytes))
        val swim = sessions.single { it.sport == ActivitySport.SWIM }
        val bike = sessions.single { it.sport == ActivitySport.RIDE }
        // The swim and bike were imported before, but the swim without any Activity Details to backfill.
        activityImportService.importParsed(listOf(swim.copy(telemetry = null), bike), testUser.id!!)
        val storedSwim = activityRepository.findByUserId(testUser.id!!).single { it.sport == ActivitySport.SWIM }

        val post =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(MockMultipartFile("files", "triathlon.fit", "application/octet-stream", triathlonBytes))
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andReturn()
        val batchId = objectMapper.readTree(post.response.contentAsString).get("batchId").asLong()

        val summary = awaitBatchCompletion(batchId)
        assertEquals(1, summary.get("processedFiles").asInt())
        assertEquals(1, summary.get("successfulFiles").asInt())
        assertEquals(1, summary.get("upgradedFiles").asInt())
        assertEquals(0, summary.get("duplicateFiles").asInt())
        assertEquals(0, summary.get("failedFiles").asInt())
        val itemUpgrades = summary.get("itemUpgrades")
        assertEquals(1, itemUpgrades.size())
        assertEquals("triathlon.fit", itemUpgrades[0].get("filename").asString())
        val changes = itemUpgrades[0].get("changes")
        assertEquals(1, changes.size())
        assertEquals("BACKFILLED", changes[0].get("details").get("kind").asString())

        val item = importBatchItemRepository.findByBatchId(batchId).single()
        assertEquals(ImportBatchItemStatus.MIXED, item.status)
        val storedRun = activityRepository.findByUserId(testUser.id!!).single { it.sport == ActivitySport.RUN }
        val storedBike = activityRepository.findByUserId(testUser.id!!).single { it.sport == ActivitySport.RIDE }
        assertEquals(
            setOf(storedRun.id, storedSwim.id, storedBike.id),
            item.activities.map { it.id }.toSet(),
            "the item links every Activity the file resolved to: created, upgraded and the unchanged duplicate",
        )

        mockMvc
            .perform(get("/api/activities/import/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(batchId))
            .andExpect(jsonPath("$[0].successfulFiles").value(1))
            .andExpect(jsonPath("$[0].upgradedFiles").value(1))
            .andExpect(jsonPath("$[0].itemUpgrades.length()").value(1))
            .andExpect(jsonPath("$[0].itemUpgrades[0].filename").value("triathlon.fit"))
    }
}
