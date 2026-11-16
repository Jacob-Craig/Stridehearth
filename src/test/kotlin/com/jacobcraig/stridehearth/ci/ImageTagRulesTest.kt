package com.jacobcraig.stridehearth.ci

import com.jacobcraig.stridehearth.testutils.TestProcessUtils.ProcessResult
import com.jacobcraig.stridehearth.testutils.TestProcessUtils.findProjectRoot
import com.jacobcraig.stridehearth.testutils.TestProcessUtils.runProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `.github/scripts/image-tag-rules.sh` decides which image tags a push publishes (ADR-0009). `main` publishes `edge`.
 * A Release tag must sit on `main` or on its own `release/X.Y.x` branch, and its floating tags (`X.Y`, `X`, `latest`)
 * move only when it is the highest Release in their range, so a hotfix to an old line never drags them backwards.
 */
class ImageTagRulesTest {
    @TempDir
    lateinit var repo: File

    private val script = File(findProjectRoot(), ".github/scripts/image-tag-rules.sh")

    private fun git(vararg args: String): String {
        val result =
            runProcess(
                listOf(
                    "git",
                    "-c",
                    "user.name=Test",
                    "-c",
                    "user.email=test@example.com",
                    "-c",
                    "commit.gpgsign=false",
                    "-c",
                    "tag.gpgsign=false",
                ) +
                    args,
                workingDir = repo,
            )
        assertEquals(0, result.exitCode, "git ${args.joinToString(" ")} failed: ${result.output}")
        return result.stdout.trim()
    }

    /** Makes a commit and returns its SHA. */
    private fun commit(message: String): String {
        git("commit", "--allow-empty", "-q", "-m", message)
        return git("rev-parse", "HEAD")
    }

    /** Points CI's view of a remote branch (as `actions/checkout` with full history leaves it) at [sha]. */
    private fun remoteBranch(
        name: String,
        sha: String,
    ) = git("update-ref", "refs/remotes/origin/$name", sha)

    private fun tag(
        name: String,
        sha: String,
    ) = git("tag", name, sha)

    private fun rules(
        ref: String,
        sha: String,
    ): ProcessResult =
        runProcess(
            listOf("bash", script.path),
            workingDir = repo,
            environment =
                mapOf(
                    "GITHUB_REF" to ref,
                    "GITHUB_SHA" to sha,
                ),
        )

    private fun assertRules(
        expected: List<String>,
        ref: String,
        sha: String,
    ) {
        val result = rules(ref, sha)
        assertEquals(0, result.exitCode, "image-tag-rules.sh failed: ${result.output}")
        assertEquals(expected, result.stdout.lines().filter { it.isNotBlank() })
    }

    private fun assertRefused(
        ref: String,
        sha: String,
        reason: String,
    ) {
        val result = rules(ref, sha)
        assertNotEquals(0, result.exitCode, "Expected $ref to be refused. Output: ${result.output}")
        assertTrue(result.output.contains(reason), "Expected the refusal to say `$reason`. Output: ${result.output}")
    }

    private fun initRepo(): String {
        git("init", "-q", "-b", "main")
        return commit("first")
    }

    @Test
    fun `a push to main publishes edge`() {
        val sha = initRepo()
        remoteBranch("main", sha)
        assertRules(listOf("type=edge,branch=main"), "refs/heads/main", sha)
    }

    @Test
    fun `the first Release on main publishes X Y Z, X Y, X and latest`() {
        val sha = initRepo()
        remoteBranch("main", sha)
        tag("v0.1.0", sha)
        assertRules(
            listOf(
                "type=semver,pattern={{version}}",
                "type=semver,pattern={{major}}.{{minor}}",
                "type=semver,pattern={{major}}",
                "type=raw,value=latest",
            ),
            "refs/tags/v0.1.0",
            sha,
        )
    }

    @Test
    fun `a hotfix on its release branch moves X Y but leaves X and latest on the newer Release`() {
        val v120 = initRepo()
        tag("v1.2.0", v120)
        val v130 = commit("feature")
        tag("v1.3.0", v130)
        remoteBranch("main", v130)
        git("checkout", "-q", "-b", "release/1.2.x", v120)
        val hotfix = commit("fix")
        remoteBranch("release/1.2.x", hotfix)
        tag("v1.2.1", hotfix)

        assertRules(listOf("type=semver,pattern={{version}}", "type=semver,pattern={{major}}.{{minor}}"), "refs/tags/v1.2.1", hotfix)
    }

    @Test
    fun `a fix to an older major moves its X but not latest`() {
        val v140 = initRepo()
        tag("v1.4.0", v140)
        val v200 = commit("breaking")
        tag("v2.0.0", v200)
        remoteBranch("main", v200)
        git("checkout", "-q", "-b", "release/1.4.x", v140)
        val hotfix = commit("fix")
        remoteBranch("release/1.4.x", hotfix)
        tag("v1.4.1", hotfix)

        assertRules(
            listOf("type=semver,pattern={{version}}", "type=semver,pattern={{major}}.{{minor}}", "type=semver,pattern={{major}}"),
            "refs/tags/v1.4.1",
            hotfix,
        )
    }

    @Test
    fun `a pre-release publishes only its own version`() {
        val v100 = initRepo()
        tag("v1.0.0", v100)
        val rc = commit("next")
        remoteBranch("main", rc)
        tag("v2.0.0-rc.1", rc)
        assertRules(listOf("type=semver,pattern={{version}}"), "refs/tags/v2.0.0-rc.1", rc)
    }

    @Test
    fun `a Release tag on any other branch is refused`() {
        val base = initRepo()
        remoteBranch("main", base)
        git("checkout", "-q", "-b", "experiment")
        val elsewhere = commit("experiment")
        tag("v0.2.0", elsewhere)
        assertRefused("refs/tags/v0.2.0", elsewhere, "not on main or release/0.2.x")
    }

    @Test
    fun `a Release tag on another line's release branch is refused`() {
        val base = initRepo()
        remoteBranch("main", base)
        git("checkout", "-q", "-b", "release/1.3.x")
        val fix = commit("fix")
        remoteBranch("release/1.3.x", fix)
        tag("v1.2.9", fix)
        assertRefused("refs/tags/v1.2.9", fix, "not on main or release/1.2.x")
    }

    @Test
    fun `tags that are not vX Y Z and branches other than main are refused`() {
        val sha = initRepo()
        remoteBranch("main", sha)
        tag("v1.2", sha)
        assertRefused("refs/tags/v1.2", sha, "not a vX.Y.Z Release tag")
        assertRefused("refs/heads/feature", sha, "publishes no image")
    }
}
