package com.jacobcraig.stridehearth.batch

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "stridehearth.storage")
data class BatchStorageProperties(
    var tempDir: String = "./temp-batches",
    var maxArchiveUncompressedBytes: Long = 500L * 1024 * 1024,
    var maxArchiveFiles: Int = 1000,
    var requiredFreeDiskSpaceBuffer: Long = 500L * 1024 * 1024,
)
