package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.LapsUpgrade
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Contract tests for the `/api/activities/import` JSON endpoints consumed by the React SPA's
 * **Import Batch** uploader, live progress dashboard, and import history.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
// /v3/api-docs is served only with the `dev` profile (ADR-0009); these tests read it, so they switch it on.
@TestPropertySource(
    properties = [
        "stridehearth.storage.temp-dir=build/temp-test-api-batches",
        "springdoc.api-docs.enabled=true",
    ],
)
@WithUserDetails(value = "api_importer", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ImportBatchApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var importBatchRepository: ImportBatchRepository

    @Autowired
    private lateinit var importBatchItemRepository: ImportBatchItemRepository

    @Autowired
    private lateinit var activityHistory: ActivityHistory

    @Value("\${stridehearth.storage.temp-dir:./temp-batches}")
    private lateinit var tempDirProperty: String

    private lateinit var importer: User
    private lateinit var otherAthlete: User

    @BeforeEach
    fun setUp() {
        importer = userRepository.save(User(email = "api_importer@example.com", passwordHash = "x", username = "api_importer"))
        otherAthlete = userRepository.save(User(email = "api_other@example.com", passwordHash = "x", username = "api_other"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        importBatchItemRepository.deleteAll()
        importBatchRepository.deleteAll()
        userRepository.deleteAll()
        File(tempDirProperty).deleteRecursively()
    }

    private fun saveBatch(
        owner: User,
        status: ImportBatchStatus,
        createdAt: Instant,
        completedAt: Instant? = null,
        totalFiles: Int = 4,
        processedFiles: Int = 0,
        successfulFiles: Int = 0,
        duplicateFiles: Int = 0,
        upgradedFiles: Int = 0,
        failedFiles: Int = 0,
    ): ImportBatch =
        importBatchRepository.save(
            ImportBatch(
                user = owner,
                status = status,
                totalFiles = totalFiles,
                processedFiles = processedFiles,
                successfulFiles = successfulFiles,
                duplicateFiles = duplicateFiles,
                upgradedFiles = upgradedFiles,
                failedFiles = failedFiles,
                createdAt = createdAt,
                completedAt = completedAt,
            ),
        )

    /** An item whose [message] is a FAILED item's error, or an upgraded item's legacy free-text upgrade. */
    private fun saveItem(
        batch: ImportBatch,
        filename: String,
        status: ImportBatchItemStatus,
        message: String?,
        upgrades: List<ActivityChange.Upgraded>? = null,
    ) {
        importBatchItemRepository.save(
            ImportBatchItem(
                batch = batch,
                originalFilename = filename,
                stagedFilePath = "/tmp/$filename",
                status = status,
                errorMessage = message.takeIf { status in ImportBatchItemStatus.WITH_ERRORS },
                upgradeMessage = message.takeIf { status in ImportBatchItemStatus.WITH_UPGRADES },
                upgrades = upgrades?.let(activityHistory::encodeItemUpgrades),
            ),
        )
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * A submitted Import Batch is processed by the real Redis Stream worker on another thread.
     * Waits until it is no longer active, so the worker cannot write rows after [tearDown] deletes them.
     */
    private fun awaitSettled(receipt: MvcResult) {
        val batchId = JsonPath.read<Number>(receipt.response.contentAsString, "$.batchId").toLong()
        val deadline = Instant.now().plusSeconds(30)
        while (importBatchRepository.findById(batchId).map { it.status in ImportBatchStatus.ACTIVE }.orElse(false)) {
            check(Instant.now().isBefore(deadline)) { "Import Batch $batchId did not settle within 30 seconds" }
            Thread.sleep(50)
        }
    }

    private val sampleFit: ByteArray get() = ClassPathResource("runs/sample.fit").inputStream.readBytes()

    @Test
    fun `submitting a loose FIT file accepts an Import Batch receipt`() {
        val receipt =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(MockMultipartFile("files", "sample.fit", "application/octet-stream", sampleFit))
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.batchId").isNumber)
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.totalFiles").value(1))
                .andReturn()
        awaitSettled(receipt)
    }

    @Test
    fun `submitting a ZIP archive counts nested FIT entries and ignores OS metadata`() {
        val archive =
            zipOf(
                mapOf(
                    "2026/march/morning.fit" to sampleFit,
                    "__MACOSX/2026/march/._morning.fit" to byteArrayOf(0),
                    "2026/notes.txt" to "hello".toByteArray(),
                ),
            )

        val receipt =
            mockMvc
                .perform(
                    multipart("/api/activities/import")
                        .file(MockMultipartFile("files", "export.zip", "application/zip", archive))
                        .with(csrf()),
                ).andExpect(status().isAccepted)
                .andExpect(jsonPath("$.totalFiles").value(1))
                .andReturn()
        awaitSettled(receipt)
    }

    @Test
    fun `submitting without any files is rejected with a BAD_REQUEST envelope`() {
        mockMvc
            .perform(multipart("/api/activities/import").with(csrf()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").isNotEmpty)
    }

    @Test
    fun `submitting a ZIP archive without FIT entries is rejected with a BAD_REQUEST envelope`() {
        val archive = zipOf(mapOf("readme.txt" to "nothing here".toByteArray()))

        mockMvc
            .perform(
                multipart("/api/activities/import")
                    .file(MockMultipartFile("files", "useless.zip", "application/zip", archive))
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("No valid .fit files found in upload"))
    }

    @Test
    fun `polling an Import Batch returns its summary as canonical primitives`() {
        val batch =
            saveBatch(
                importer,
                ImportBatchStatus.PROCESSING,
                createdAt = Instant.parse("2026-03-01T10:00:00Z"),
                processedFiles = 3,
                successfulFiles = 1,
                duplicateFiles = 1,
                failedFiles = 1,
            )
        saveItem(batch, "broken.fit", ImportBatchItemStatus.FAILED, "Invalid checksum")

        mockMvc
            .perform(get("/api/activities/import/batches/${batch.id}").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(batch.id!!))
            .andExpect(jsonPath("$.status").value("PROCESSING"))
            .andExpect(jsonPath("$.totalFiles").value(4))
            .andExpect(jsonPath("$.processedFiles").value(3))
            .andExpect(jsonPath("$.successfulFiles").value(1))
            .andExpect(jsonPath("$.duplicateFiles").value(1))
            .andExpect(jsonPath("$.upgradedFiles").value(0))
            .andExpect(jsonPath("$.failedFiles").value(1))
            .andExpect(jsonPath("$.createdAt").value("2026-03-01T10:00:00Z"))
            .andExpect(jsonPath("$.completedAt").value(nullValue()))
            .andExpect(jsonPath("$.errorMessage").value(nullValue()))
            .andExpect(jsonPath("$.itemErrors", hasSize<Any>(1)))
            .andExpect(jsonPath("$.itemErrors[0].filename").value("broken.fit"))
            .andExpect(jsonPath("$.itemErrors[0].errorMessage").value("Invalid checksum"))
            .andExpect(jsonPath("$.itemUpgrades", hasSize<Any>(0)))
            .andExpect(jsonPath("$.duration").doesNotExist())
            .andExpect(jsonPath("$.errors").doesNotExist())
    }

    @Test
    fun `polling another athlete's Import Batch returns a RESOURCE_NOT_FOUND envelope`() {
        val foreign = saveBatch(otherAthlete, ImportBatchStatus.PROCESSING, createdAt = Instant.now())

        mockMvc
            .perform(get("/api/activities/import/batches/${foreign.id}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `cancelling an active Import Batch marks it CANCELLED with a completion instant`() {
        val batch = saveBatch(importer, ImportBatchStatus.PROCESSING, createdAt = Instant.now())

        mockMvc
            .perform(post("/api/activities/import/batches/${batch.id}/cancel").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(batch.id!!))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.completedAt").isString)
    }

    @Test
    fun `cancelling a finished Import Batch leaves its terminal status unchanged`() {
        val batch =
            saveBatch(
                importer,
                ImportBatchStatus.COMPLETED,
                createdAt = Instant.parse("2026-03-01T10:00:00Z"),
                completedAt = Instant.parse("2026-03-01T10:00:12Z"),
            )

        mockMvc
            .perform(post("/api/activities/import/batches/${batch.id}/cancel").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.completedAt").value("2026-03-01T10:00:12Z"))
    }

    @Test
    fun `cancelling an unknown Import Batch returns a RESOURCE_NOT_FOUND envelope`() {
        mockMvc
            .perform(post("/api/activities/import/batches/987654/cancel").with(csrf()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.kind").value("RESOURCE_NOT_FOUND"))
    }

    @Test
    fun `import history lists the athlete's Import Batches newest first with item errors and Parser Version upgrades`() {
        val older =
            saveBatch(
                importer,
                ImportBatchStatus.COMPLETED,
                createdAt = Instant.parse("2026-02-01T08:00:00Z"),
                completedAt = Instant.parse("2026-02-01T08:01:05Z"),
                totalFiles = 3,
                processedFiles = 3,
                successfulFiles = 1,
                upgradedFiles = 3,
                failedFiles = 1,
            )
        saveItem(older, "bad.fit", ImportBatchItemStatus.FAILED, "Truncated file")
        saveItem(older, "old.fit", ImportBatchItemStatus.UPGRADED, "Upgraded Parser Version 1 -> 2")
        saveItem(
            older,
            "typed.fit",
            ImportBatchItemStatus.MIXED,
            message = null,
            upgrades =
                listOf(
                    ActivityChange.Upgraded(null, LapsUpgrade.Backfilled(2)),
                    ActivityChange.Upgraded(null, LapsUpgrade.Replaced(1)),
                ),
        )
        saveItem(older, "bare.fit", ImportBatchItemStatus.UPGRADED, message = null)
        val newer =
            saveBatch(
                importer,
                ImportBatchStatus.CANCELLED,
                createdAt = Instant.parse("2026-03-01T08:00:00Z"),
                completedAt = Instant.parse("2026-03-01T08:00:02Z"),
            )
        saveBatch(otherAthlete, ImportBatchStatus.COMPLETED, createdAt = Instant.parse("2026-04-01T08:00:00Z"))

        mockMvc
            .perform(get("/api/activities/import/history").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$", hasSize<Any>(2)))
            .andExpect(jsonPath("$[*].id", contains(newer.id!!.toInt(), older.id!!.toInt())))
            .andExpect(jsonPath("$[0].status").value("CANCELLED"))
            .andExpect(jsonPath("$[0].itemErrors", hasSize<Any>(0)))
            .andExpect(jsonPath("$[1].createdAt").value("2026-02-01T08:00:00Z"))
            .andExpect(jsonPath("$[1].completedAt").value("2026-02-01T08:01:05Z"))
            .andExpect(jsonPath("$[1].itemErrors[0].filename").value("bad.fit"))
            .andExpect(jsonPath("$[1].itemErrors[0].errorMessage").value("Truncated file"))
            .andExpect(jsonPath("$[1].itemUpgrades[*].filename", contains("old.fit", "typed.fit", "bare.fit")))
            // A legacy item's free text is one LEGACY change, never parsed.
            .andExpect(jsonPath("$[1].itemUpgrades[0].changes", hasSize<Any>(1)))
            .andExpect(jsonPath("$[1].itemUpgrades[0].changes[0].kind").value("LEGACY"))
            .andExpect(jsonPath("$[1].itemUpgrades[0].changes[0].changeType").value("UPGRADED"))
            .andExpect(jsonPath("$[1].itemUpgrades[0].changes[0].description").value("Upgraded Parser Version 1 -> 2"))
            .andExpect(jsonPath("$[1].itemUpgrades[0].upgradeMessage").doesNotExist())
            .andExpect(jsonPath("$[1].itemUpgrades[1].changes[*].kind", contains("UPGRADED", "UPGRADED")))
            .andExpect(jsonPath("$[1].itemUpgrades[1].changes[*].laps.kind", contains("BACKFILLED", "REPLACED")))
            .andExpect(jsonPath("$[1].itemUpgrades[1].changes[0].laps.count").value(2))
            .andExpect(jsonPath("$[1].itemUpgrades[1].changes[0].details").value(nullValue()))
            // Neither typed upgrades nor legacy text: no changes, and no backend fallback wording.
            .andExpect(jsonPath("$[1].itemUpgrades[2].changes", hasSize<Any>(0)))
            .andExpect(jsonPath("$[1].duration").doesNotExist())
    }

    @Test
    @WithAnonymousUser
    fun `unauthenticated import history request returns an UNAUTHORIZED envelope`() {
        mockMvc
            .perform(get("/api/activities/import/history").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }

    @Test
    @WithAnonymousUser
    fun `OpenAPI contract documents every Import Batch error response as an ApiErrorResponse envelope`() {
        val envelope = "#/components/schemas/ApiErrorResponse"
        val errorResponses =
            listOf(
                "$.paths['/api/activities/import'].post.responses['400']",
                "$.paths['/api/activities/import'].post.responses['401']",
                "$.paths['/api/activities/import/batches/{id}'].get.responses['404']",
                "$.paths['/api/activities/import/batches/{id}/cancel'].post.responses['404']",
                "$.paths['/api/activities/import/history'].get.responses['401']",
            )
        val result =
            mockMvc
                .perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk)
        errorResponses.forEach { response ->
            result
                .andExpect(jsonPath("$response.description").isNotEmpty)
                .andExpect(jsonPath("$response.content['*/*'].schema['\$ref']").value(envelope))
        }
    }

    @Test
    @WithAnonymousUser
    fun `OpenAPI contract exposes the Import Batch endpoints and required canonical fields`() {
        val summary = "$.components.schemas.ImportBatchSummaryDTO"
        mockMvc
            .perform(get("/v3/api-docs").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/activities/import'].post").exists())
            .andExpect(jsonPath("$.paths['/api/activities/import/batches/{id}'].get").exists())
            .andExpect(jsonPath("$.paths['/api/activities/import/batches/{id}/cancel'].post").exists())
            .andExpect(jsonPath("$.paths['/api/activities/import/history'].get").exists())
            .andExpect(jsonPath("$.paths['/activities/import']").doesNotExist())
            .andExpect(jsonPath("$.paths['/activities/import/batches/{id}']").doesNotExist())
            .andExpect(
                jsonPath(
                    "$summary.required",
                    hasItems("id", "status", "createdAt", "completedAt", "itemErrors", "itemUpgrades"),
                ),
            ).andExpect(jsonPath("$summary.properties.createdAt.format").value("date-time"))
            .andExpect(jsonPath("$summary.properties.duration").doesNotExist())
            .andExpect(jsonPath("$summary.properties.errors").doesNotExist())
            .andExpect(jsonPath("$summary.properties.completedAt.type", hasItems("string", "null")))
            .andExpect(jsonPath("$.components.schemas.ImportBatchReceiptDTO.required", hasItems("batchId", "status", "totalFiles")))
            .andExpect(jsonPath("$.components.schemas.ImportBatchItemErrorDTO.required", hasItems("filename", "errorMessage")))
            .andExpect(
                jsonPath("$.components.schemas.ImportBatchItemUpgradeDTO.required", hasItems("filename", "changes")),
            ).andExpect(jsonPath("$.components.schemas.ImportBatchItemUpgradeDTO.properties.upgradeMessage").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.ActivityChangeDTO.discriminator.propertyName").value("kind"))
            .andExpect(jsonPath("$summary.properties", not(nullValue())))
    }
}
