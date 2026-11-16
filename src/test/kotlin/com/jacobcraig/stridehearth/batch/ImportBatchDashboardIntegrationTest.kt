package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.AppUserDetails
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.Duration
import java.time.Instant

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "stridehearth.storage.temp-dir=build/temp-test-dashboard-batches",
    ],
)
@WithUserDetails(value = "dashboard_user", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ImportBatchDashboardIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var importBatchRepository: ImportBatchRepository

    @Autowired
    private lateinit var importBatchItemRepository: ImportBatchItemRepository

    @Autowired
    private lateinit var batchStorageService: BatchStorageService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var stringRedisTemplate: org.springframework.data.redis.core.StringRedisTemplate

    @Value("\${stridehearth.storage.temp-dir:./temp-batches}")
    private lateinit var tempDirProperty: String

    private lateinit var testUser: User
    private lateinit var otherUser: User

    @BeforeEach
    fun setUp() {
        testUser =
            userRepository.save(
                User(email = "dashboard_user@example.com", passwordHash = "x", username = "dashboard_user"),
            )
        otherUser =
            userRepository.save(
                User(email = "other_dashboard_user@example.com", passwordHash = "x", username = "other_dashboard_user"),
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
    fun `unauthenticated Import Batch submission is rejected with an UNAUTHORIZED envelope and stages nothing`() {
        val file = MockMultipartFile("files", "sample.fit", "application/octet-stream", ByteArray(16))
        mockMvc
            .perform(
                multipart("/api/activities/import")
                    .file(file)
                    .with(csrf())
                    .with(anonymous()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))

        assertEquals(0, importBatchRepository.count())
    }

    @Test
    fun `GET api activities import batches id returns real-time progress and item error messages for failed items`() {
        val corruptBytes = ClassPathResource("runs/corrupt_sample.fit").inputStream.readBytes()
        val corruptFile = MockMultipartFile("files", "corrupted_run.fit", "application/octet-stream", corruptBytes)

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
        assertEquals(1, summary.get("failedFiles").asInt())

        // Verify item error messages are included in polling response
        val itemErrors = summary.get("itemErrors")
        assertNotNull(itemErrors, "itemErrors should be present in polling response")
        assertTrue(itemErrors.isArray && itemErrors.size() == 1)
        assertEquals("corrupted_run.fit", itemErrors.get(0).get("filename").asString())
        assertTrue(
            itemErrors
                .get(0)
                .get("errorMessage")
                .asString()
                .isNotBlank(),
        )

        assertFalse(summary.has("errors"), "Preformatted error strings are not part of the canonical contract")
    }

    @Test
    fun `POST api activities import batches id cancel transitions batch to CANCELLED and purges staged files`() {
        // Prepare an existing saved activity for testUser
        val existingActivity =
            activityRepository.save(
                Activity(
                    title = "Preserved Morning Run",
                    sport = ActivitySport.RUN,
                    startedAt = Instant.now(),
                    distance = 5.0,
                    timeTakenSeconds = 1800,
                    user = testUser,
                    source = ActivitySource.FIT_UPLOAD,
                    externalId = "preserved-1",
                ),
            )

        // Create a batch in PENDING / PROCESSING state manually to simulate in-flight batch
        val batch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.PROCESSING,
                    totalFiles = 3,
                    processedFiles = 1,
                    successfulFiles = 1,
                ),
            )

        // Stage dummy files for this batch
        val stagedDir = batchStorageService.getBatchDirectory(batch.id!!)
        java.nio.file.Files
            .createDirectories(stagedDir)
        val dummyFile = stagedDir.resolve("dummy.fit")
        java.nio.file.Files
            .write(dummyFile, byteArrayOf(1, 2, 3))
        assertTrue(dummyFile.toFile().exists(), "Dummy staged file should exist before cancellation")

        // Create items: 1 completed, 2 pending
        importBatchItemRepository.save(
            ImportBatchItem(
                batch = batch,
                originalFilename = "completed.fit",
                stagedFilePath = dummyFile.toString(),
                status = ImportBatchItemStatus.SUCCESS,
                activities = listOf(existingActivity),
                processedAt = Instant.now(),
            ),
        )
        val pendingItem1 =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = batch,
                    originalFilename = "pending1.fit",
                    stagedFilePath = dummyFile.toString(),
                    status = ImportBatchItemStatus.PENDING,
                ),
            )
        val pendingItem2 =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = batch,
                    originalFilename = "pending2.fit",
                    stagedFilePath = dummyFile.toString(),
                    status = ImportBatchItemStatus.PENDING,
                ),
            )

        // Cancel the batch via POST /api/activities/import/batches/{id}/cancel
        val cancelResult =
            mockMvc
                .perform(
                    post("/api/activities/import/batches/${batch.id}/cancel")
                        .with(csrf()),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.id").value(batch.id!!))
                .andReturn()

        val cancelJson = objectMapper.readTree(cancelResult.response.contentAsString)
        assertEquals("CANCELLED", cancelJson.get("status").asString())

        // Verify batch state in database
        val updatedBatch = importBatchRepository.findById(batch.id!!).orElseThrow()
        assertEquals(ImportBatchStatus.CANCELLED, updatedBatch.status)
        assertNotNull(updatedBatch.completedAt)

        // Verify staged batch directory was purged
        assertFalse(stagedDir.toFile().exists(), "Batch directory must be purged on cancellation")

        // Verify already saved activity is preserved
        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(1, activities.size, "Committed activities must remain intact")
        assertEquals("Preserved Morning Run", activities[0].title)

        // Verify pending items remain unprocessed
        val refetchedPending1 = importBatchItemRepository.findById(pendingItem1.id!!).orElseThrow()
        val refetchedPending2 = importBatchItemRepository.findById(pendingItem2.id!!).orElseThrow()
        assertEquals(ImportBatchItemStatus.PENDING, refetchedPending1.status)
        assertEquals(ImportBatchItemStatus.PENDING, refetchedPending2.status)
    }

    @Test
    fun `the removed non-api Import Batch cancel alias no longer cancels anything`() {
        val batch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.PROCESSING,
                    totalFiles = 1,
                ),
            )

        mockMvc
            .perform(
                post("/activities/import/batches/${batch.id}/cancel")
                    .with(csrf()),
            ).andExpect(status().is4xxClientError)

        val unchanged = importBatchRepository.findById(batch.id!!).orElseThrow()
        assertEquals(ImportBatchStatus.PROCESSING, unchanged.status)
    }

    @Test
    fun `cancel endpoint on non-existent batch or another user's batch returns 404`() {
        mockMvc
            .perform(
                post("/api/activities/import/batches/999999/cancel")
                    .with(csrf()),
            ).andExpect(status().isNotFound)

        val batch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.PROCESSING,
                    totalFiles = 1,
                ),
            )

        val otherUserDetails = AppUserDetails(otherUser.id!!, otherUser.username, otherUser.passwordHash)
        mockMvc
            .perform(
                post("/api/activities/import/batches/${batch.id}/cancel")
                    .with(user(otherUserDetails))
                    .with(csrf()),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `worker skips pending stream items for cancelled batch without creating activities`() {
        val batch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.CANCELLED,
                    totalFiles = 1,
                ),
            )

        val fitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()
        val stagedPath = batchStorageService.stageFile(batch.id!!, "sample.fit", fitBytes.inputStream())

        val item =
            importBatchItemRepository.save(
                ImportBatchItem(
                    batch = batch,
                    originalFilename = "sample.fit",
                    stagedFilePath = stagedPath.toString(),
                    status = ImportBatchItemStatus.PENDING,
                ),
            )

        val record =
            org.springframework.data.redis.connection.stream.StreamRecords
                .string(
                    mapOf(
                        "batchId" to batch.id!!.toString(),
                        "itemId" to item.id!!.toString(),
                        "stagedFilePath" to item.stagedFilePath,
                    ),
                ).withStreamKey("stridehearth-import-stream")

        stringRedisTemplate.opsForStream<String, String>().add(record)

        Thread.sleep(1000)

        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(0, activities.size, "No activity should be created for items of cancelled batch")

        val refetchedItem = importBatchItemRepository.findById(item.id!!).orElseThrow()
        assertEquals(ImportBatchItemStatus.PENDING, refetchedItem.status)
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
}
