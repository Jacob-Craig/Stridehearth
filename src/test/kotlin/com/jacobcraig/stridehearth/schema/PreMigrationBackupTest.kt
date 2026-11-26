package com.jacobcraig.stridehearth.schema

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
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
    fun `pg_dump runs with the connection, and a non-zero exit is a BackupFailedException carrying its message`() {
        val script = dir.resolve("fake-pg-dump")
        script.writeText("#!/bin/sh\necho \"args: \$* pw=\$PGPASSWORD\" >&2\nexit 3\n")
        script.toFile().setExecutable(true)

        val failure = assertFailsWith<BackupFailedException> { PgDumpDumper(script.toString()).dump(connection, dir.resolve("out")) }

        val message = failure.message.orEmpty()
        assertTrue("--username=app" in message && "--dbname=postgresql://db:5432/stridehearth" in message, message)
        assertTrue("pw=secret" in message && "status 3" in message, message)
    }

    @Test
    fun `a missing pg_dump executable is a BackupFailedException`() {
        assertFailsWith<BackupFailedException> { PgDumpDumper("/nonexistent/pg_dump").dump(connection, dir.resolve("out")) }
    }
}
