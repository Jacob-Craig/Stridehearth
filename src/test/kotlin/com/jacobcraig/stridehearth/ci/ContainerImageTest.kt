package com.jacobcraig.stridehearth.ci

import com.jacobcraig.stridehearth.testutils.TestProcessUtils.findProjectRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The release image's Dockerfile (ADR-0009): a multi-stage build onto a Temurin 17 JRE, the Spring Boot jar's layers
 * copied in dependency-first order, a non-root user, a healthcheck, the licence and NOTICE shipped, and no secrets.
 * Building and running the image is checked by hand and by CI; this test keeps those properties from regressing.
 */
class ContainerImageTest {
    private val root = findProjectRoot()

    /** One Dockerfile instruction with its continuation lines joined, e.g. `COPY --from=build a b`. */
    private data class Instruction(
        val keyword: String,
        val arguments: String,
    )

    private fun instructions(): List<Instruction> {
        val dockerfile = File(root, "Dockerfile")
        assertTrue(dockerfile.isFile, "The release Dockerfile must live at the repository root")
        return dockerfile
            .readLines()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")
            .replace(Regex("\\\\\\s*\\n"), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> Instruction(line.substringBefore(' ').uppercase(), line.substringAfter(' ', "").trim()) }
    }

    /** Instructions grouped by build stage, each starting at its FROM. */
    private fun stages(): List<List<Instruction>> =
        instructions().fold(mutableListOf<MutableList<Instruction>>()) { stages, instruction ->
            if (instruction.keyword == "FROM") stages.add(mutableListOf())
            stages.lastOrNull()?.add(instruction)
            stages
        }

    private fun runtimeStage() = stages().last()

    @Test
    fun `the image is built in one stage and runs on a Temurin 17 JRE in another`() {
        val stages = stages()
        assertTrue(stages.size >= 2, "The Dockerfile must be multi-stage. Stages: ${stages.map { it.first() }}")
        val runtimeBase = runtimeStage().first().arguments
        assertTrue(runtimeBase.startsWith("eclipse-temurin:17-jre"), "The runtime stage must use a Temurin 17 JRE: $runtimeBase")
    }

    @Test
    fun `every base image is pinned to a digest so Dependabot can update it`() {
        stages()
            .map {
                it
                    .first()
                    .arguments
                    .split(' ')
                    .first { arg -> !arg.startsWith("--") }
            }.forEach { image ->
                assertTrue(Regex("^[^@\\s]+:[^@\\s]+@sha256:[0-9a-f]{64}$").matches(image), "Pin `$image` as name:tag@sha256:digest")
            }
        @Suppress("UNCHECKED_CAST")
        val updates = Yaml().load<Map<String, Any>>(File(root, ".github/dependabot.yml").readText())["updates"] as List<Map<String, Any>>
        val dockerDirectories = updates.first { it["package-ecosystem"] == "docker" }["directories"] as List<*>
        assertTrue(dockerDirectories.contains("/"), "Dependabot's docker ecosystem must watch the root Dockerfile: $dockerDirectories")
    }

    @Test
    fun `the Spring Boot jar's layers are copied least-changing first`() {
        val layerCopies =
            runtimeStage()
                .filter { it.keyword == "COPY" && it.arguments.startsWith("--from=") }
                .mapNotNull { copy -> LAYERS.firstOrNull { copy.arguments.contains("/$it/") } }
        assertEquals(LAYERS, layerCopies, "The runtime stage must copy the extracted layers in this order")
    }

    @Test
    fun `the container runs as a non-root user`() {
        val user = runtimeStage().lastOrNull { it.keyword == "USER" }?.arguments
        assertTrue(user != null, "The runtime stage must switch to a non-root USER")
        val name = user!!.substringBefore(':')
        assertTrue(name != "root" && name != "0", "The container must not run as root: USER $user")
    }

    @Test
    fun `a healthcheck polls the actuator health endpoint`() {
        val healthcheck = runtimeStage().singleOrNull { it.keyword == "HEALTHCHECK" }?.arguments
        assertTrue(
            healthcheck?.contains("/actuator/health") == true,
            "The runtime stage needs a HEALTHCHECK on /actuator/health: $healthcheck",
        )
    }

    @Test
    fun `the licence and NOTICE ship in the image`() {
        // NOTICE says the bundled Garmin FIT SDK is not under Apache-2.0 (docs/research/2026-fit-sdk-licence-redistribution.md).
        val copied = runtimeStage().filter { it.keyword == "COPY" && !it.arguments.startsWith("--from=") }.map { it.arguments }
        listOf("LICENSE", "NOTICE").forEach { file ->
            assertTrue(copied.any { it.split(' ').contains(file) }, "The runtime stage must COPY $file. COPY instructions: $copied")
        }
        val licences = runtimeStage().filter { it.keyword == "LABEL" }.joinToString(" ") { it.arguments }
        assertTrue(
            licences.contains("org.opencontainers.image.licenses=\"$IMAGE_LICENSES\""),
            "The image must declare its licences as \"$IMAGE_LICENSES\": $licences",
        )
    }

    @Test
    fun `the image carries no configuration secrets`() {
        val settings = instructions().filter { it.keyword == "ENV" || it.keyword == "ARG" }.map { it.arguments }
        val secretLike = Regex("(?i)password|secret|token|datasource|redis")
        assertTrue(settings.none { secretLike.containsMatchIn(it) }, "No ENV or ARG may set connection settings or secrets: $settings")

        val ignored = File(root, ".dockerignore").readLines().map { it.trim() }
        listOf(".git", ".env", "build", "frontend/node_modules").forEach { path ->
            assertTrue(ignored.contains(path), ".dockerignore must exclude $path from the build context")
        }
    }

    companion object {
        val LAYERS = listOf("dependencies", "spring-boot-loader", "snapshot-dependencies", "application")

        /** Stridehearth's own code is Apache-2.0; the bundled Garmin FIT SDK keeps its own licence (NOTICE). */
        const val IMAGE_LICENSES = "Apache-2.0 AND LicenseRef-Garmin-FIT-Protocol-License"
    }
}
