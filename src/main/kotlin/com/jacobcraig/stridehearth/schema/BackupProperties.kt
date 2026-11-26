package com.jacobcraig.stridehearth.schema

import org.springframework.boot.context.properties.ConfigurationProperties

/** Where and how the Server backs up its database before applying pending migrations (ADR-0010). */
@ConfigurationProperties(prefix = "stridehearth.backup")
data class BackupProperties(
    /** The backups directory. The image mounts a volume here (`STRIDEHEARTH_BACKUP_DIR`). */
    val dir: String = "./backups",
    /** The `pg_dump` executable; the image bundles one. */
    val pgDumpCommand: String = "pg_dump",
    /** How many backups to keep; older ones are deleted after a new one succeeds. */
    val keep: Int = 3,
) {
    init {
        require(keep >= 1) { "stridehearth.backup.keep must be at least 1, was $keep" }
    }
}
