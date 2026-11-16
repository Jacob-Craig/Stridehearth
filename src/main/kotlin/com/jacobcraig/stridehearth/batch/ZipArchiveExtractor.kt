package com.jacobcraig.stridehearth.batch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class StagedItemInfo(
    val originalFilename: String,
    val stagedPath: Path,
)

data class ArchiveInspection(
    val totalEntries: Int,
    val uncompressedBytes: Long,
    val validFitEntries: List<ZipEntry>,
)

@Component
class ZipArchiveExtractor(
    private val properties: BatchStorageProperties,
    private val diskSpaceChecker: DiskSpaceChecker,
) {
    private val logger = LoggerFactory.getLogger(ZipArchiveExtractor::class.java)

    fun isZipArchive(
        filename: String?,
        contentType: String?,
    ): Boolean {
        if (filename?.endsWith(".zip", ignoreCase = true) == true) return true
        if (contentType == "application/zip" || contentType == "application/x-zip-compressed") return true
        return false
    }

    fun inspectAndValidate(
        archiveFile: File,
        storagePath: Path,
    ): ArchiveInspection {
        val zipFile =
            try {
                ZipFile(archiveFile)
            } catch (e: Exception) {
                throw ArchiveValidationException("Failed to read archive: ${e.message}")
            }

        zipFile.use { zip ->
            var entryCount = 0
            var uncompressedBytes = 0L
            val validEntries = mutableListOf<ZipEntry>()

            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                val entryName = entry.name

                // Block Zip Slip path traversal attacks
                if (entryName.contains("..")) {
                    throw ZipSlipException(entryName)
                }

                entryCount++
                if (entryCount > properties.maxArchiveFiles) {
                    throw ArchiveTooManyFilesException(entryCount, properties.maxArchiveFiles)
                }

                val size = entry.size
                if (size > 0) {
                    uncompressedBytes += size
                    if (uncompressedBytes > properties.maxArchiveUncompressedBytes) {
                        throw ArchiveTooLargeException(uncompressedBytes, properties.maxArchiveUncompressedBytes)
                    }
                }

                if (isValidFitEntry(entry)) {
                    validEntries.add(entry)
                }
            }

            // Disk space validation: usableSpace > uncompressedSize + 500MB
            val usableSpace = diskSpaceChecker.getUsableSpace(storagePath)
            val requiredSpace = uncompressedBytes + properties.requiredFreeDiskSpaceBuffer
            if (usableSpace <= requiredSpace) {
                throw InsufficientDiskSpaceException(usableSpace, requiredSpace)
            }

            logger.info(
                "Archive {} inspection passed: {} total entries, {} valid FIT entries, {} uncompressed bytes, {} usable space",
                archiveFile.name,
                entryCount,
                validEntries.size,
                uncompressedBytes,
                usableSpace,
            )

            return ArchiveInspection(
                totalEntries = entryCount,
                uncompressedBytes = uncompressedBytes,
                validFitEntries = validEntries,
            )
        }
    }

    fun extractValidEntries(
        archiveFile: File,
        batchId: Long,
        batchStorageService: BatchStorage,
    ): List<StagedItemInfo> {
        val staged = mutableListOf<StagedItemInfo>()
        var totalExtractedBytes = 0L

        ZipFile(archiveFile).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (isValidFitEntry(entry)) {
                    val cleanName = Paths.get(entry.name.replace('\\', '/')).fileName?.toString() ?: "upload.fit"
                    zip.getInputStream(entry).use { fis ->
                        val stagedPath = batchStorageService.stageFile(batchId, cleanName, fis)
                        val fileSize = Files.size(stagedPath)
                        totalExtractedBytes += fileSize
                        if (totalExtractedBytes > properties.maxArchiveUncompressedBytes) {
                            throw ArchiveTooLargeException(totalExtractedBytes, properties.maxArchiveUncompressedBytes)
                        }
                        staged.add(StagedItemInfo(cleanName, stagedPath))
                    }
                }
            }
        }
        return staged
    }

    fun isValidFitEntry(entry: ZipEntry): Boolean {
        if (entry.isDirectory) return false
        val name = entry.name
        // Filter macOS metadata directory
        if (name.contains("__MACOSX")) return false

        val fileName = Paths.get(name.replace('\\', '/')).fileName?.toString() ?: return false
        // Filter .DS_Store and AppleDouble metadata files
        if (fileName.equals(".DS_Store", ignoreCase = true)) return false
        if (fileName.startsWith("._")) return false

        // Filter non-FIT files
        return fileName.endsWith(".fit", ignoreCase = true)
    }
}
