package com.jacobcraig.stridehearth.ci

import com.jacobcraig.stridehearth.testutils.TestProcessUtils.findProjectRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

class GitHubActionsWorkflowTest {
    private fun assertConfiguresJava17(steps: List<Map<String, Any>>) {
        val hasSetupJava =
            steps.any { step ->
                val uses = step["uses"] as? String ?: ""
                val with = step["with"] as? Map<*, *>
                uses.startsWith("actions/setup-java") &&
                    with?.get("java-version")?.toString() == "17" &&
                    with["distribution"]?.toString() == "temurin" &&
                    with["cache"]?.toString() == "gradle"
            }
        assertTrue(hasSetupJava, "Job must configure actions/setup-java with Java 17 temurin and gradle cache")
    }

    private fun assertConfiguresNode(
        steps: List<Map<String, Any>>,
        jobName: String,
    ) {
        val hasSetupNode =
            steps.any { step ->
                val uses = step["uses"] as? String ?: ""
                val with = step["with"] as? Map<*, *>
                uses.startsWith("actions/setup-node") &&
                    with?.get("node-version-file")?.toString() == "frontend/.nvmrc" &&
                    with["cache"]?.toString() == "npm" &&
                    with["cache-dependency-path"]?.toString() == "frontend/package-lock.json"
            }
        assertTrue(
            hasSetupNode,
            "$jobName job must configure actions/setup-node from frontend/.nvmrc with npm cache keyed on frontend/package-lock.json",
        )
    }

    /** Resolves an `npm test` / `npm run <script>` step to the command declared in frontend/package.json. */
    private fun resolveNpmScript(run: String): String? {
        val script =
            when {
                run == "npm test" -> "test"
                run.startsWith("npm run ") -> run.removePrefix("npm run ").trim()
                else -> return null
            }
        val packageJson = File(findProjectRoot(), "frontend/package.json").readText()

        @Suppress("UNCHECKED_CAST")
        val scripts = (Yaml().load<Any>(packageJson) as Map<String, Any>)["scripts"] as Map<String, String>
        return scripts[script]
    }

    private fun jobs(): Map<String, Any> {
        @Suppress("UNCHECKED_CAST")
        return loadCiWorkflow()["jobs"] as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun steps(job: String): List<Map<String, Any>> = (jobs()[job] as Map<String, Any>)["steps"] as List<Map<String, Any>>

    private fun loadCiWorkflow(): Map<*, *> {
        val projectRoot = findProjectRoot()
        val ciFile = File(projectRoot, ".github/workflows/ci.yml")
        assertTrue(ciFile.exists(), "CI workflow file .github/workflows/ci.yml must exist")
        assertTrue(ciFile.isFile, ".github/workflows/ci.yml must be a regular file")

        val yaml = Yaml()
        val content = ciFile.readText()
        val parsed = yaml.load<Any>(content)
        assertNotNull(parsed, ".github/workflows/ci.yml must parse to non-null YAML")
        @Suppress("UNCHECKED_CAST")
        return parsed as Map<*, *>
    }

    @Test
    fun `ci workflow file exists and is valid YAML with name CI`() {
        val workflow = loadCiWorkflow()
        assertEquals("CI", workflow["name"], "Workflow name must be CI")
    }

    @Test
    fun `event triggers include push and pull_request targeting main`() {
        val workflow = loadCiWorkflow()
        val onConfig = workflow["on"] ?: workflow[true]
        assertNotNull(onConfig, "Workflow must declare 'on' trigger section")

        @Suppress("UNCHECKED_CAST")
        val onMap = onConfig as Map<String, Any>

        val pushConfig = onMap["push"]
        assertNotNull(pushConfig, "'on' triggers must include 'push'")
        @Suppress("UNCHECKED_CAST")
        val pushMap = pushConfig as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val pushBranches = pushMap["branches"] as List<String>
        assertTrue(pushBranches.contains("main"), "push trigger must target main branch")

        val prConfig = onMap["pull_request"]
        assertNotNull(prConfig, "'on' triggers must include 'pull_request'")
        @Suppress("UNCHECKED_CAST")
        val prMap = prConfig as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val prBranches = prMap["branches"] as List<String>
        assertTrue(prBranches.contains("main"), "pull_request trigger must target main branch")
    }

    @Test
    fun `concurrency group is configured and cancel-in-progress preserves mainline runs while cancelling PR runs`() {
        val workflow = loadCiWorkflow()
        val concurrencyConfig = workflow["concurrency"]
        assertNotNull(concurrencyConfig, "Workflow must declare 'concurrency' section")

        @Suppress("UNCHECKED_CAST")
        val concurrencyMap = concurrencyConfig as Map<String, Any>
        assertEquals(
            "\${{ github.workflow }}-\${{ github.ref }}",
            concurrencyMap["group"],
            "Concurrency group must be \${{ github.workflow }}-\${{ github.ref }}",
        )
        assertEquals(
            "\${{ github.ref != 'refs/heads/main' }}",
            concurrencyMap["cancel-in-progress"],
            "cancel-in-progress must preserve mainline runs while cancelling PR runs",
        )
    }

    @Test
    fun `parallel lint, frontend and test jobs are declared`() {
        val workflow = loadCiWorkflow()
        val jobsConfig = workflow["jobs"]
        assertNotNull(jobsConfig, "Workflow must declare 'jobs' section")

        @Suppress("UNCHECKED_CAST")
        val jobs = jobsConfig as Map<String, Any>
        assertTrue(jobs.containsKey("lint"), "jobs must include 'lint' job")
        assertTrue(jobs.containsKey("test"), "jobs must include 'test' job")
        assertTrue(jobs.containsKey("frontend"), "jobs must include 'frontend' job")

        @Suppress("UNCHECKED_CAST")
        val frontendJob = jobs["frontend"] as Map<String, Any>
        assertEquals("ubuntu-latest", frontendJob["runs-on"], "frontend job must run on ubuntu-latest")
        assertNull(frontendJob["needs"], "frontend job should not depend on other jobs")

        @Suppress("UNCHECKED_CAST")
        val lintJob = jobs["lint"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val testJob = jobs["test"] as Map<String, Any>

        assertEquals("ubuntu-latest", lintJob["runs-on"], "lint job must run on ubuntu-latest")
        assertEquals("ubuntu-latest", testJob["runs-on"], "test job must run on ubuntu-latest")

        // Parallel execution: neither job depends on the other
        assertNull(lintJob["needs"], "lint job should not depend on other jobs")
        assertNull(testJob["needs"], "test job should not depend on other jobs")
    }

    @Test
    fun `lint job sets up Java 17 with gradle cache and runs ktlintCheck`() {
        val workflow = loadCiWorkflow()

        @Suppress("UNCHECKED_CAST")
        val jobs = workflow["jobs"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val lintJob = jobs["lint"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val steps = lintJob["steps"] as List<Map<String, Any>>

        assertConfiguresJava17(steps)

        val hasKtlintCheck =
            steps.any { step ->
                val run = step["run"] as? String ?: ""
                run.trim() == "./gradlew ktlintCheck"
            }
        assertTrue(hasKtlintCheck, "lint job must run ./gradlew ktlintCheck")
    }

    @Test
    fun `test job sets up Java 17 verifies docker and runs check`() {
        val workflow = loadCiWorkflow()

        @Suppress("UNCHECKED_CAST")
        val jobs = workflow["jobs"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val testJob = jobs["test"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val steps = testJob["steps"] as List<Map<String, Any>>

        assertConfiguresJava17(steps)

        val hasDockerInfo =
            steps.any { step ->
                val run = step["run"] as? String ?: ""
                run.trim() == "docker info"
            }
        assertTrue(hasDockerInfo, "test job must run docker info to verify Docker for Testcontainers")

        val hasCheck =
            steps.any { step ->
                val run = step["run"] as? String ?: ""
                run.trim() == "./gradlew check"
            }
        assertTrue(hasCheck, "test job must run ./gradlew check")
    }

    @Test
    fun `build-artifact job depends on lint and test runs bootJar and uploads artifact with retention 7`() {
        val workflow = loadCiWorkflow()

        @Suppress("UNCHECKED_CAST")
        val jobs = workflow["jobs"] as Map<String, Any>
        assertTrue(jobs.containsKey("build-artifact"), "jobs must include 'build-artifact' job")

        @Suppress("UNCHECKED_CAST")
        val buildJob = jobs["build-artifact"] as Map<String, Any>

        val needs = buildJob["needs"]
        assertNotNull(needs, "build-artifact job must declare 'needs' dependencies")
        val needsList =
            when (needs) {
                is List<*> -> needs.map { it.toString() }
                is String -> listOf(needs)
                else -> emptyList()
            }
        assertTrue(needsList.contains("lint"), "build-artifact needs must include 'lint'")
        assertTrue(needsList.contains("test"), "build-artifact needs must include 'test'")
        assertTrue(needsList.contains("frontend"), "build-artifact needs must include 'frontend'")

        val condition = buildJob["if"] as? String
        assertEquals(
            "github.event_name == 'push' && github.ref == 'refs/heads/main'",
            condition,
            "build-artifact must only run on main push",
        )

        assertEquals("ubuntu-latest", buildJob["runs-on"], "build-artifact must run on ubuntu-latest")

        @Suppress("UNCHECKED_CAST")
        val steps = buildJob["steps"] as List<Map<String, Any>>

        val hasBootJar =
            steps.any { step ->
                val run = step["run"] as? String ?: ""
                run.trim() == "./gradlew bootJar"
            }
        assertTrue(hasBootJar, "build-artifact job must run ./gradlew bootJar")

        val hasArtifactUpload =
            steps.any { step ->
                val uses = step["uses"] as? String ?: ""
                val with = step["with"] as? Map<*, *>
                uses.startsWith("actions/upload-artifact") &&
                    with?.get("name")?.toString() == "stridehearth-app" &&
                    with["path"]?.toString() == "build/libs/Stridehearth-*.jar" &&
                    with["retention-days"]?.toString() == "7"
            }
        assertTrue(
            hasArtifactUpload,
            "build-artifact job must upload stridehearth-app with retention-days 7 and path build/libs/Stridehearth-*.jar",
        )
    }

    @Test
    fun `frontend job sets up Node and runs biome check, tsc --noEmit, vitest run and the codegen freshness check`() {
        val steps = steps("frontend")
        assertConfiguresNode(steps, "frontend")

        @Suppress("UNCHECKED_CAST")
        val frontendJob = jobs()["frontend"] as Map<String, Any>
        val workingDirectory = ((frontendJob["defaults"] as? Map<*, *>)?.get("run") as? Map<*, *>)?.get("working-directory")
        assertEquals("frontend", workingDirectory, "frontend job must run its steps inside frontend/")

        val commands = steps.mapNotNull { (it["run"] as? String)?.trim() }
        assertTrue(commands.contains("npm ci"), "frontend job must install dependencies with npm ci. Steps: $commands")
        val resolved = commands.mapNotNull { resolveNpmScript(it) }
        listOf("biome check", "tsc --noEmit", "vitest run", "codegen.mjs --check").forEach { gate ->
            assertTrue(
                resolved.any { it.contains(gate) },
                "frontend job must run a step resolving to `$gate`. Steps: $commands -> $resolved",
            )
        }
    }

    @Test
    fun `gradle jobs that build the frontend set up Node`() {
        listOf("test", "build-artifact").forEach { job -> assertConfiguresNode(steps(job), job) }
    }

    @Test
    fun `every workflow grants a read-only token by default and no job widens it`() {
        val workflows =
            File(findProjectRoot(), ".github/workflows")
                .listFiles { f -> f.extension == "yml" || f.extension == "yaml" }!!
                .toList()
        assertTrue(workflows.isNotEmpty(), "Expected at least one workflow")
        workflows.forEach { file ->
            val workflow = Yaml().load<Map<String, Any>>(file.readText())
            assertEquals(
                mapOf("contents" to "read"),
                workflow["permissions"],
                "${file.name} must declare top-level `permissions: contents: read` (ADR-0012)",
            )

            @Suppress("UNCHECKED_CAST")
            val jobs = workflow["jobs"] as Map<String, Map<String, Any>>
            jobs.forEach { (name, job) ->
                val permissions = job["permissions"] as? Map<*, *> ?: return@forEach
                assertTrue(
                    permissions.values.none { it == "write" },
                    "${file.name} job $name widens the token; record why in ADR-0012 and update this test",
                )
            }
        }
    }

    @Test
    fun `third-party actions are pinned to full commit SHAs`() {
        val pinned = Regex("^[^@]+@[0-9a-f]{40}$")
        jobs().keys.forEach { job ->
            steps(job).mapNotNull { it["uses"] as? String }.forEach { uses ->
                assertTrue(pinned.matches(uses), "$job uses `$uses`; pin it to a full commit SHA with a version comment")
            }
        }
    }

    @Test
    fun `gitleaks scans the tree and the pull request commits with full history`() {
        val steps = steps("gitleaks")
        val checkout = steps.first { (it["uses"] as? String).orEmpty().startsWith("actions/checkout") }
        assertEquals(0, (checkout["with"] as Map<*, *>)["fetch-depth"], "gitleaks needs full history to scan PR commits")
        val commands = steps.mapNotNull { it["run"] as? String }
        assertTrue(commands.any { it.contains("gitleaks-ci dir /repo") }, "gitleaks must scan the working tree. Steps: $commands")
        assertTrue(
            commands.any { it.contains("gitleaks-ci git /repo") && it.contains("PR_BASE_SHA..\$PR_HEAD_SHA") },
            "gitleaks must scan the pull request's commits. Steps: $commands",
        )
    }

    @Test
    fun `activity-file guard checks the pull request commits against the allowlist`() {
        val commands = steps("activity-file-guard").mapNotNull { it["run"] as? String }
        assertTrue(
            commands.any { it.contains(".github/scripts/check-activity-files.sh \"\$PR_BASE_SHA..\$PR_HEAD_SHA\"") },
            "activity-file-guard must run the guard over the pull request's commits. Steps: $commands",
        )
        assertTrue(File(findProjectRoot(), ".github/activity-file-allowlist.txt").isFile, "Allowlist must exist")
    }

    @Test
    fun `build-artifact waits for the repository guards`() {
        @Suppress("UNCHECKED_CAST")
        val needs = (jobs()["build-artifact"] as Map<String, Any>)["needs"] as List<String>
        assertTrue(needs.containsAll(listOf("gitleaks", "activity-file-guard")), "build-artifact needs: $needs")
    }
}
