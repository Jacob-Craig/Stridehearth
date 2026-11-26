package com.jacobcraig.stridehearth.schema

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
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The PostgreSQL the sample compose file runs (deploy/compose.yaml), so the dump comes from the client's own major. */
private val COMPOSE_POSTGIS_IMAGE = DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres")

/**
 * ADR-0010 at startup: pending migrations on an existing database are preceded by a backup, and a failed backup stops
 * the Server before any migration runs. A stand-in script plays `pg_dump`, so these need no PostgreSQL client.
 */
class PreMigrationBackupIntegrationTest {
    private val postgres = PostgreSQLContainer(COMPOSE_POSTGIS_IMAGE)

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
    fun `with the backup disabled, pending migrations run without calling pg_dump`() {
        SpringApplicationBuilder(StridehearthApplication::class.java)
            .run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--spring.data.redis.host=redis.invalid",
                "--server.port=0",
                "--stridehearth.backup.dir=$backups",
                "--stridehearth.backup.enabled=false",
                "--stridehearth.backup.pg-dump-command=${fakePgDump("exit 1")}",
            ).close()

        assertTrue(currentVersion()!! > 5)
        assertEquals(emptyList(), dumps())
    }

    @Test
    fun `an unwritable backups directory stops the Server before any migration runs`() {
        val notADirectory = backups.resolve("file").also { it.writeText("x") }

        assertFailsWith<Exception> { start(fakePgDump("exit 0"), notADirectory) }

        assertEquals(5, currentVersion())
    }

    /**
     * A real `pg_dump` and `pg_restore` round trip. The dump goes through the production code path (the Server's
     * startup, [PgDumpDumper], the `PG*` environment); the host has no PostgreSQL client, so the configured command is
     * a stand-in that runs the container's own `pg_dump` with that environment. The restore follows
     * docs/self-hosting.md: `createdb`, then `pg_restore --exit-on-error`, creating the database from `template0` so the dump's own CREATE EXTENSION cannot clash.
     */
    @Test
    fun `a real pg_dump taken at startup restores into a fresh database with its data and PostGIS`() {
        JdbcTemplate(dataSource()).apply {
            update("insert into app_user (email, password_hash, username) values ('a@example.com', 'x', 'athlete')")
            update(
                "insert into activity (title, started_at, distance, time_taken_seconds, user_id, source, sport) " +
                    "select 'Morning loop', '2026-01-02T03:04:05Z', 10.5, 3600, id, 'MANUAL', 'RUN' from app_user",
            )
            update(
                "insert into activity_details (activity_id, route, t, parser_version) " +
                    "select id, ST_GeomFromText('LINESTRING(-0.12 51.5, -0.11 51.51)', 4326), '{0,1}', 1 from activity",
            )
        }
        val viaContainer =
            fakePgDump(
                "out=; for a in \"\$@\"; do case \"\$a\" in --file=*) out=\"\${a#--file=}\";; esac; done\n" +
                    "exec docker exec -e PGUSER -e PGPASSWORD -e PGDATABASE ${postgres.containerId} pg_dump --format=custom > \"\$out\"",
            )

        start(viaContainer)

        val dump = Files.list(backups).use { s -> s.toList() }.single()
        assertTrue(Files.size(dump) > 0)
        postgres.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/restore.dump")
        val user = postgres.username

        fun inContainer(command: String) =
            postgres.execInContainer("sh", "-c", command).also { assertEquals(0, it.exitCode, "$command: ${it.stderr}") }
        inContainer("createdb -U $user --template=template0 restored")
        inContainer("pg_restore -U $user -d restored --exit-on-error < /tmp/restore.dump")

        val restored = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl.replaceAfterLast('/', "restored"), user, postgres.password))
        assertEquals("Morning loop", restored.queryForObject("select title from activity", String::class.java))
        assertEquals(1, restored.queryForObject("select count(*) from pg_extension where extname = 'postgis'", Int::class.java))
        assertEquals(1, restored.queryForObject("select count(*) from activity_details where ST_NPoints(route) = 2", Int::class.java))
        assertTrue(restored.queryForObject("select postgis_version()", String::class.java).orEmpty().isNotBlank())
        // The dump is of the database as it stood before the upgrade, so it restores at schema version 5.
        assertEquals(
            5,
            restored.queryForObject("select max(version::int) from flyway_schema_history where success", Int::class.java),
        )
    }
}
