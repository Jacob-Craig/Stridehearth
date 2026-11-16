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
        assertConfiguresNode(steps("test"), "test")
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
                val allowed = if (file.name == "ci.yml") IMAGE_JOB_PERMISSIONS[name] else null
                if (allowed != null) {
                    assertEquals(allowed, permissions, "${file.name} job $name may widen the token only as far as publishing needs")
                } else {
                    assertTrue(
                        permissions.values.none { it == "write" },
                        "${file.name} job $name widens the token; record why in ADR-0009 or ADR-0012 and update this test",
                    )
                }
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
    fun `pushing a version tag runs the workflow`() {
        @Suppress("UNCHECKED_CAST")
        val push = (loadCiWorkflow()["on"] ?: loadCiWorkflow()[true]) as Map<String, Map<String, List<String>>>
        assertEquals(listOf("v*.*.*"), push["push"]!!["tags"], "A vX.Y.Z tag must trigger the Release build (ADR-0009)")
    }

    @Test
    fun `the jar-only artifact upload is gone`() {
        assertNull(jobs()["build-artifact"], "Images replace the jar artifact (#19)")
        jobs().keys.forEach { job ->
            assertTrue(
                steps(job).none {
                    (it["uses"] as? String).orEmpty().startsWith("actions/upload-artifact") &&
                        it["with"].toString().contains(".jar")
                },
                "$job must not upload a jar",
            )
        }
    }

    @Test
    fun `each architecture is built natively after every check passes on a push`() {
        val image = job("image")
        assertEquals("github.event_name == 'push'", image["if"], "Images are built for pushes to main and tags, never for pull requests")
        assertEquals(CHECK_JOBS, (image["needs"] as List<*>).toSet(), "image must wait for every check")
        assertEquals("\${{ matrix.runner }}", image["runs-on"])

        @Suppress("UNCHECKED_CAST")
        val include = ((image["strategy"] as Map<String, Any>)["matrix"] as Map<String, Any>)["include"] as List<Map<String, String>>
        assertEquals(
            setOf("linux/amd64" to "ubuntu-24.04", "linux/arm64" to "ubuntu-24.04-arm"),
            include.map { it["platform"] to it["runner"] }.toSet(),
            "amd64 and arm64 must each build on a native runner",
        )

        val build = step("image", "docker/build-push-action")
        assertEquals("\${{ matrix.platform }}", build["platforms"])
        assertEquals("\${{ steps.meta.outputs.labels }}", build["labels"], "Each platform image carries the OCI labels")
        assertTrue(build["outputs"].toString().contains("push-by-digest=true"), "Platform images are pushed by digest: ${build["outputs"]}")
    }

    @Test
    fun `a release tag is built only from a commit on main`() {
        val image = steps("image")
        val checkout = image.first { (it["uses"] as? String).orEmpty().startsWith("actions/checkout") }
        assertEquals(0, (checkout["with"] as Map<*, *>)["fetch-depth"], "The tag check needs main's history")
        assertTrue(
            image.any { (it["run"] as? String).orEmpty().contains("git merge-base --is-ancestor \"\$GITHUB_SHA\" origin/main") },
            "image must refuse a tag that is not on main",
        )
    }

    @Test
    fun `main publishes edge and a release tag publishes X Y Z, X Y, X and latest`() {
        val publish = job("publish-image")
        assertEquals(listOf("image"), publish["needs"])
        val metadata = step("publish-image", "docker/metadata-action")
        assertEquals("\${{ env.IMAGE }}", metadata["images"])
        assertEquals("ghcr.io/jacob-craig/stridehearth", (loadCiWorkflow()["env"] as Map<*, *>)["IMAGE"])
        assertEquals(
            listOf(
                "type=edge,branch=main",
                "type=semver,pattern={{version}}",
                "type=semver,pattern={{major}}.{{minor}}",
                "type=semver,pattern={{major}}",
            ),
            metadata["tags"]
                .toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() },
        )
        // `latest` follows the newest semver tag (metadata-action's `flavor: latest=auto` default), never `edge`.
        assertNull(metadata["flavor"], "Keep metadata-action's default latest=auto")
        assertTrue(
            steps("publish-image").any { (it["run"] as? String).orEmpty().contains("docker buildx imagetools create") },
            "publish-image must join the platform digests into one multi-arch tag set",
        )
    }

    @Test
    fun `images declare both licences in their OCI labels`() {
        listOf("image", "publish-image").forEach { job ->
            val metadata = step(job, "docker/metadata-action")
            assertTrue(
                metadata["labels"].toString().contains("org.opencontainers.image.licenses=\${{ env.IMAGE_LICENSES }}"),
                "$job must override the repository's Apache-2.0 licence label: ${metadata["labels"]}",
            )
        }
        assertEquals(ContainerImageTest.IMAGE_LICENCES, (loadCiWorkflow()["env"] as Map<*, *>)["IMAGE_LICENSES"])
    }

    @Test
    fun `the published image carries a build provenance attestation`() {
        val attest = step("publish-image", "actions/attest")
        assertEquals("\${{ env.IMAGE }}", attest["subject-name"])
        assertTrue(attest["subject-digest"].toString().isNotBlank(), "The attestation must name the multi-arch digest")
        assertEquals(true, attest["push-to-registry"])
    }

    private fun job(name: String): Map<*, *> = jobs()[name] as? Map<*, *> ?: error("ci.yml has no `$name` job")

    /** The `with` inputs of the first step in [job] that uses [action]. */
    private fun step(
        job: String,
        action: String,
    ): Map<*, *> {
        val step = steps(job).firstOrNull { (it["uses"] as? String).orEmpty().startsWith("$action@") }
        assertNotNull(step, "$job must use $action")
        return step!!["with"] as Map<*, *>
    }

    companion object {
        private val CHECK_JOBS = setOf("lint", "frontend", "test", "gitleaks", "activity-file-guard")

        /** The only jobs that may write: pushing to GHCR, and signing and storing the attestation (ADR-0009). */
        private val IMAGE_JOB_PERMISSIONS =
            mapOf(
                "image" to mapOf("contents" to "read", "packages" to "write"),
                "publish-image" to mapOf("contents" to "read", "packages" to "write", "id-token" to "write", "attestations" to "write"),
            )
    }
}
