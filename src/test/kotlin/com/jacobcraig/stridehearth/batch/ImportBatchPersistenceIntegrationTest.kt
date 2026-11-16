package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.Duration
import java.time.Instant

/** How an **Import Batch** item behaves around the database: the transaction boundary and the duplicate race. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@TestPropertySource(properties = ["stridehearth.storage.temp-dir=build/temp-test-batch-persistence"])
@WithUserDetails(value = "batch_race_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ImportBatchPersistenceIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoSpyBean
    private lateinit var activityRepository: ActivityRepository

    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    @Autowired
    private lateinit var importBatchRepository: ImportBatchRepository

    @Autowired
    private lateinit var importBatchItemRepository: ImportBatchItemRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Value("\${stridehearth.storage.temp-dir}")
    private lateinit var tempDir: String

    private lateinit var runner: User

    private val sampleFitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()

    @BeforeEach
    fun setUp() {
        runner =
            userRepository.save(User(email = "batch_race_runner@example.com", passwordHash = "x", username = "batch_race_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        importBatchItemRepository.deleteAll()
        importBatchRepository.deleteAll()
        userRepository.deleteAll()
        File(tempDir).deleteRecursively()
    }

    private fun importSampleAsBatch(): JsonNode {
        val receipt =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(MockMultipartFile("files", "sample.fit", "application/octet-stream", sampleFitBytes))
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andReturn()
        val batchId = objectMapper.readTree(receipt.response.contentAsString).get("batchId").asLong()
        return awaitBatchCompletion(batchId)
    }

    private fun awaitBatchCompletion(batchId: Long): JsonNode {
        val deadline = Instant.now().plus(Duration.ofSeconds(10))
        var last: JsonNode? = null
        while (Instant.now().isBefore(deadline)) {
            val response = mockMvc.perform(get("/api/activities/import/batches/$batchId")).andReturn().response
            last = objectMapper.readTree(response.contentAsString)
            if (last.get("status")?.asString() == "COMPLETED") return last
            Thread.sleep(100)
        }
        throw AssertionError("Batch $batchId did not complete. Last response: $last")
    }

    @Test
    fun `an item that loses the duplicate race to a concurrent import is recorded DUPLICATE and keeps one Activity`() {
        var raced = false
        doAnswer { invocation ->
            val externalId = invocation.getArgument<String>(2)
            // A spied Spring Data proxy cannot call through, so other lookups answer from the stored rows.
            if (raced) return@doAnswer activityRepository.findByUserId(runner.id!!).firstOrNull { it.externalId == externalId }
            raced = true
            // A concurrent import of the same file commits first, on its own thread and transaction.
            val concurrent = Thread { activityImportService.import(ByteArrayResource(sampleFitBytes), runner.id!!) }
            concurrent.start()
            concurrent.join()
            null
        }.whenever(activityRepository).findByUserIdAndSourceAndExternalId(eq(runner.id!!), eq(ActivitySource.FIT_UPLOAD), any())

        val summary = importSampleAsBatch()

        assertEquals(1, summary.get("duplicateFiles").asInt())
        assertEquals(0, summary.get("failedFiles").asInt())
        assertEquals(ImportBatchItemStatus.DUPLICATE, importBatchItemRepository.findAll().single().status)
        assertEquals(1, activityRepository.findByUserId(runner.id!!).size)
    }

    @Test
    fun `an item's staged file is parsed with no database transaction open`() {
        var transactionActiveDuringParse: Boolean? = null
        doAnswer { invocation ->
            transactionActiveDuringParse = TransactionSynchronizationManager.isActualTransactionActive()
            invocation.callRealMethod()
        }.whenever(fitActivityParser).parse(any())

        assertEquals(1, importSampleAsBatch().get("successfulFiles").asInt())

        assertFalse(transactionActiveDuringParse!!)
    }
}
