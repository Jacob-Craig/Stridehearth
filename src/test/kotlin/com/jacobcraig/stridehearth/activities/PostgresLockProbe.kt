package com.jacobcraig.stridehearth.activities

import org.springframework.jdbc.core.simple.JdbcClient
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/** How many advisory locks a backend holds and how many it is still waiting for. */
data class AdvisoryLockCounts(
    val held: Int,
    val waiting: Int,
)

/**
 * Observes Postgres backends' locks for concurrency tests (ADR-0006): which backend a transaction runs on, what it
 * waits on, and which advisory locks it holds. Tests wait with [awaitUntil], which polls with no sleeps.
 */
class PostgresLockProbe(
    private val jdbcClient: JdbcClient,
) {
    /** The backend of the calling thread's connection, so another connection can watch what it waits on. */
    fun currentBackendPid(): Int = jdbcClient.sql("SELECT pg_backend_pid()").query(Int::class.java).single()

    /**
     * Whether backend [pid] waits on a heavyweight lock (a row lock or an advisory lock) right now. Safe to call
     * inside a transaction: it drops the statistics snapshot Postgres would otherwise keep, and reuse, until the
     * transaction ends.
     */
    fun isWaitingOnLock(pid: Int): Boolean {
        jdbcClient.sql("SELECT pg_stat_clear_snapshot()").query { _, _ -> }.list()
        return jdbcClient
            .sql("SELECT wait_event_type FROM pg_stat_activity WHERE pid = :pid")
            .param("pid", pid)
            .query(String::class.java)
            .optional()
            .orElse(null) == "Lock"
    }

    /** How many advisory locks backend [pid] holds and how many it is still waiting for. */
    fun advisoryLockCounts(pid: Int): AdvisoryLockCounts {
        val granted =
            jdbcClient
                .sql("SELECT granted FROM pg_locks WHERE locktype = 'advisory' AND pid = :pid")
                .param("pid", pid)
                .query { rs, _ -> rs.getBoolean("granted") }
                .list()
        return AdvisoryLockCounts(held = granted.count { it }, waiting = granted.count { !it })
    }

    /**
     * Polls until a backend other than [excluding] waits on an advisory lock in this database, and returns it: the
     * way to find the backend of work that opens its own transaction (an import), so it has no pid to report.
     * Fails after a timeout, or as soon as [ended] reports that the work finished without waiting.
     */
    fun awaitAdvisoryLockWaiter(
        excluding: Set<Int> = emptySet(),
        ended: () -> Boolean = { false },
    ): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val waiter =
                jdbcClient
                    .sql(
                        "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND NOT granted " +
                            "AND database = (SELECT oid FROM pg_database WHERE datname = current_database())",
                    ).query(Int::class.java)
                    .list()
                    .firstOrNull { it !in excluding }
            if (waiter != null) return waiter
            check(!ended()) { "The work ended without waiting on an advisory lock" }
            Thread.onSpinWait()
        }
        fail("No backend other than $excluding started waiting on an advisory lock")
    }

    /**
     * Polls until a backend other than [excluding] in this database waits on a heavyweight lock that is not an
     * advisory lock (such as another transaction's row lock), and returns it: the way to find the backend of work
     * that opens its own transaction. When [statementMentioning] is given, only a backend whose current statement
     * contains that text (such as a table name) counts, so another backend's lock wait is not mistaken for it. Fails
     * after a timeout, or as soon as [ended] reports that the work finished without waiting.
     */
    fun awaitRowLockWaiter(
        excluding: Set<Int> = emptySet(),
        statementMentioning: String? = null,
        ended: () -> Boolean = { false },
    ): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            jdbcClient.sql("SELECT pg_stat_clear_snapshot()").query { _, _ -> }.list()
            val waiter =
                jdbcClient
                    .sql(
                        "SELECT l.pid FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid " +
                            "WHERE l.locktype <> 'advisory' AND NOT l.granted AND a.datname = current_database() " +
                            "AND (CAST(:statement AS text) IS NULL OR a.query LIKE '%' || CAST(:statement AS text) || '%')",
                    ).param("statement", statementMentioning)
                    .query(Int::class.java)
                    .list()
                    .firstOrNull { it !in excluding }
            if (waiter != null) return waiter
            check(!ended()) { "The work ended without waiting on a lock" }
            Thread.onSpinWait()
        }
        fail("No backend other than $excluding started waiting on a lock")
    }

    /**
     * Polls until backend [pid] reaches [what], as [reached] reports. Fails after a timeout, or as soon as [ended]
     * reports that the backend's work finished without getting there.
     */
    fun awaitUntil(
        pid: Int,
        what: String,
        ended: () -> Boolean = { false },
        reached: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (reached()) return
            check(!ended()) { "Backend $pid ended instead of reaching: $what" }
            Thread.onSpinWait()
        }
        fail("Backend $pid never reached: $what")
    }
}
