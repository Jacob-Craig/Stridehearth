package com.jacobcraig.stridehearth.lint

import com.jacobcraig.stridehearth.testutils.TestProcessUtils.findProjectRoot
import com.jacobcraig.stridehearth.testutils.TestProcessUtils.runGradle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class KtlintEnforcementTest {
    @Test
    fun `clean code passes ktlintCheck with exit code 0`() {
        val result = runGradle("ktlintCheck")
        assertEquals(0, result.exitCode, "Expected clean code to pass ktlintCheck. Output:\n${result.output}")
    }

    @Test
    fun `unformatted style violation causes ktlintCheck to fail and ktlintFormat restores exit code 0`() {
        val projectRoot = findProjectRoot()
        val testSourceDir = File(projectRoot, "src/main/kotlin/com/jacobcraig/stridehearth/lint")
        testSourceDir.mkdirs()
        val violationFile = File(testSourceDir, "TemporaryViolationSample.kt")

        try {
            // Write an unformatted style violation (redundant semicolon, trailing whitespace)
            violationFile.writeText(
                "package com.jacobcraig.stridehearth.lint;\n\nclass TemporaryViolationSample {\n    val x = 1;   \n}\n",
            )

            // 1. Verify ktlintCheck fails with non-zero exit code
            val checkResultBefore = runGradle("ktlintCheck")
            assertNotEquals(
                0,
                checkResultBefore.exitCode,
                "ktlintCheck should fail on style violation. Output:\n${checkResultBefore.output}",
            )
            assertTrue(
                checkResultBefore.output.contains("Unnecessary semicolon") ||
                    checkResultBefore.output.contains("TemporaryViolationSample.kt"),
                "Output should identify violation in TemporaryViolationSample.kt. Output:\n${checkResultBefore.output}",
            )

            // 2. Run ktlintFormat to auto-fix the style violation
            val formatResult = runGradle("ktlintFormat")
            assertEquals(
                0,
                formatResult.exitCode,
                "ktlintFormat should exit with 0. Output:\n${formatResult.output}",
            )

            // 3. Verify ktlintCheck now passes with exit code 0
            val checkResultAfter = runGradle("ktlintCheck")
            assertEquals(
                0,
                checkResultAfter.exitCode,
                "ktlintCheck should pass after ktlintFormat. Output:\n${checkResultAfter.output}",
            )
        } finally {
            if (violationFile.exists()) {
                violationFile.delete()
            }
            if (testSourceDir.exists() && testSourceDir.list().isNullOrEmpty()) {
                testSourceDir.delete()
            }
        }
    }

    @Test
    fun `clean frontend passes frontendLint and frontendTypecheck with exit code 0`() {
        val result = runGradle("frontendLint", "frontendTypecheck")
        assertEquals(0, result.exitCode, "Expected clean frontend to pass biome check and tsc --noEmit. Output:\n${result.output}")
    }

    @Test
    fun `unformatted TypeScript causes frontendLint to fail and frontendFormat restores exit code 0`() {
        val violationFile = File(findProjectRoot(), "frontend/tests/temporaryBiomeViolationSample.ts")

        try {
            // Double quotes, missing semicolon and odd indentation all violate the Biome formatter config.
            violationFile.writeText("export const sample = {\n      value: \"x\"\n}\n")

            val checkResultBefore = runGradle("frontendLint")
            assertNotEquals(
                0,
                checkResultBefore.exitCode,
                "frontendLint (biome check) should fail on a formatting violation. Output:\n${checkResultBefore.output}",
            )
            assertTrue(
                checkResultBefore.output.contains("temporaryBiomeViolationSample.ts"),
                "Output should identify the violation in temporaryBiomeViolationSample.ts. Output:\n${checkResultBefore.output}",
            )

            val formatResult = runGradle("frontendFormat")
            assertEquals(0, formatResult.exitCode, "frontendFormat should exit with 0. Output:\n${formatResult.output}")

            val checkResultAfter = runGradle("frontendLint")
            assertEquals(
                0,
                checkResultAfter.exitCode,
                "frontendLint should pass after frontendFormat. Output:\n${checkResultAfter.output}",
            )
        } finally {
            violationFile.delete()
        }
    }
}
