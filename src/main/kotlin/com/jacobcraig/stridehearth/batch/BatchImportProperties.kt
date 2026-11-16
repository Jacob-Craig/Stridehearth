package com.jacobcraig.stridehearth.batch

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "stridehearth.import")
data class BatchImportProperties(
    var concurrency: Int = 6,
    var stream: String = "stridehearth-import-stream",
    var group: String = "stridehearth-import-workers",
    var dlqStream: String = "import-batches-dlq",
    var maxDeliveryAttempts: Long = 3,
    var idleThreshold: Duration = Duration.ofSeconds(60),
    var reconciliationThreshold: Duration = Duration.ofSeconds(60),
    var reconciliationIntervalMs: Long = 10000,
    var reclaimIntervalMs: Long = 10000,
)
