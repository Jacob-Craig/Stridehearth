package com.jacobcraig.stridehearth.schema

import com.jacobcraig.stridehearth.POSTGIS_IMAGE
import com.jacobcraig.stridehearth.StridehearthApplication
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-0010 at startup: pending migrations on an existing database are preceded by a backup, and a failed backup stops
 * the Server before any migration runs. A stand-in script plays `pg_dump`, so these need no PostgreSQL client.
 */
class PreMigrationBackupIntegrationTest {
    private val postgres = PostgreSQLContainer(POSTGIS_IMAGE)

    @TempDir
    lateinit var backups: Path

    @TempDir
    lateinit var tools: Path

    @BeforeEach
    fun startDatabaseAtAnOlderSchema() {
        postgres.start()
        flyway().target("5").load().migrate()
    }

    @AfterEach
    fun stopDatabase() = postgres.stop()

    private fun dataSource() = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun flyway() = Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration")

    private fun currentVersion() =
        JdbcTemplate(dataSource()).queryForObject("select max(version::int) from flyway_schema_history where success", Int::class.java)

    private fun fakePgDump(body: String): String {
        val script = tools.resolve("pg_dump")
        script.writeText("#!/bin/sh\n$body\n")
        script.toFile().setExecutable(true)
        return script.toString()
    }

    private fun start(
        pgDump: String,
        backupDir: Path = backups,
    ) = SpringApplicationBuilder(StridehearthApplication::class.java)
        .run(
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.data.redis.host=redis.invalid",
            "--server.port=0",
            "--stridehearth.backup.dir=$backupDir",
            "--stridehearth.backup.pg-dump-command=$pgDump",
        ).close()

    private fun dumps() = Files.list(backups).use { s -> s.map { it.name }.toList() }

    @Test
    fun `pending migrations are backed up first, and a restart with none pending writes nothing`() {
        val writesFile = fakePgDump("for a in \"\$@\"; do case \"\$a\" in --file=*) echo dump > \"\${a#--file=}\";; esac; done")

        start(writesFile)

        val latest =
            flyway()
                .load()
                .info()
                .all()
                .maxOf { it.version.version.toInt() }
        assertEquals(latest, currentVersion())
        val written = dumps()
        assertEquals(1, written.size, written.toString())
        assertTrue(Regex("stridehearth-v5-to-v$latest-\\d{8}T\\d{6}Z\\.dump").matches(written.single()), written.single())

        start(writesFile)

        assertEquals(written, dumps())
    }

    @Test
    fun `a failed dump stops the Server before any migration runs`() {
        val failure = assertFailsWith<Exception> { start(fakePgDump("echo 'cannot connect' >&2; exit 1")) }

        val refusal = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<BackupFailedException>().firstOrNull()
        assertTrue(refusal?.message.orEmpty().contains("cannot connect"), "not refused by the backup: $failure")
        assertEquals(5, currentVersion())
        assertEquals(emptyList(), dumps())
    }

    @Test
    fun `an unwritable backups directory stops the Server before any migration runs`() {
        val notADirectory = backups.resolve("file").also { it.writeText("x") }

        assertFailsWith<Exception> { start(fakePgDump("exit 0"), notADirectory) }

        assertEquals(5, currentVersion())
    }
}
