package com.jacobcraig.stridehearth.schema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreMigrationBackupTest {
    @TempDir
    lateinit var dir: Path

    private val connection = DatabaseConnection("jdbc:postgresql://db:5432/stridehearth", "app", "secret")
    private var now = Instant.parse("2026-03-04T05:06:07Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant(): Instant = now
        }
    private val writingDumper = DatabaseDumper { _, target -> target.writeText("dump") }

    private fun backup(dumper: DatabaseDumper = writingDumper) = PreMigrationBackup(dir, dumper, keep = 3, clock = clock)

    private fun dumps() = Files.list(dir).use { s -> s.map { it.name }.sorted().toList() }

    @Test
    fun `names the dump with the from and to schema versions and a UTC timestamp`() {
        val path = backup().take(connection, "26", "28")

        assertEquals("stridehearth-v26-to-v28-20260304T050607Z.dump", path.name)
        assertEquals(listOf(path.name), dumps())
    }

    @Test
    fun `creates the backups directory if it is missing`() {
        val nested = dir.resolve("a/b")
        PreMigrationBackup(nested, writingDumper, clock = clock).take(connection, "1", "2")

        assertTrue(Files.isDirectory(nested))
    }

    @Test
    fun `keeps only the latest three dumps`() {
        repeat(5) { i ->
            now = now.plusSeconds(60)
            val path = backup().take(connection, "$i", "${i + 1}")
            Files.setLastModifiedTime(path, FileTime.from(now))
        }

        assertEquals(listOf(2, 3, 4), dumps().map { it.substringAfter("-v").substringBefore("-to").toInt() }.sorted())
    }

    @Test
    fun `retention orders by the timestamp in the name, so v9 is older than v10 whatever the mtime`() {
        val old = dir.resolve("stridehearth-v8-to-v9-20260101T000000Z.dump").also { it.writeText("x") }
        dir.resolve("stridehearth-v9-to-v10-20260102T000000Z.dump").writeText("x")
        dir.resolve("stridehearth-v10-to-v11-20260103T000000Z.dump").writeText("x")
        dir.resolve("stridehearth-v11-to-v12-20260104T000000Z.dump").writeText("x")
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2030-01-01T00:00:00Z")))

        backup().take(connection, "12", "13")

        assertTrue(old.name !in dumps(), dumps().toString())
        assertEquals(3, dumps().size)
    }

    @Test
    fun `removes stale partial files from a killed backup`() {
        val stale = dir.resolve("stridehearth-v1-to-v2-20250101T000000Z.dump.partial").also { it.writeText("half") }

        backup().take(connection, "2", "3")

        assertTrue(stale.name !in dumps())
    }

    @Test
    fun `keep must be at least one`() {
        assertFailsWith<IllegalArgumentException> { PreMigrationBackup(dir, writingDumper, keep = 0) }
        assertFailsWith<IllegalArgumentException> { BackupProperties(keep = 0) }
    }

    @Test
    fun `does not touch other files when pruning`() {
        dir.resolve("notes.txt").writeText("mine")
        repeat(4) {
            now = now.plusSeconds(1)
            backup().take(connection, "1", "2")
        }

        assertTrue(dumps().contains("notes.txt"))
    }

    @Test
    fun `a failed dump leaves no file and keeps the earlier dumps`() {
        val earlier = backup().take(connection, "1", "2")
        now = now.plusSeconds(60)

        assertFailsWith<BackupFailedException> {
            backup { _, target ->
                target.writeText("half")
                throw BackupFailedException("boom")
            }.take(connection, "2", "3")
        }

        assertEquals(listOf(earlier.name), dumps())
    }

    @Test
    fun `an unwritable backups directory is a BackupFailedException`() {
        val file = dir.resolve("not-a-directory")
        file.writeText("x")

        assertFailsWith<BackupFailedException> {
            PreMigrationBackup(file, writingDumper, clock = clock).take(connection, "1", "2")
        }
    }

    @Test
    fun `pg_dump gets the connection in its environment, never in its arguments, and a non-zero exit carries its message`() {
        val script = dir.resolve("fake-pg-dump")
        script.writeText(
            "#!/bin/sh\necho \"args: \$* host=\$PGHOST port=\$PGPORT db=\$PGDATABASE user=\$PGUSER pw=\$PGPASSWORD\" >&2\nexit 3\n",
        )
        script.toFile().setExecutable(true)

        val failure = assertFailsWith<BackupFailedException> { PgDumpDumper(script.toString()).dump(connection, dir.resolve("out")) }

        val message = failure.message.orEmpty()
        assertTrue(
            "args: --format=custom --file=${dir.resolve("out")} host=db port=5432 db=stridehearth user=app pw=secret" in message,
            message,
        )
        assertTrue("status 3" in message, message)
    }

    @Test
    fun `a pg_dump that hangs is killed after the timeout`() {
        val script = dir.resolve("hanging-pg-dump")
        script.writeText("#!/bin/sh\nexec sleep 30\n")
        script.toFile().setExecutable(true)

        val failure =
            assertFailsWith<BackupFailedException> {
                PgDumpDumper(script.toString(), timeout = Duration.ofMillis(300)).dump(connection, dir.resolve("out"))
            }

        assertTrue("did not finish" in failure.message.orEmpty(), failure.message)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("urls")
    fun `the JDBC URL becomes libpq environment variables and JDBC-only parameters are dropped`(
        url: String,
        expected: Map<String, String>,
    ) {
        assertEquals(expected, PgDumpDumper.libpqEnvironment(DatabaseConnection(url, "app", "secret")).minus(setOf("PGUSER", "PGPASSWORD")))
    }

    @Test
    fun `the connection's credentials win over the URL's, which are used when the connection has none`() {
        val url = "jdbc:postgresql://h/db?user=urluser&password=urlpw"

        val own = PgDumpDumper.libpqEnvironment(DatabaseConnection(url, "app", "secret"))
        val fromUrl = PgDumpDumper.libpqEnvironment(DatabaseConnection(url, null, null))

        assertEquals("app" to "secret", own["PGUSER"] to own["PGPASSWORD"])
        assertEquals("urluser" to "urlpw", fromUrl["PGUSER"] to fromUrl["PGPASSWORD"])
    }

    @Test
    fun `a URL that is not jdbc postgresql is a BackupFailedException`() {
        assertFailsWith<BackupFailedException> { PgDumpDumper.libpqEnvironment(DatabaseConnection("jdbc:mysql://h/db", "u", "p")) }
    }

    @Test
    fun `a missing pg_dump executable is a BackupFailedException`() {
        assertFailsWith<BackupFailedException> { PgDumpDumper("/nonexistent/pg_dump").dump(connection, dir.resolve("out")) }
    }

    companion object {
        @JvmStatic
        fun urls() =
            listOf(
                Arguments.of(
                    "jdbc:postgresql://db:5432/stridehearth",
                    mapOf(
                        "PGHOST" to "db",
                        "PGPORT" to "5432",
                        "PGDATABASE" to "stridehearth",
                    ),
                ),
                Arguments.of("jdbc:postgresql://db/stridehearth", mapOf("PGHOST" to "db", "PGDATABASE" to "stridehearth")),
                Arguments.of(
                    "jdbc:postgresql://db:5432/stridehearth?currentSchema=app&ApplicationName=x&reWriteBatchedInserts=true",
                    mapOf("PGHOST" to "db", "PGPORT" to "5432", "PGDATABASE" to "stridehearth"),
                ),
                Arguments.of(
                    "jdbc:postgresql://db:5432/stridehearth?ssl=true",
                    mapOf("PGHOST" to "db", "PGPORT" to "5432", "PGDATABASE" to "stridehearth", "PGSSLMODE" to "verify-full"),
                ),
                Arguments.of(
                    "jdbc:postgresql://db:5432/stridehearth?ssl=true&sslmode=require&sslrootcert=/ca.pem",
                    mapOf(
                        "PGHOST" to "db",
                        "PGPORT" to "5432",
                        "PGDATABASE" to "stridehearth",
                        "PGSSLMODE" to "require",
                        "PGSSLROOTCERT" to "/ca.pem",
                    ),
                ),
                Arguments.of("jdbc:postgresql://db/stridehearth?ssl=false", mapOf("PGHOST" to "db", "PGDATABASE" to "stridehearth")),
                Arguments.of("jdbc:postgresql://[::1]:6543/my%20db", mapOf("PGHOST" to "::1", "PGPORT" to "6543", "PGDATABASE" to "my db")),
                Arguments.of(
                    "jdbc:postgresql://a:1,b:2/stridehearth",
                    mapOf(
                        "PGHOST" to "a",
                        "PGPORT" to "1",
                        "PGDATABASE" to "stridehearth",
                    ),
                ),
                Arguments.of("jdbc:postgresql:///stridehearth", mapOf("PGDATABASE" to "stridehearth")),
            )
    }
}
