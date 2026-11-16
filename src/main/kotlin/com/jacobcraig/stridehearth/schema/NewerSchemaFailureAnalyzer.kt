package com.jacobcraig.stridehearth.schema

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/** Reports a [NewerSchemaException] as Spring Boot's "APPLICATION FAILED TO START" summary rather than a stack trace. */
class NewerSchemaFailureAnalyzer : AbstractFailureAnalyzer<NewerSchemaException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: NewerSchemaException,
    ) = FailureAnalysis(
        cause.message,
        "Restore the database backup taken before the upgrade to the newer One-way Release, or run that newer Release.",
        cause,
    )
}
