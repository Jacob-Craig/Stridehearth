package com.jacobcraig.stridehearth.git

import com.jacobcraig.stridehearth.testutils.TestProcessUtils
import com.jacobcraig.stridehearth.testutils.TestProcessUtils.findProjectRoot
import com.jacobcraig.stridehearth.testutils.TestProcessUtils.runProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.ObjectMapper
import java.io.File

class ClaudeDangerousGitHookTest {
    private fun runHookWithCommand(commandText: String): TestProcessUtils.ProcessResult {
        val projectRoot = findProjectRoot()
        val hookScript = File(projectRoot, ".claude/hooks/block-dangerous-git.sh")
        assertTrue(hookScript.exists(), "Hook script should exist at ${hookScript.absolutePath}")
        assertTrue(hookScript.canExecute(), "Hook script should be executable (chmod +x)")

        // JSON payload format passed by Claude Code PreToolUse hook
        val payload = """{"tool_input":{"command":${ObjectMapper().writeValueAsString(commandText)}}}"""
        return runProcess(
            command = listOf(hookScript.absolutePath),
            workingDir = projectRoot,
            stdinInput = payload,
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "git reset --hard HEAD~1",
            "git reset --hard",
            "git clean -fd",
            "git clean -f",
            "git branch -D temp-branch",
            "git checkout .",
            "git restore .",
            // Hook bypass on push
            "git push --no-verify origin HEAD:main",
            "git push --no-verify origin feature/x",
            "git push -n origin feature/x",
            "git push -un origin feature/x",
            "git -c core.hooksPath=/dev/null push origin feature/x",
            "GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.hooksPath GIT_CONFIG_VALUE_0=/tmp git push origin feature/x",
            "git config core.hooksPath /dev/null",
            "git config --unset core.hooksPath",
            // Pushes targeting main/master
            "git push origin main",
            "git push origin master",
            "git push origin HEAD:main",
            "git push origin HEAD:master",
            "git push origin feature/x:main",
            "git push origin :main",
            "git push origin HEAD:refs/heads/main",
            "git push origin refs/heads/main",
            "git push origin --delete main",
            "git push -d origin master",
            "cd /tmp && git push origin main",
            "git -C /some/repo push origin main",
            // Force pushes (all forms, including --force-with-lease)
            "git push --force origin feature/x",
            "git push -f origin feature/x",
            "git push -uf origin feature/x",
            "git push origin +feature/x",
            "git push origin +HEAD:feature/x",
            "git push --force-with-lease origin feature/x",
            "git push --force-if-includes --force-with-lease origin feature/x",
            "git push --mirror origin",
        ],
    )
    fun `interception and exit code 2 for dangerous git commands`(dangerousCommand: String) {
        val result = runHookWithCommand(dangerousCommand)
        assertEquals(
            2,
            result.exitCode,
            "Expected exit code 2 for dangerous command '$dangerousCommand'. Stderr: ${result.stderr}",
        )
        assertTrue(
            result.stderr.contains("BLOCKED"),
            "Expected stderr to contain 'BLOCKED' for dangerous command '$dangerousCommand'. Stderr: ${result.stderr}",
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "git status",
            "./gradlew check",
            "git log -n 5 --oneline",
            "git checkout -b feature/new-idea",
            "git checkout feature/branch-name",
            "git add .",
            "git commit -m \"feat: added safe feature\"",
            "git diff HEAD~1",
            "./gradlew testClasses ktlintCheck",
            "git push origin feature/14-ci",
            "git push -u origin feature/48-activities",
            "git push --set-upstream origin feature/48-activities",
            "git push origin HEAD:feature/main-menu-fix",
            "git push origin HEAD:chore/agent-feature-branch-push-policy",
            "git push origin fix/master-detail-view",
            "git push",
            "git config --get core.hooksPath",
            "git checkout main",
            "git log origin/main..HEAD",
        ],
    )
    fun `passing benign commands with exit code 0`(benignCommand: String) {
        val result = runHookWithCommand(benignCommand)
        assertEquals(
            0,
            result.exitCode,
            "Expected exit code 0 for benign command '$benignCommand'. Stderr: ${result.stderr}",
        )
    }

    @Test
    fun `settings file has valid JSON and correct hook configuration`() {
        val projectRoot = findProjectRoot()
        val settingsFile = File(projectRoot, ".claude/settings.json")
        assertTrue(settingsFile.exists(), "Settings file should exist at ${settingsFile.absolutePath}")

        val objectMapper = ObjectMapper()
        val rootNode = objectMapper.readTree(settingsFile)
        assertNotNull(rootNode, "Settings JSON should be parseable")

        val preToolUseNode = rootNode.path("hooks").path("PreToolUse")
        assertTrue(preToolUseNode.isArray, "hooks.PreToolUse should be an array")
        assertTrue(preToolUseNode.size() > 0, "hooks.PreToolUse should contain at least one configuration")

        var foundBashHook = false
        for (i in 0 until preToolUseNode.size()) {
            val matcherNode = preToolUseNode.get(i)
            if (matcherNode.path("matcher").asString() == "Bash") {
                val hooksNode = matcherNode.path("hooks")
                assertTrue(hooksNode.isArray, "matcher 'Bash' should have an array of hooks")
                for (j in 0 until hooksNode.size()) {
                    val hook = hooksNode.get(j)
                    if (hook.path("type").asString() == "command" &&
                        hook.path("command").asString() == "\"${'$'}CLAUDE_PROJECT_DIR\"/.claude/hooks/block-dangerous-git.sh"
                    ) {
                        foundBashHook = true
                    }
                }
            }
        }

        assertTrue(
            foundBashHook,
            "Expected to find Bash PreToolUse hook running \"\$CLAUDE_PROJECT_DIR\"/.claude/hooks/block-dangerous-git.sh",
        )
    }
}
