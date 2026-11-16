package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.users.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path

class ImportBatchServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val importBatchRepository: ImportBatchRepository = mock()
    private val importBatchItemRepository: ImportBatchItemRepository = mock()
    private val batchStorageService: BatchStorage = mock()
    private val zipArchiveExtractor: ZipArchiveExtractor = mock()
    private val streamDispatcher: BatchImportStreamDispatcher = mock()
    private val transactionTemplate: TransactionTemplate = mock()
    private val diskSpaceChecker: DiskSpaceChecker = mock()

    private val properties = BatchImportProperties()
    private lateinit var storageProperties: BatchStorageProperties
    private lateinit var service: ImportBatchService

    private val testUser =
        User(
            id = 1L,
            email = "test@example.com",
            username = "tester",
            passwordHash = "hash",
        )

    @BeforeEach
    fun setUp() {
        storageProperties =
            BatchStorageProperties(
                tempDir = tempDir.toString(),
                requiredFreeDiskSpaceBuffer = 500L * 1024 * 1024,
            )
        doReturn(tempDir).`when`(batchStorageService).getStorageDirectory()

        doAnswer { invocation ->
            val callback = invocation.getArgument<TransactionCallback<Any>>(0)
            callback.doInTransaction(mock<TransactionStatus>())
        }.`when`(transactionTemplate).execute(any<TransactionCallback<Any>>())

        doReturn(100L * 1024 * 1024 * 1024).`when`(diskSpaceChecker).getUsableSpace(any())

        service =
            ImportBatchService(
                importBatchRepository = importBatchRepository,
                importBatchItemRepository = importBatchItemRepository,
                lifecycle = mock(),
                batchStorageService = batchStorageService,
                zipArchiveExtractor = zipArchiveExtractor,
                streamDispatcher = streamDispatcher,
                transactionTemplate = transactionTemplate,
                properties = properties,
                storageProperties = storageProperties,
                diskSpaceChecker = diskSpaceChecker,
            )
    }

    @Test
    fun `createAndEnqueueBatch throws IllegalArgumentException when all files are empty`() {
        val emptyFile = MockMultipartFile("file", "test.fit", "application/octet-stream", ByteArray(0))
        assertThrows(IllegalArgumentException::class.java) {
            service.createAndEnqueueBatch(testUser, listOf(emptyFile))
        }
    }

    @Test
    fun `createAndEnqueueBatch rejects upload if usable disk space is insufficient for loose files`() {
        val looseFile = MockMultipartFile("file", "run.fit", "application/octet-stream", ByteArray(1024 * 1024))
        doReturn(false).`when`(zipArchiveExtractor).isZipArchive(any(), any())

        // Usable space is less than required buffer
        doReturn(100L).`when`(diskSpaceChecker).getUsableSpace(any())

        assertThrows(InsufficientDiskSpaceException::class.java) {
            service.createAndEnqueueBatch(testUser, listOf(looseFile))
        }
    }

    @Test
    fun `createAndEnqueueBatch stages loose files, persists header, saves items, and dispatches to redis stream`() {
        val fileContent = "SAMPLE_FIT_CONTENT".toByteArray()
        val looseFile = MockMultipartFile("file", "morning_run.fit", "application/octet-stream", fileContent)
        doReturn(false).`when`(zipArchiveExtractor).isZipArchive(any(), any())

        val stagedPath = tempDir.resolve("morning_run.fit")
        doReturn(stagedPath).`when`(batchStorageService).stageFile(eq(42L), eq("morning_run.fit"), any())

        val savedBatch = ImportBatch(id = 42L, user = testUser, status = ImportBatchStatus.PROCESSING, totalFiles = 1)
        doReturn(savedBatch).`when`(importBatchRepository).save(any())

        val savedItem =
            ImportBatchItem(
                id = 101L,
                batch = savedBatch,
                originalFilename = "morning_run.fit",
                stagedFilePath = stagedPath.toString(),
                status = ImportBatchItemStatus.PENDING,
            )
        doReturn(listOf(savedItem)).`when`(importBatchItemRepository).saveAll(any<Iterable<ImportBatchItem>>())

        val receipt = service.createAndEnqueueBatch(testUser, listOf(looseFile))

        assertEquals(42L, receipt.batchId)
        assertEquals(ImportBatchStatus.PROCESSING, receipt.status)
        assertEquals(1, receipt.totalFiles)

        verify(batchStorageService).stageFile(eq(42L), eq("morning_run.fit"), any())
        verify(importBatchItemRepository).saveAll(any<List<ImportBatchItem>>())
        verify(streamDispatcher).dispatchAll(eq(42L), any())
    }

    @Test
    fun `createAndEnqueueBatch deletes staged directory if staging throws exception`() {
        val looseFile = MockMultipartFile("file", "bad.fit", "application/octet-stream", ByteArray(100))
        doReturn(false).`when`(zipArchiveExtractor).isZipArchive(any(), any())

        val savedBatch = ImportBatch(id = 99L, user = testUser, status = ImportBatchStatus.PROCESSING, totalFiles = 1)
        doReturn(savedBatch).`when`(importBatchRepository).save(any())

        doAnswer { throw RuntimeException("Disk write error") }
            .`when`(batchStorageService)
            .stageFile(eq(99L), eq("bad.fit"), any())

        assertThrows(RuntimeException::class.java) {
            service.createAndEnqueueBatch(testUser, listOf(looseFile))
        }

        verify(batchStorageService).deleteBatchDirectory(99L)
    }

    @Test
    fun `createAndEnqueueBatch rejects upload if combined size of archives and loose files exceeds disk buffer`() {
        val archiveBytes = ByteArray(1024)
        val zipFile = MockMultipartFile("file", "activities.zip", "application/zip", archiveBytes)
        val looseFile = MockMultipartFile("file", "run.fit", "application/octet-stream", ByteArray(1024 * 1024))

        doReturn(true).`when`(zipArchiveExtractor).isZipArchive(eq("activities.zip"), eq("application/zip"))
        doReturn(false).`when`(zipArchiveExtractor).isZipArchive(eq("run.fit"), eq("application/octet-stream"))

        val inspection =
            ArchiveInspection(
                totalEntries = 5,
                uncompressedBytes = 200L * 1024 * 1024,
                validFitEntries = listOf(java.util.zip.ZipEntry("run.fit")),
            )
        doReturn(inspection).`when`(zipArchiveExtractor).inspectAndValidate(any(), any())

        // Usable space is less than combined 200MB + 1MB + 500MB buffer
        val required = 200L * 1024 * 1024 + 1024 * 1024 + storageProperties.requiredFreeDiskSpaceBuffer
        doReturn(required - 1).`when`(diskSpaceChecker).getUsableSpace(any())

        assertThrows(InsufficientDiskSpaceException::class.java) {
            service.createAndEnqueueBatch(testUser, listOf(zipFile, looseFile))
        }
    }
}
