package com.jacobcraig.stridehearth.system.fit

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ActivityRepairToolRepairerTest {
    @TempDir
    lateinit var dir: Path

    private val bytes = byteArrayOf(1, 2, 3, 4)

    @BeforeEach
    fun unixOnly() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
    }

    // stands in for `java`; receives: -jar <tool.jar> <input.fit>
    private fun fakeJava(body: String): String {
        val script = dir.resolve("fake-java.sh")
        Files.writeString(script, "#!/bin/sh\n$body\n")
        script.toFile().setExecutable(true)
        return script.toString()
    }

    private fun repairer(
        body: String,
        timeout: Duration = Duration.ofSeconds(10),
    ) = ActivityRepairToolRepairer(dir.resolve("ActivityRepairTool.jar"), timeout, fakeJava(body))

    @Test
    fun `returns the file written next to the input`() {
        val result = repairer("cp \"\$3\" \"\${3%.fit}_repaired.fit\"").repair(bytes)
        assertContentEquals(bytes, result)
    }

    @Test
    fun `non zero exit returns null`() {
        assertNull(repairer("exit 1").repair(bytes))
    }

    @Test
    fun `no output file returns null`() {
        assertNull(repairer("exit 0").repair(bytes))
    }

    @Test
    fun `timeout returns null`() {
        assertNull(repairer("sleep 5", timeout = Duration.ofMillis(200)).repair(bytes))
    }

    @Test
    fun `configuration falls back to no-op without a path`() {
        assertSame(NoOpFitFileRepairer, FitRepairConfiguration().fitFileRepairer(""))
    }

    @Test
    fun `configuration falls back to no-op when jar is missing`() {
        assertSame(NoOpFitFileRepairer, FitRepairConfiguration().fitFileRepairer(dir.resolve("missing.jar").toString()))
    }

    @Test
    fun `configuration uses the repair tool when jar exists`() {
        val jar = Files.createFile(dir.resolve("ActivityRepairTool.jar"))
        assertIs<ActivityRepairToolRepairer>(FitRepairConfiguration().fitFileRepairer(jar.toString()))
    }
}
