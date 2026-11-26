package com.jacobcraig.stridehearth.schema

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/** Reports a [BackupFailedException] as Spring Boot's "APPLICATION FAILED TO START" summary rather than a stack trace. */
class BackupFailureAnalyzer : AbstractFailureAnalyzer<BackupFailedException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: BackupFailedException,
    ) = FailureAnalysis(
        cause.message,
        "Make sure the backups directory (stridehearth.backup.dir, the backups volume in the image) is writable and " +
            "has free space, that PostgreSQL is reachable, and that pg_dump is on the PATH at least as new as the PostgreSQL " +
            "server (the image bundles one; elsewhere install it or set stridehearth.backup.pg-dump-command), then " +
            "start the Server again. No migration was applied.",
        cause,
    )
}
