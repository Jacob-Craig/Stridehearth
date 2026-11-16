package com.jacobcraig.stridehearth.system.fit

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile

/**
 * Repairs damaged FIT files by running Garmin's **Activity Repair Tool**.
 *
 * The tool (`ActivityRepairTool.jar`, from github.com/garmin/fit-sdk-tools) is a command-line
 * program, not a library you can call from code. So for each repair this class:
 * 1. creates a new, empty temporary directory,
 * 2. writes the upload into it as `upload.fit`,
 * 3. runs `java -jar ActivityRepairTool.jar upload.fit` as a separate process, waiting at most [timeout],
 * 4. reads back the repaired file the tool wrote next to the input. Garmin doesn't document the
 *    output file name, so any *new* file in the directory is taken to be the result,
 * 5. always deletes the temporary directory, whether the repair worked or not.
 *
 * It never throws. Every failure (timeout, non-zero exit code, missing output, I/O error) is logged
 * and reported as `null`, meaning "could not repair".
 *
 * **Position in the file:** works on the raw bytes of the whole file. It knows nothing about
 * messages. The caller decodes the result again.
 *
 * Instances are built by [FitRepairConfiguration] when `stridehearth.fit.repair-tool-path` is set.
 *
 * @property toolJar path to `ActivityRepairTool.jar`.
 * @property timeout the longest the tool may run before it is killed.
 * @property javaExecutable the `java` program used to run the JAR. It defaults to the Java this app
 *   is running on. Tests swap in a stand-in script.
 */
class ActivityRepairToolRepairer(
    private val toolJar: Path,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val javaExecutable: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
) : FitFileRepairer {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Runs the repair tool on [bytes] in a temporary directory that only this call uses.
     *
     * @param bytes the complete damaged FIT file.
     * @return the repaired bytes, or `null` if the tool timed out, failed, or wrote no output.
     */
    override fun repair(bytes: ByteArray): ByteArray? {
        val workDir =
            try {
                Files.createTempDirectory("fit-repair-")
            } catch (e: IOException) {
                log.warn("Could not create temp directory for FIT repair", e)
                return null
            }
        try {
            val input = workDir.resolve("upload.fit")
            Files.write(input, bytes)

            // Start the tool as a separate operating-system process; its console output is discarded.
            val process =
                ProcessBuilder(javaExecutable, "-jar", toolJar.toAbsolutePath().toString(), input.toString())
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()

            // waitFor returns false if the process is still running when the timeout expires.
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                log.warn("ActivityRepairTool timed out after {}", timeout)
                return null
            }
            if (process.exitValue() != 0) {
                log.warn("ActivityRepairTool exited with code {}", process.exitValue())
                return null
            }

            // The directory started empty apart from our input, so any other file is the tool's output.
            val output =
                Files
                    .list(workDir)
                    .use { files ->
                        files.filter { it != input && it.isRegularFile() }.toList()
                    }.maxByOrNull { Files.getLastModifiedTime(it) }
            if (output == null) {
                log.warn("ActivityRepairTool produced no output file")
                return null
            }
            return Files.readAllBytes(output)
        } catch (e: IOException) {
            log.warn("ActivityRepairTool failed", e)
            return null
        } catch (e: InterruptedException) {
            // Restore the thread's "interrupted" flag so callers further up can see the interruption.
            Thread.currentThread().interrupt()
            return null
        } finally {
            // `finally` always runs, even after a `return` above, so temp files never pile up.
            workDir.toFile().deleteRecursively()
        }
    }
}
