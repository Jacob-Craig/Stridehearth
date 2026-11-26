package com.jacobcraig.stridehearth.schema

import java.io.IOException
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
 * versions it was taken between and a UTC timestamp. Only the latest [keep] are kept.
 */
class PreMigrationBackup(
    private val directory: Path,
    private val dumper: DatabaseDumper,
    private val keep: Int = 3,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Dumps the database as it stands at schema version [from], about to move to [to]. Returns the dump's path. */
    fun take(
        connection: DatabaseConnection,
        from: String,
        to: String,
    ): Path {
        val stamp = TIMESTAMP.format(clock.instant().atOffset(ZoneOffset.UTC))
        val target = directory.resolve("$PREFIX-v$from-to-v$to-$stamp$SUFFIX")
        val partial = target.resolveSibling("${target.fileName}.partial")
        try {
            Files.createDirectories(directory)
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

    private fun prune() {
        val dumps =
            Files.list(directory).use { files ->
                files
                    .filter { it.fileName.toString().let { name -> name.startsWith("$PREFIX-") && name.endsWith(SUFFIX) } }
                    .toList()
            }
        dumps
            .sortedWith(compareByDescending<Path> { Files.getLastModifiedTime(it) }.thenByDescending { it.fileName.toString() })
            .drop(keep)
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private companion object {
        const val PREFIX = "stridehearth"
        const val SUFFIX = ".dump"
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}

/** Dumps with a `pg_dump` executable, in custom format so `pg_restore` can restore it. */
class PgDumpDumper(
    private val command: String,
    private val timeoutMinutes: Long = 60,
) : DatabaseDumper {
    override fun dump(
        connection: DatabaseConnection,
        target: Path,
    ) {
        val args =
            buildList {
                add(command)
                add("--format=custom")
                add("--file=$target")
                connection.username?.let { add("--username=$it") }
                add("--dbname=${connection.jdbcUrl.removePrefix("jdbc:")}")
            }
        val process =
            try {
                ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .apply { connection.password?.let { environment()["PGPASSWORD"] = it } }
                    .start()
            } catch (e: IOException) {
                throw BackupFailedException("could not run $command (${e.message}).", e)
            }
        // pg_dump writes only errors to its output, so reading it all here stays small and cannot block the dump.
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw BackupFailedException("pg_dump did not finish within $timeoutMinutes minutes.")
        }
        if (process.exitValue() != 0) {
            throw BackupFailedException("pg_dump exited with status ${process.exitValue()}. ${output.takeLast(2000)}".trim())
        }
    }
}
