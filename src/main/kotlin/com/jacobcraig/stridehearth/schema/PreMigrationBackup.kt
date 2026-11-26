package com.jacobcraig.stridehearth.schema

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** How to reach the database being dumped. */
data class DatabaseConnection(
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
)

/** Writes a restorable dump of a database to a file; throws [BackupFailedException] if it cannot. */
fun interface DatabaseDumper {
    fun dump(
        connection: DatabaseConnection,
        target: Path,
    )
}

/**
 * The backup the Server takes just before applying pending migrations, so a One-way Release can be undone by
 * restoring it (ADR-0010). Each dump is a `pg_dump` custom-format archive in [directory], named with the schema
 * versions it was taken between and a UTC timestamp. Only the latest [keep] are kept, newest by that timestamp.
 */
class PreMigrationBackup(
    private val directory: Path,
    private val dumper: DatabaseDumper,
    private val keep: Int = 3,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(keep >= 1) { "stridehearth.backup.keep must be at least 1, was $keep" }
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** Dumps the database as it stands at schema version [from], about to move to [to]. Returns the dump's path. */
    fun take(
        connection: DatabaseConnection,
        from: String,
        to: String,
    ): Path {
        val stamp = TIMESTAMP.format(clock.instant().atOffset(ZoneOffset.UTC))
        val target = directory.resolve("$PREFIX-v$from-to-v$to-$stamp$SUFFIX")
        val partial = target.resolveSibling("${target.fileName}$PARTIAL")
        try {
            Files.createDirectories(directory)
            removeStalePartials()
            dumper.dump(connection, partial)
            Files.move(partial, target)
        } catch (e: IOException) {
            throw BackupFailedException("cannot write to the backups directory $directory (${e.message}).", e)
        } finally {
            runCatching { Files.deleteIfExists(partial) }
        }
        prune()
        return target
    }

    private fun names(): List<Path> = Files.list(directory).use { it.toList() }

    /** Half-written dumps left by a Server killed mid-backup. */
    private fun removeStalePartials() {
        names()
            .filter { it.fileName.toString().let { n -> n.startsWith("$PREFIX-") && n.endsWith("$SUFFIX$PARTIAL") } }
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** Keeps the [keep] newest dumps by the timestamp in their names (not mtime, and not the name: v9 sorts after v10). */
    private fun prune() {
        try {
            names()
                .mapNotNull { path -> NAME.matchEntire(path.fileName.toString())?.let { path to it.groupValues[1] } }
                .sortedWith(compareByDescending<Pair<Path, String>> { it.second }.thenByDescending { it.first.fileName.toString() })
                .drop(keep)
                .forEach { (path, _) -> Files.deleteIfExists(path) }
        } catch (e: IOException) {
            log.warn("Could not prune old backups in {}: {}", directory, e.message)
        }
    }

    private companion object {
        const val PREFIX = "stridehearth"
        const val SUFFIX = ".dump"
        const val PARTIAL = ".partial"
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val NAME = Regex("$PREFIX-v.+-to-v.+-(\\d{8}T\\d{6}Z)\\.dump")
    }
}

/**
 * Dumps with a `pg_dump` executable, in custom format so `pg_restore` can restore it. The JDBC URL is parsed here and
 * handed over as `PG*` environment variables: libpq rejects JDBC-only URL parameters, and nothing secret goes in the
 * process arguments.
 */
class PgDumpDumper(
    private val command: String,
    private val timeout: java.time.Duration = java.time.Duration.ofMinutes(60),
) : DatabaseDumper {
    override fun dump(
        connection: DatabaseConnection,
        target: Path,
    ) {
        val env = libpqEnvironment(connection)
        val log = Files.createTempFile("pg_dump-", ".log")
        try {
            val process =
                try {
                    ProcessBuilder(command, "--format=custom", "--file=$target")
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .apply {
                            environment().keys.removeIf { it.startsWith("PG") }
                            environment().putAll(env)
                        }.start()
                } catch (e: IOException) {
                    throw BackupFailedException("could not run $command (${e.message}).", e)
                }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw BackupFailedException("pg_dump did not finish within $timeout.")
            }
            if (process.exitValue() != 0) {
                val output = runCatching { Files.readString(log) }.getOrDefault("").trim()
                throw BackupFailedException("pg_dump exited with status ${process.exitValue()}. ${output.takeLast(2000)}".trim())
            }
        } finally {
            runCatching { Files.deleteIfExists(log) }
        }
    }

    internal companion object {
        /**
         * The `PG*` variables for [connection]. Understood from the JDBC URL: host, port, database, `user`,
         * `password`, `ssl=true` and `sslmode` (to PGSSLMODE), and `sslrootcert`, `sslcert` and `sslkey`. Everything
         * else (`currentSchema`, `ApplicationName`, `reWriteBatchedInserts`, ...) is JDBC-only and dropped. The
         * connection's own username and password win over the URL's.
         */
        fun libpqEnvironment(connection: DatabaseConnection): Map<String, String> {
            val url = connection.jdbcUrl
            val uri =
                try {
                    URI(url.removePrefix("jdbc:").replaceFirst(Regex("^postgresql://[^/?]*"), "postgresql://placeholder"))
                } catch (e: java.net.URISyntaxException) {
                    throw BackupFailedException("cannot understand the JDBC URL (${e.message}).", e)
                }
            val authority =
                Regex("^(?:jdbc:)?postgres(?:ql)?://([^/?]*)").find(url)?.groupValues?.get(1)
                    ?: throw BackupFailedException("the JDBC URL is not a jdbc:postgresql:// URL.")
            // Several hosts may be listed (failover); the first is dumped.
            val first = authority.substringAfterLast('@').substringBefore(',')
            val (host, port) =
                if (first.startsWith("[")) {
                    first.substringAfter('[').substringBefore(']') to first.substringAfter(']', "").removePrefix(":")
                } else {
                    first.substringBefore(':') to first.substringAfter(':', "")
                }
            val params =
                (uri.rawQuery ?: "")
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .associate {
                        val key = URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8)
                        key to URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
                    }
            val database = URLDecoder.decode(uri.rawPath.orEmpty().removePrefix("/"), StandardCharsets.UTF_8)
            val sslMode = params["sslmode"] ?: if (params["ssl"].let { it != null && !it.equals("false", true) }) "verify-full" else null
            return buildMap {
                if (host.isNotEmpty()) put("PGHOST", host)
                if (port.isNotEmpty()) put("PGPORT", port)
                if (database.isNotEmpty()) put("PGDATABASE", database)
                (connection.username ?: params["user"])?.let { put("PGUSER", it) }
                (connection.password ?: params["password"])?.let { put("PGPASSWORD", it) }
                sslMode?.let { put("PGSSLMODE", it) }
                params["sslrootcert"]?.let { put("PGSSLROOTCERT", it) }
                params["sslcert"]?.let { put("PGSSLCERT", it) }
                params["sslkey"]?.let { put("PGSSLKEY", it) }
            }
        }
    }
}
