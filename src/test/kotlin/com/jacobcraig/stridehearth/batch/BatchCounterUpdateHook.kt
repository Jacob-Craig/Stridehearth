package com.jacobcraig.stridehearth.batch

import org.hibernate.resource.jdbc.spi.StatementInspector

/**
 * A Hibernate statement inspector that runs [hook] just before any statement that adds to an
 * **Import Batch**'s running counters executes. Race tests use it to pause a worker at the exact moment
 * it counts a file, so they can land a cancel or a second worker in that gap.
 *
 * Register it with `spring.jpa.properties.hibernate.session_factory.statement_inspector`.
 */
class BatchCounterUpdateHook : StatementInspector {
    override fun inspect(sql: String): String {
        if (COUNTER_INCREMENT.containsMatchIn(sql.lowercase().replace(WHITESPACE, ""))) {
            hook?.invoke()
        }
        return sql
    }

    companion object {
        /** Runs on the counting thread, before its counter update reaches Postgres. */
        @Volatile
        var hook: (() -> Unit)? = null

        private val WHITESPACE = Regex("\\s+")
        private val COUNTER_INCREMENT = Regex("^updateimport_batch(?!_item).*processed_files=\\(?[a-z0-9_]*\\.?processed_files\\+")
    }
}
