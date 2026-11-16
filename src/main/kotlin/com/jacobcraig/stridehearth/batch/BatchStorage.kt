package com.jacobcraig.stridehearth.batch

import java.io.InputStream
import java.nio.file.Path

/**
 * Storage abstraction for staging and managing batch FIT uploads.
 * Enables swapping between local filesystem and cloud storage (S3/MinIO) adapters
 * for multi-node deployments per ADR 0001.
 */
interface BatchStorage {
    fun getStorageDirectory(): Path

    fun getBatchDirectory(batchId: Long): Path

    fun stageFile(
        batchId: Long,
        originalFilename: String,
        inputStream: InputStream,
    ): Path

    fun deleteItemFile(stagedFilePath: String): Boolean

    fun deleteBatchDirectory(batchId: Long): Boolean

    fun purgeStorageDirectory()
}
