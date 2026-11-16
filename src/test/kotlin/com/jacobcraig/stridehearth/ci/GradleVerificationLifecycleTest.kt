package com.jacobcraig.stridehearth.ci

import com.jacobcraig.stridehearth.testutils.TestProcessUtils.findProjectRoot
import com.jacobcraig.stridehearth.testutils.TestProcessUtils.runGradle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Seam 3 (issue #45): the Gradle lifecycle is the single entry point that runs every Kotlin and
 * TypeScript gate and packages the SPA, so `./gradlew check` / `./gradlew bootJar` cannot succeed
 * while a gate is skipped.
 */
class GradleVerificationLifecycleTest {
    private fun plannedTasks(vararg tasks: String): List<String> {
        val result = runGradle(*tasks, "--dry-run")
        assertEquals(0, result.exitCode, "Dry run of ${tasks.joinToString(" ")} failed. Output:\n${result.output}")
        return result.stdout
            .lines()
            .map { it.trim() }
            .filter { it.endsWith(" SKIPPED") }
            .map { it.removeSuffix(" SKIPPED") }
    }

    private val frontendGates =
        listOf(":frontendLint", ":frontendTypecheck", ":frontendTest", ":frontendCodegenCheck", ":frontendBuild")

    @Test
    fun `check runs every Kotlin and TypeScript gate`() {
        val planned = plannedTasks("check")
        (frontendGates + listOf(":ktlintCheck", ":test")).forEach { task ->
            assertTrue(planned.contains(task), "`./gradlew check` must run $task. Planned tasks:\n$planned")
        }
    }

    @Test
    fun `check leaves out the Heatmap performance harness, which performanceTest runs on its own`() {
        // ADR-0007: the ~10k-Activity harness is a separate `performanceTest` task; `check` runs only its smoke version.
        val check = plannedTasks("check")
        assertFalse(check.contains(":performanceTest"), "`./gradlew check` must not run :performanceTest. Planned tasks:\n$check")
        val performance = plannedTasks("performanceTest")
        assertTrue(performance.contains(":performanceTest"), "`./gradlew performanceTest` must plan itself. Planned tasks:\n$performance")
        assertFalse(performance.contains(":test"), "`./gradlew performanceTest` must not run :test. Planned tasks:\n$performance")
    }

    @Test
    fun `bootJar runs the frontend gates and bundles the SPA through processResources`() {
        val planned = plannedTasks("bootJar")
        (frontendGates + ":processResources").forEach { task ->
            assertTrue(planned.contains(task), "`./gradlew bootJar` must run $task. Planned tasks:\n$planned")
        }
        assertTrue(
            planned.indexOf(":frontendBuild") < planned.indexOf(":processResources"),
            "frontendBuild must run before processResources packages its output. Planned tasks:\n$planned",
        )
    }

    @Test
    fun `compiled SPA is packaged onto the main runtime classpath under static`() {
        val indexHtml = javaClass.getResource("/static/index.html")
        assertNotNull(indexHtml, "processResources must package frontend/dist into static/ (BOOT-INF/classes/static in bootJar)")
        val html = indexHtml!!.readText()
        assertTrue(html.contains("id=\"root\""), "Packaged index.html must be the SPA shell. Was:\n$html")
        val assetPath = Regex("""/assets/[^"]+\.js""").find(html)?.value
        assertNotNull(assetPath, "Packaged index.html must reference the Vite-built JS bundle. Was:\n$html")
        assertNotNull(javaClass.getResource("/static$assetPath"), "Bundle $assetPath referenced by index.html must be packaged")
    }

    @Test
    fun `frontendCodegenCheck passes for the committed client and fails when the generated client is stale`(
        @TempDir tempDir: File,
    ) {
        val committed = File(findProjectRoot(), "frontend/src/api/generated")
        val generated = File(tempDir, "generated")
        committed.copyRecursively(generated)

        val fresh = runGradle("frontendCodegenCheck", "-PfrontendGeneratedDir=${generated.absolutePath}")
        assertEquals(0, fresh.exitCode, "Committed generated client must match codegen of frontend/openapi.json. Output:\n${fresh.output}")

        val types = File(generated, "types.gen.ts")
        types.writeText(types.readText() + "\nexport type HandEditedDrift = string;\n")

        val stale = runGradle("frontendCodegenCheck", "-PfrontendGeneratedDir=${generated.absolutePath}")
        assertNotEquals(0, stale.exitCode, "frontendCodegenCheck must fail on a stale client. Output:\n${stale.output}")
        assertTrue(stale.output.contains("types.gen.ts"), "Failure must name the stale file. Output:\n${stale.output}")
    }
}
