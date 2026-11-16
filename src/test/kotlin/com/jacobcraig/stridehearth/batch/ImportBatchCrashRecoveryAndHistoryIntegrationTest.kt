package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.users.AppUserDetails
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "stridehearth.storage.temp-dir=build/temp-test-recovery-batches",
    ],
)
@WithUserDetails(value = "recovery_user", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ImportBatchCrashRecoveryAndHistoryIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var importBatchRepository: ImportBatchRepository

    @Autowired
    private lateinit var importBatchItemRepository: ImportBatchItemRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var batchStorageService: BatchStorageService

    @Autowired
    private lateinit var applicationEventPublisher: ApplicationEventPublisher

    @Autowired
    private lateinit var applicationContext: org.springframework.context.ConfigurableApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Value("\${stridehearth.storage.temp-dir:./temp-batches}")
    private lateinit var tempDirProperty: String

    private lateinit var testUser: User
    private lateinit var otherUser: User

    @BeforeEach
    fun setUp() {
        testUser =
            userRepository.save(
                User(email = "recovery_user@example.com", passwordHash = "x", username = "recovery_user"),
            )
        otherUser =
            userRepository.save(
                User(email = "other_recovery_user@example.com", passwordHash = "x", username = "other_recovery_user"),
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
    fun `ApplicationReadyEvent listener marks PENDING and PROCESSING batches as INTERRUPTED and purges orphaned directories`() {
        // Arrange: 4 batches in different states
        val pendingBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.PENDING,
                    totalFiles = 2,
                    processedFiles = 0,
                    createdAt = Instant.now().minusSeconds(120),
                ),
            )
        val processingBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.PROCESSING,
                    totalFiles = 5,
                    processedFiles = 2,
                    successfulFiles = 2,
                    createdAt = Instant.now().minusSeconds(60),
                ),
            )
        val completedBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.COMPLETED,
                    totalFiles = 1,
                    processedFiles = 1,
                    successfulFiles = 1,
                    completedAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS),
                    createdAt = Instant.now().minusSeconds(40),
                ),
            )
        val cancelledBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.CANCELLED,
                    totalFiles = 3,
                    processedFiles = 1,
                    completedAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS),
                    createdAt = Instant.now().minusSeconds(20),
                ),
            )

        // Stage dummy directories and files simulating orphaned batch directories
        val tempDir = File(tempDirProperty)
        tempDir.mkdirs()
        val danglingBatchDir1 = File(tempDir, pendingBatch.id!!.toString())
        danglingBatchDir1.mkdirs()
        File(danglingBatchDir1, "staged1.fit").writeBytes(byteArrayOf(1, 2, 3))

        val danglingBatchDir2 = File(tempDir, processingBatch.id!!.toString())
        danglingBatchDir2.mkdirs()
        File(danglingBatchDir2, "staged2.fit").writeBytes(byteArrayOf(4, 5, 6))

        val danglingOrphanFile = File(tempDir, "upload-archive-dangling.zip")
        danglingOrphanFile.writeBytes(byteArrayOf(7, 8, 9))

        assertTrue(danglingBatchDir1.exists())
        assertTrue(danglingBatchDir2.exists())
        assertTrue(danglingOrphanFile.exists())

        // Act: trigger ApplicationReadyEvent
        val readyEvent =
            ApplicationReadyEvent(
                SpringApplication(),
                emptyArray(),
                applicationContext,
                Duration.ofSeconds(1),
            )
        applicationEventPublisher.publishEvent(readyEvent)

        // Assert: PENDING and PROCESSING batches transitioned to INTERRUPTED
        val updatedPending = importBatchRepository.findById(pendingBatch.id!!).orElseThrow()
        assertEquals(ImportBatchStatus.INTERRUPTED, updatedPending.status)
        assertEquals("Batch was interrupted by a server restart", updatedPending.errorMessage)
        assertNotNull(updatedPending.completedAt)

        val updatedProcessing = importBatchRepository.findById(processingBatch.id!!).orElseThrow()
        assertEquals(ImportBatchStatus.INTERRUPTED, updatedProcessing.status)
        assertEquals("Batch was interrupted by a server restart", updatedProcessing.errorMessage)
        assertNotNull(updatedProcessing.completedAt)

        // Assert: COMPLETED and CANCELLED batches remain untouched
        val updatedCompleted = importBatchRepository.findById(completedBatch.id!!).orElseThrow()
        assertEquals(ImportBatchStatus.COMPLETED, updatedCompleted.status)
        assertEquals(completedBatch.completedAt, updatedCompleted.completedAt)

        val updatedCancelled = importBatchRepository.findById(cancelledBatch.id!!).orElseThrow()
        assertEquals(ImportBatchStatus.CANCELLED, updatedCancelled.status)

        // Assert: Orphaned temporary directories and files are purged
        assertFalse(danglingBatchDir1.exists(), "Dangling batch directory 1 must be purged")
        assertFalse(danglingBatchDir2.exists(), "Dangling batch directory 2 must be purged")
        assertFalse(danglingOrphanFile.exists(), "Dangling archive file must be purged")
    }

    @Test
    fun `JVM shutdown hook cleans up ephemeral temporary storage files`() {
        val tempDir = File(tempDirProperty)
        tempDir.mkdirs()
        val batchDir = File(tempDir, "999")
        batchDir.mkdirs()
        val dummyFile = File(batchDir, "workout.fit")
        dummyFile.writeBytes(byteArrayOf(1, 2, 3))
        val orphanZip = File(tempDir, "temp-upload.zip")
        orphanZip.writeBytes(byteArrayOf(4, 5, 6))

        assertTrue(dummyFile.exists())
        assertTrue(orphanZip.exists())

        // Execute batch storage shutdown hook
        batchStorageService.shutdownHook.run()

        assertFalse(dummyFile.exists(), "Staged file must be cleaned up by shutdown hook")
        assertFalse(batchDir.exists(), "Batch directory must be cleaned up by shutdown hook")
        assertFalse(orphanZip.exists(), "Temporary zip must be cleaned up by shutdown hook")
    }

    @Test
    fun `GET api import history exposes batches newest first with status, breakdown, completion instants and item errors`() {
        val now = Instant.now()
        val completedBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.COMPLETED,
                    totalFiles = 4,
                    processedFiles = 4,
                    successfulFiles = 2,
                    duplicateFiles = 1,
                    failedFiles = 1,
                    createdAt = now.minusSeconds(125),
                    completedAt = now.minusSeconds(120),
                ),
            )
        importBatchItemRepository.save(
            ImportBatchItem(
                batch = completedBatch,
                originalFilename = "corrupted_lap.fit",
                stagedFilePath = "/tmp/dummy",
                status = ImportBatchItemStatus.FAILED,
                errorMessage = "Corrupt FIT header",
                processedAt = now.minusSeconds(120),
            ),
        )

        val interruptedBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.INTERRUPTED,
                    totalFiles = 10,
                    processedFiles = 3,
                    successfulFiles = 3,
                    duplicateFiles = 0,
                    failedFiles = 0,
                    errorMessage = "Batch was interrupted by a server restart",
                    createdAt = now.minusSeconds(60),
                    completedAt = now.minusSeconds(55),
                ),
            )

        mockMvc
            .perform(get("/api/activities/import/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(interruptedBatch.id!!))
            .andExpect(jsonPath("$[0].status").value("INTERRUPTED"))
            .andExpect(jsonPath("$[0].processedFiles").value(3))
            .andExpect(jsonPath("$[0].errorMessage").value("Batch was interrupted by a server restart"))
            .andExpect(jsonPath("$[1].id").value(completedBatch.id!!))
            .andExpect(jsonPath("$[1].status").value("COMPLETED"))
            .andExpect(jsonPath("$[1].successfulFiles").value(2))
            .andExpect(jsonPath("$[1].duplicateFiles").value(1))
            .andExpect(jsonPath("$[1].failedFiles").value(1))
            .andExpect(jsonPath("$[1].createdAt").isString)
            .andExpect(jsonPath("$[1].completedAt").isString)
            .andExpect(jsonPath("$[1].itemErrors[0].filename").value("corrupted_lap.fit"))
            .andExpect(jsonPath("$[1].itemErrors[0].errorMessage").value("Corrupt FIT header"))
    }

    @Test
    fun `GET api import history enforces user ownership so athletes only see their own batches`() {
        val userBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.COMPLETED,
                    totalFiles = 1,
                    processedFiles = 1,
                    successfulFiles = 1,
                    errorMessage = "testUser batch unique message",
                ),
            )

        val otherBatch =
            importBatchRepository.save(
                ImportBatch(
                    user = otherUser,
                    status = ImportBatchStatus.COMPLETED,
                    totalFiles = 1,
                    processedFiles = 1,
                    successfulFiles = 1,
                    errorMessage = "otherUser batch secret message",
                ),
            )

        // Request as testUser
        mockMvc
            .perform(get("/api/activities/import/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].id", contains(userBatch.id!!.toInt())))
            .andExpect(jsonPath("$[*].errorMessage", contains("testUser batch unique message")))

        // Request as otherUser
        val otherUserDetails = AppUserDetails(otherUser.id!!, otherUser.username, otherUser.passwordHash)
        mockMvc
            .perform(
                get("/api/activities/import/history")
                    .with(user(otherUserDetails)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$[*].id", contains(otherBatch.id!!.toInt())))
            .andExpect(jsonPath("$[*].errorMessage", contains("otherUser batch secret message")))
    }

    @Test
    fun `GET api activities import history returns JSON array of user batches with breakdown and canonical instants`() {
        val now = Instant.now()
        val batch =
            importBatchRepository.save(
                ImportBatch(
                    user = testUser,
                    status = ImportBatchStatus.COMPLETED,
                    totalFiles = 5,
                    processedFiles = 5,
                    successfulFiles = 3,
                    duplicateFiles = 1,
                    failedFiles = 1,
                    createdAt = now.minusSeconds(10),
                    completedAt = now,
                ),
            )
        importBatchItemRepository.save(
            ImportBatchItem(
                batch = batch,
                originalFilename = "bad_run.fit",
                stagedFilePath = "/tmp/dummy",
                status = ImportBatchItemStatus.FAILED,
                errorMessage = "Invalid checksum",
            ),
        )

        mockMvc
            .perform(get("/api/activities/import/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$[0].id").value(batch.id!!))
            .andExpect(jsonPath("$[0].status").value("COMPLETED"))
            .andExpect(jsonPath("$[0].totalFiles").value(5))
            .andExpect(jsonPath("$[0].processedFiles").value(5))
            .andExpect(jsonPath("$[0].successfulFiles").value(3))
            .andExpect(jsonPath("$[0].duplicateFiles").value(1))
            .andExpect(jsonPath("$[0].failedFiles").value(1))
            .andExpect(jsonPath("$[0].createdAt").isString)
            .andExpect(jsonPath("$[0].completedAt").isString)
            .andExpect(jsonPath("$[0].duration").doesNotExist())
            .andExpect(jsonPath("$[0].itemErrors[0].filename").value("bad_run.fit"))
            .andExpect(jsonPath("$[0].itemErrors[0].errorMessage").value("Invalid checksum"))
    }

    @Test
    fun `unauthenticated access to GET api import history is rejected with an UNAUTHORIZED envelope`() {
        mockMvc
            .perform(
                get("/api/activities/import/history")
                    .with(anonymous()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }
}
