package com.jacobcraig.stridehearth.testutils

import java.io.File
import java.util.concurrent.Executors

object TestProcessUtils {
    data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val output: String
            get() =
                when {
                    stderr.isEmpty() -> stdout
                    stdout.isEmpty() -> stderr
                    else -> "$stdout\n$stderr"
                }
    }

    fun findProjectRoot(): File {
        var dir = File(".").canonicalFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir
    }

    fun findGradleWrapper(projectRoot: File = findProjectRoot()): File {
        val gradlewName =
            if (System.getProperty("os.name").lowercase().contains("win")) {
                "gradlew.bat"
            } else {
                "gradlew"
            }
        return File(projectRoot, gradlewName)
    }

    fun runProcess(
        command: List<String>,
        workingDir: File = findProjectRoot(),
        environment: Map<String, String> = emptyMap(),
        stdinInput: String = "",
        redirectErrorStream: Boolean = false,
    ): ProcessResult {
        val processBuilder = ProcessBuilder(command).directory(workingDir)
        processBuilder.environment().putAll(environment)
        if (redirectErrorStream) {
            processBuilder.redirectErrorStream(true)
        }

        val process = processBuilder.start()
        if (stdinInput.isNotEmpty()) {
            process.outputStream.bufferedWriter().use {
                it.write(stdinInput)
                it.flush()
            }
        } else {
            process.outputStream.close()
        }

        val stdout: String
        val stderr: String
        if (redirectErrorStream) {
            stdout = process.inputStream.bufferedReader().readText()
            stderr = ""
        } else {
            val stderrExecutor = Executors.newSingleThreadExecutor()
            val stderrFuture =
                stderrExecutor.submit<String> {
                    process.errorStream.bufferedReader().readText()
                }
            stdout = process.inputStream.bufferedReader().readText()
            stderr = stderrFuture.get()
            stderrExecutor.shutdown()
        }
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, stdout, stderr)
    }

    fun runGradle(
        vararg args: String,
        workingDir: File = findProjectRoot(),
        environment: Map<String, String> = emptyMap(),
        redirectErrorStream: Boolean = false,
    ): ProcessResult {
        val gradlew = findGradleWrapper(workingDir).absolutePath
        val command = mutableListOf(gradlew).apply { addAll(args) }
        return runProcess(
            command = command,
            workingDir = workingDir,
            environment = environment,
            redirectErrorStream = redirectErrorStream,
        )
    }
}
