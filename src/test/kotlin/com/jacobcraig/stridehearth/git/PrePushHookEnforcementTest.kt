package com.jacobcraig.stridehearth.git

import com.jacobcraig.stridehearth.testutils.TestProcessUtils
import com.jacobcraig.stridehearth.testutils.TestProcessUtils.findProjectRoot
import com.jacobcraig.stridehearth.testutils.TestProcessUtils.runGradle
import com.jacobcraig.stridehearth.testutils.TestProcessUtils.runProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class PrePushHookEnforcementTest {
    companion object {
        /** Each source is clean for every gate except the one named, so the rejection proves that gate runs. */
        @JvmStatic
        fun frontendGateViolations(): List<Arguments> =
            listOf(
                Arguments.of(
                    "biome check",
                    "temporaryPrePushLintGate.ts",
                    "export const quoted = \"double quotes violate the Biome formatter\";\n",
                ),
                Arguments.of(
                    "tsc --noEmit",
                    "temporaryPrePushTypeGate.ts",
                    "export const notANumber: number = 'text';\n",
                ),
                Arguments.of(
                    "vitest run",
                    "temporaryPrePushFailing.test.ts",
                    "import { expect, test } from 'vitest';\n\ntest('fails', () => {\n  expect(1).toBe(2);\n});\n",
                ),
            )
    }

    private fun runHook(stdinInput: String): TestProcessUtils.ProcessResult {
        val projectRoot = findProjectRoot()
        val hookFile = File(projectRoot, ".githooks/pre-push")
        return runProcess(
            command = listOf(hookFile.absolutePath, "origin", "git@github.com:dummy/repo.git"),
            workingDir = projectRoot,
            stdinInput = stdinInput,
        )
    }

    /**
     * Creates a scratch clone wired to a local bare remote with core.hooksPath pointing at this
     * repo's tracked hooks, so real `git push` refspec resolution (e.g. HEAD:main) feeds the hook.
     * The remote is seeded with `main` (bypassing hooks in setup only) so delete refspecs resolve,
     * then a further local commit is made so pushes to `main` are not no-ops.
     */
    private fun scratchRepoWithRemote(tempDir: File): File {
        val hooksDir = File(findProjectRoot(), ".githooks").absolutePath
        val remote = File(tempDir, "remote.git")
        val work = File(tempDir, "work")
        val git = { dir: File, args: List<String> ->
            val result =
                runProcess(
                    command =
                        listOf(
                            "git",
                            "-c",
                            "user.name=Test",
                            "-c",
                            "user.email=test@example.com",
                            "-c",
                            "commit.gpgsign=false",
                        ) + args,
                    workingDir = dir,
                )
            assertEquals(0, result.exitCode, "git ${args.joinToString(" ")} failed: ${result.output}")
        }
        git(tempDir, listOf("init", "--bare", "-b", "main", remote.absolutePath))
        git(tempDir, listOf("init", "-b", "feature/x", work.absolutePath))
        git(work, listOf("commit", "--allow-empty", "-m", "seed"))
        git(work, listOf("remote", "add", "origin", remote.absolutePath))
        git(work, listOf("push", "--no-verify", "origin", "HEAD:refs/heads/main"))
        git(work, listOf("config", "core.hooksPath", hooksDir))
        git(work, listOf("commit", "--allow-empty", "-m", "change to push"))
        return work
    }

    @ParameterizedTest
    @ValueSource(strings = ["HEAD:main", "HEAD:master", "HEAD:refs/heads/main", ":main"])
    fun `rejects real git push whose refspec resolves to main or master`(
        refspec: String,
        @TempDir tempDir: File,
    ) {
        val work = scratchRepoWithRemote(tempDir)
        val result = runProcess(command = listOf("git", "push", "origin", refspec), workingDir = work)
        assertNotEquals(0, result.exitCode, "Expected 'git push origin $refspec' to be rejected. Output:\n${result.output}")
        assertTrue(
            result.stderr.contains("PUSH REJECTED: Direct pushes to 'refs/heads/ma"),
            "Expected pre-push hook rejection for '$refspec'. Stderr was:\n${result.stderr}",
        )
    }

    @Test
    fun `rejects push targeting main branch with exit code 1`() {
        val stdin = "refs/heads/feature/test 1111 refs/heads/main 2222\n"
        val result = runHook(stdinInput = stdin)
        assertEquals(1, result.exitCode, "Expected push to main to be rejected with exit code 1")
        assertTrue(
            result.stderr.contains("PUSH REJECTED") && result.stderr.contains("refs/heads/main"),
            "Expected stderr to explain direct pushes to main are prohibited. Stderr was:\n${result.stderr}",
        )
    }

    @Test
    fun `rejects push targeting master branch with exit code 1`() {
        val stdin = "refs/heads/feature/test 1111 refs/heads/master 2222\n"
        val result = runHook(stdinInput = stdin)
        assertEquals(1, result.exitCode, "Expected push to master to be rejected with exit code 1")
        assertTrue(
            result.stderr.contains("PUSH REJECTED") && result.stderr.contains("refs/heads/master"),
            "Expected stderr to explain direct pushes to master are prohibited. Stderr was:\n${result.stderr}",
        )
    }

    @Test
    fun `accepts non-interactive agent push on non-main branch when clean`() {
        val stdin = "refs/heads/feature/test 1111 refs/heads/feature/test 2222\n"
        val result = runHook(stdinInput = stdin)
        assertEquals(
            0,
            result.exitCode,
            "Expected clean push to succeed. Stdout:\n${result.stdout}\nStderr:\n${result.stderr}",
        )
        assertTrue(
            result.stdout.contains("Pre-push verification passed successfully"),
            "Expected stdout to confirm pre-push verification passed. Stdout was:\n${result.stdout}",
        )
    }

    @Test
    fun `rejects push when pre-push verification fails with exit code 1`() {
        val projectRoot = findProjectRoot()
        val failingFile =
            File(
                projectRoot,
                "src/test/kotlin/com/jacobcraig/stridehearth/git/TemporaryFailingVerificationSample.kt",
            )
        val stdin = "refs/heads/feature/test 1111 refs/heads/feature/test 2222\n"
        try {
            failingFile.parentFile.mkdirs()
            failingFile.writeText("package com.jacobcraig.stridehearth.git\n\nval invalidKotlinSyntax = ;\n")

            val result = runHook(stdinInput = stdin)
            assertEquals(1, result.exitCode, "Expected hook to fail when verification fails")
            assertTrue(
                result.stderr.contains("PUSH REJECTED: Pre-push verification failed"),
                "Expected stderr to state verification failed. Stderr was:\n${result.stderr}",
            )
        } finally {
            if (failingFile.exists()) {
                failingFile.delete()
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("frontendGateViolations")
    fun `rejects push when a frontend gate fails`(
        gate: String,
        fileName: String,
        source: String,
    ) {
        val failingFile = File(findProjectRoot(), "frontend/tests/$fileName")
        val stdin = "refs/heads/feature/test 1111 refs/heads/feature/test 2222\n"
        try {
            failingFile.writeText(source)

            val result = runHook(stdinInput = stdin)
            assertEquals(1, result.exitCode, "Expected hook to reject push when `$gate` fails. Output:\n${result.output}")
            assertTrue(
                result.stderr.contains("PUSH REJECTED: Pre-push verification failed"),
                "Expected stderr to state verification failed for `$gate`. Stderr was:\n${result.stderr}",
            )
            assertTrue(
                result.output.contains(fileName),
                "Expected the `$gate` failure to point at $fileName. Output:\n${result.output}",
            )
        } finally {
            failingFile.delete()
        }
    }

    @Test
    fun `installGitHooks gradle task configures core hooksPath to dot-githooks and marks hook executable`() {
        val projectRoot = findProjectRoot()
        val originalHooksPath = runProcess(listOf("git", "config", "--get", "core.hooksPath")).stdout.trim()
        try {
            runProcess(listOf("git", "config", "core.hooksPath", ".otherhooks"))

            val result = runGradle("installGitHooks")
            assertEquals(
                0,
                result.exitCode,
                "Expected installGitHooks task to succeed. Output:\nStdout: ${result.stdout}\nStderr: ${result.stderr}",
            )

            val gitConfigResult = runProcess(listOf("git", "config", "--get", "core.hooksPath"))
            assertEquals(0, gitConfigResult.exitCode, "git config --get core.hooksPath should succeed")
            assertEquals(".githooks", gitConfigResult.stdout.trim(), "core.hooksPath should be set to .githooks")

            val hookFile = File(projectRoot, ".githooks/pre-push")
            assertTrue(hookFile.exists(), ".githooks/pre-push must exist")
            assertTrue(hookFile.canExecute(), ".githooks/pre-push must be executable")
        } finally {
            if (originalHooksPath.isNotEmpty()) {
                runProcess(listOf("git", "config", "core.hooksPath", originalHooksPath))
            } else {
                runProcess(listOf("git", "config", "core.hooksPath", ".githooks"))
            }
        }
    }
}
