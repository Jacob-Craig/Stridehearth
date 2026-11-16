package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.users.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Service
class ImportBatchService(
    private val importBatchRepository: ImportBatchRepository,
    private val importBatchItemRepository: ImportBatchItemRepository,
    private val lifecycle: ImportBatchLifecycle,
    private val batchStorageService: BatchStorage,
    private val zipArchiveExtractor: ZipArchiveExtractor,
    private val streamDispatcher: BatchImportStreamDispatcher,
    private val transactionTemplate: TransactionTemplate,
    private val properties: BatchImportProperties,
    private val storageProperties: BatchStorageProperties = BatchStorageProperties(),
    private val diskSpaceChecker: DiskSpaceChecker = DefaultDiskSpaceChecker(),
) {
    private val logger = LoggerFactory.getLogger(ImportBatchService::class.java)

    fun createAndEnqueueBatch(
        user: User,
        files: List<MultipartFile>,
    ): ImportBatchReceiptDTO {
        val validFiles = files.filter { !it.isEmpty }
        if (validFiles.isEmpty()) {
            throw IllegalArgumentException("At least one non-empty file is required for bulk import")
        }

        val tempZipFiles = mutableListOf<Path>()
        try {
            val storageDir = batchStorageService.getStorageDirectory()
            val uploadManifest = inspectUploads(validFiles, storageDir, tempZipFiles)

            if (uploadManifest.totalValidFiles == 0) {
                throw ArchiveValidationException("No valid .fit files found in upload")
            }

            val batch = createBatchHeader(user, uploadManifest.totalValidFiles)
            val itemsToSave = stageBatchItems(batch, uploadManifest)
            val savedItems = saveBatchItems(itemsToSave)

            dispatchToRedisStream(batch.id!!, savedItems)

            logger.info("Enqueued batch {} with {} items to Redis stream '{}'", batch.id, savedItems.size, properties.stream)

            return ImportBatchReceiptDTO(
                batchId = batch.id!!,
                status = batch.status,
                totalFiles = batch.totalFiles,
            )
        } finally {
            cleanupTempArchives(tempZipFiles)
        }
    }

    private data class UploadManifest(
        val looseFiles: List<MultipartFile>,
        val archiveInspections: List<Pair<Path, ArchiveInspection>>,
        val totalValidFiles: Int,
    )

    private fun inspectUploads(
        validFiles: List<MultipartFile>,
        storageDir: Path,
        tempZipFiles: MutableList<Path>,
    ): UploadManifest {
        val looseFiles = mutableListOf<MultipartFile>()
        val archiveInspections = mutableListOf<Pair<Path, ArchiveInspection>>()

        for (file in validFiles) {
            if (zipArchiveExtractor.isZipArchive(file.originalFilename, file.contentType)) {
                val tempZip = Files.createTempFile(storageDir, "upload-archive-", ".zip")
                tempZipFiles.add(tempZip)
                file.inputStream.use { fis ->
                    Files.copy(fis, tempZip, StandardCopyOption.REPLACE_EXISTING)
                }
                val inspection = zipArchiveExtractor.inspectAndValidate(tempZip.toFile(), storageDir)
                archiveInspections.add(tempZip to inspection)
            } else {
                looseFiles.add(file)
            }
        }

        val totalUncompressedBytes = looseFiles.sumOf { it.size } + archiveInspections.sumOf { it.second.uncompressedBytes }
        if (totalUncompressedBytes > 0) {
            val usableSpace = diskSpaceChecker.getUsableSpace(storageDir)
            val requiredSpace = totalUncompressedBytes + storageProperties.requiredFreeDiskSpaceBuffer
            if (usableSpace <= requiredSpace) {
                throw InsufficientDiskSpaceException(usableSpace, requiredSpace)
            }
        }

        val totalValidFiles = looseFiles.size + archiveInspections.sumOf { it.second.validFitEntries.size }
        return UploadManifest(looseFiles, archiveInspections, totalValidFiles)
    }

    private fun createBatchHeader(
        user: User,
        totalValidFiles: Int,
    ): ImportBatch =
        transactionTemplate.execute {
            importBatchRepository.save(
                ImportBatch(
                    user = user,
                    status = ImportBatchStatus.PROCESSING,
                    totalFiles = totalValidFiles,
                ),
            )
        }

    private fun stageBatchItems(
        batch: ImportBatch,
        manifest: UploadManifest,
    ): List<ImportBatchItem> {
        val itemsToSave = mutableListOf<ImportBatchItem>()
        try {
            for (file in manifest.looseFiles) {
                val originalFilename = file.originalFilename ?: "upload.fit"
                val stagedPath = batchStorageService.stageFile(batch.id!!, originalFilename, file.inputStream)
                itemsToSave.add(createBatchItem(batch, originalFilename, stagedPath))
            }

            for ((tempZip, _) in manifest.archiveInspections) {
                val stagedEntries = zipArchiveExtractor.extractValidEntries(tempZip.toFile(), batch.id!!, batchStorageService)
                for (stagedEntry in stagedEntries) {
                    itemsToSave.add(createBatchItem(batch, stagedEntry.originalFilename, stagedEntry.stagedPath))
                }
            }
            return itemsToSave
        } catch (e: Exception) {
            batchStorageService.deleteBatchDirectory(batch.id!!)
            throw e
        }
    }

    private fun createBatchItem(
        batch: ImportBatch,
        originalFilename: String,
        stagedPath: Path,
    ): ImportBatchItem =
        ImportBatchItem(
            batch = batch,
            originalFilename = originalFilename,
            stagedFilePath = stagedPath.toString(),
            status = ImportBatchItemStatus.PENDING,
        )

    private fun saveBatchItems(items: List<ImportBatchItem>): List<ImportBatchItem> =
        transactionTemplate.execute {
            importBatchItemRepository.saveAll(items)
        }

    private fun dispatchToRedisStream(
        batchId: Long,
        items: List<ImportBatchItem>,
    ) {
        streamDispatcher.dispatchAll(batchId, items)
    }

    private fun cleanupTempArchives(tempZipFiles: List<Path>) {
        for (tempZip in tempZipFiles) {
            try {
                Files.deleteIfExists(tempZip)
            } catch (e: Exception) {
                logger.warn("Failed to delete temp archive {}: {}", tempZip, e.message)
            }
        }
    }

    fun cancelBatch(
        batchId: Long,
        userId: Long,
    ): ImportBatchSummaryDTO? {
        lifecycle.cancel(batchId, userId)
        return lifecycle.summary(batchId, userId)
    }
}
