package com.jacobcraig.stridehearth.batch

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.util.FileSystemUtils
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID

@Service
class BatchStorageService(
    private val properties: BatchStorageProperties,
) : BatchStorage {
    private val logger = LoggerFactory.getLogger(BatchStorageService::class.java)

    internal val shutdownHook: Thread =
        Thread({
            logger.info("Executing JVM shutdown hook: purging temporary batch storage")
            purgeStorageDirectory()
        }, "batch-storage-shutdown-hook")

    @PostConstruct
    fun init() {
        try {
            getStorageDirectory()
        } catch (e: Exception) {
            logger.warn("Could not initialize storage directory: {}", e.message)
        }
        registerShutdownHook()
    }

    fun registerShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            logger.debug("Registered JVM shutdown hook for BatchStorageService")
        } catch (e: IllegalStateException) {
            logger.warn("Could not register shutdown hook: {}", e.message)
        }
    }

    @PreDestroy
    fun onDestroy() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // JVM already in the process of shutting down
        }
        logger.info("BatchStorageService destroying: purging temporary batch storage")
        purgeStorageDirectory()
    }

    override fun purgeStorageDirectory() {
        try {
            val root = Paths.get(properties.tempDir).toAbsolutePath().normalize()
            if (Files.exists(root)) {
                Files.list(root).use { stream ->
                    stream.forEach { entry ->
                        try {
                            FileSystemUtils.deleteRecursively(entry)
                        } catch (e: Exception) {
                            logger.warn("Failed to delete entry {}: {}", entry, e.message)
                        }
                    }
                }
                logger.debug("Purged temporary storage directory {}", root)
            }
        } catch (e: Exception) {
            logger.warn("Failed to purge storage directory: {}", e.message)
        }
    }

    override fun getStorageDirectory(): Path {
        val path = Paths.get(properties.tempDir).toAbsolutePath().normalize()
        Files.createDirectories(path)
        return path
    }

    override fun getBatchDirectory(batchId: Long): Path = Paths.get(properties.tempDir, batchId.toString()).toAbsolutePath().normalize()

    override fun stageFile(
        batchId: Long,
        originalFilename: String,
        inputStream: InputStream,
    ): Path {
        val batchDir = getBatchDirectory(batchId)
        Files.createDirectories(batchDir)

        val cleanName =
            Paths
                .get(originalFilename)
                .fileName
                .toString()
                .ifBlank { "upload.fit" }
        val uniqueName = "${UUID.randomUUID()}_$cleanName"
        val targetPath = batchDir.resolve(uniqueName).normalize()

        if (!targetPath.startsWith(batchDir)) {
            throw SecurityException("Invalid file path traversal attempt: $originalFilename")
        }

        Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING)
        logger.debug("Staged file {} for batch {}", targetPath, batchId)
        return targetPath
    }

    override fun deleteItemFile(stagedFilePath: String): Boolean =
        try {
            val path = Paths.get(stagedFilePath)
            val deleted = Files.deleteIfExists(path)
            if (deleted) {
                logger.debug("Deleted staged item file {}", stagedFilePath)
            }
            deleted
        } catch (e: Exception) {
            logger.warn("Failed to delete staged item file {}: {}", stagedFilePath, e.message)
            false
        }

    override fun deleteBatchDirectory(batchId: Long): Boolean =
        try {
            val batchDir = getBatchDirectory(batchId)
            val deleted = FileSystemUtils.deleteRecursively(batchDir)
            if (deleted) {
                logger.debug("Deleted batch directory for batch {}", batchId)
            }
            deleted
        } catch (e: Exception) {
            logger.warn("Failed to delete batch directory for batch {}: {}", batchId, e.message)
            false
        }
}
