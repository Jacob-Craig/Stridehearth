package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityDetailsRepository
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.fit.FitFileRepairer
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
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
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@Import(TestcontainersConfiguration::class, ImportBatchRepairIntegrationTest.TestRepairConfig::class)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "stridehearth.storage.temp-dir=build/temp-test-batches-repair",
    ],
)
@WithUserDetails(value = "repair_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ImportBatchRepairIntegrationTest {
    @TestConfiguration
    class TestRepairConfig {
        companion object {
            val repairCalls = AtomicInteger(0)
        }

        @Bean
        @Primary
        fun testFitFileRepairer(): FitFileRepairer {
            val sampleBytes = ClassPathResource("runs/sample.fit").inputStream.use { it.readAllBytes() }
            return FitFileRepairer { bytes ->
                repairCalls.incrementAndGet()
                sampleBytes
            }
        }
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var activityDetailsRepository: ActivityDetailsRepository

    @Autowired
    private lateinit var importBatchRepository: ImportBatchRepository

    @Autowired
    private lateinit var importBatchItemRepository: ImportBatchItemRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Value("\${stridehearth.storage.temp-dir:build/temp-test-batches-repair}")
    private lateinit var tempDirProperty: String

    private lateinit var testUser: User

    @BeforeEach
    fun setUp() {
        TestRepairConfig.repairCalls.set(0)
        testUser =
            userRepository.save(
                User(email = "repair_runner@example.com", passwordHash = "x", username = "repair_runner"),
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
    fun `damaged but repairable FIT file in import batch is repaired and imported as SUCCESS`() {
        val corruptBytes = ClassPathResource("runs/corrupt_sample.fit").inputStream.readBytes()
        val corruptFile = MockMultipartFile("files", "corrupt_sample.fit", "application/octet-stream", corruptBytes)

        val postResult =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(corruptFile)
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

        // Verify that the repairer bean was indeed invoked
        assertTrue(TestRepairConfig.repairCalls.get() > 0, "Repairer should be invoked for corrupt_sample.fit")

        // Verify batch item status
        val items = importBatchItemRepository.findByBatchId(batchId)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(ImportBatchItemStatus.SUCCESS, item.status)
        assertEquals(1, item.activities.size, "Item should link its activity")
        assertNull(item.errorMessage, "Successful item should have no error message")

        // Verify activity persisted
        val activities = activityRepository.findByUserId(testUser.id!!)
        assertEquals(1, activities.size, "Exactly one activity should be persisted for user")
        val activity = activities[0]
        assertEquals(ActivitySport.RUN, activity.sport)
        assertEquals(ActivitySource.FIT_UPLOAD, activity.source)
        assertTrue(activity.title.endsWith("Run"))

        // Verify telemetry persisted
        val telemetryJson = activityDetailsRepository.findTelemetryJson(activity.id!!, testUser.id!!)
        assertNotNull(telemetryJson, "Activity details telemetry should be persisted")
        assertTrue(telemetryJson!!.contains("\"t\":"), "Telemetry JSON must contain timeline")

        // Verify batch temp directory cleanup
        val batchDir = File(tempDirProperty, batchId.toString())
        assertFalse(batchDir.exists(), "Batch directory should be deleted upon batch finalization")
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
