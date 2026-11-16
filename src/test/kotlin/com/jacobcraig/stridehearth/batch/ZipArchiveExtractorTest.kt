package com.jacobcraig.stridehearth.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveExtractorTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var properties: BatchStorageProperties
    private lateinit var diskSpaceChecker: DiskSpaceChecker
    private lateinit var extractor: ZipArchiveExtractor

    @BeforeEach
    fun setUp() {
        properties =
            BatchStorageProperties(
                tempDir = tempDir.toString(),
                maxArchiveUncompressedBytes = 500L * 1024 * 1024,
                maxArchiveFiles = 1000,
                requiredFreeDiskSpaceBuffer = 500L * 1024 * 1024,
            )
        diskSpaceChecker =
            mock {
                on { getUsableSpace(any()) } doReturn (100L * 1024 * 1024 * 1024) // 100GB
            }
        extractor = ZipArchiveExtractor(properties, diskSpaceChecker)
    }

    @Test
    fun `isZipArchive returns true for zip extension and zip mime types`() {
        assertTrue(extractor.isZipArchive("test.zip", null))
        assertTrue(extractor.isZipArchive("TEST.ZIP", null))
        assertTrue(extractor.isZipArchive("archive.something", "application/zip"))
        assertTrue(extractor.isZipArchive("archive.bin", "application/x-zip-compressed"))

        assertFalse(extractor.isZipArchive("sample.fit", "application/octet-stream"))
        assertFalse(extractor.isZipArchive("image.png", "image/png"))
        assertFalse(extractor.isZipArchive(null, null))
    }

    @Test
    fun `isValidFitEntry filters out non-FIT entries, OS metadata, and directories`() {
        assertTrue(extractor.isValidFitEntry(ZipEntry("workout.fit")))
        assertTrue(extractor.isValidFitEntry(ZipEntry("WORKOUT.FIT")))
        assertTrue(extractor.isValidFitEntry(ZipEntry("activities/2026/run.fit")))
        assertTrue(extractor.isValidFitEntry(ZipEntry("activities\\2026\\run.fit")))

        // Directory
        val dirEntry = ZipEntry("folder/").apply { isDirectory }
        assertFalse(extractor.isValidFitEntry(dirEntry))

        // macOS metadata
        assertFalse(extractor.isValidFitEntry(ZipEntry("__MACOSX/run.fit")))
        assertFalse(extractor.isValidFitEntry(ZipEntry("folder/__MACOSX/run.fit")))
        assertFalse(extractor.isValidFitEntry(ZipEntry("__MACOSX/._run.fit")))

        // .DS_Store
        assertFalse(extractor.isValidFitEntry(ZipEntry(".DS_Store")))
        assertFalse(extractor.isValidFitEntry(ZipEntry("folder/.DS_Store")))

        // AppleDouble resource forks
        assertFalse(extractor.isValidFitEntry(ZipEntry("._run.fit")))
        assertFalse(extractor.isValidFitEntry(ZipEntry("folder/._run.fit")))

        // Non-FIT formats
        assertFalse(extractor.isValidFitEntry(ZipEntry("notes.txt")))
        assertFalse(extractor.isValidFitEntry(ZipEntry("run.gpx")))
        assertFalse(extractor.isValidFitEntry(ZipEntry("summary.csv")))
    }

    @Test
    fun `inspectAndValidate blocks Zip Slip path traversal`() {
        val zipFile = createZipFile(mapOf("../evil.fit" to byteArrayOf(1, 2, 3)))
        assertThrows(ZipSlipException::class.java) {
            extractor.inspectAndValidate(zipFile, tempDir)
        }

        val nestedSlip = createZipFile(mapOf("folder/../../evil.fit" to byteArrayOf(1, 2, 3)))
        assertThrows(ZipSlipException::class.java) {
            extractor.inspectAndValidate(nestedSlip, tempDir)
        }
    }

    @Test
    fun `inspectAndValidate rejects archive exceeding 1000 files`() {
        val entries = (1..1001).associate { "run_$it.fit" to byteArrayOf(1) }
        val zipFile = createZipFile(entries)

        val ex =
            assertThrows(ArchiveTooManyFilesException::class.java) {
                extractor.inspectAndValidate(zipFile, tempDir)
            }
        assertTrue(ex.message!!.contains("exceeds limit of 1000 files"))
    }

    @Test
    fun `inspectAndValidate rejects archive exceeding 500MB uncompressed size`() {
        val zipFile = createZipFileWithClaimedSize("huge.fit", 501L * 1024 * 1024)

        val ex =
            assertThrows(ArchiveTooLargeException::class.java) {
                extractor.inspectAndValidate(zipFile, tempDir)
            }
        assertTrue(ex.message!!.contains("exceeds limit of 524288000 bytes"))
    }

    @Test
    fun `inspectAndValidate rejects archive when usable space is insufficient`() {
        // Mock usable space: 100 bytes < uncompressedSize + 500MB
        doReturn(100L).`when`(diskSpaceChecker).getUsableSpace(any())

        val zipFile = createZipFile(mapOf("run.fit" to byteArrayOf(1, 2, 3)))

        val ex =
            assertThrows(InsufficientDiskSpaceException::class.java) {
                extractor.inspectAndValidate(zipFile, tempDir)
            }
        assertTrue(ex.message!!.contains("Insufficient disk space"))
    }

    @Test
    fun `extractValidEntries stages only valid FIT files into batch directory`() {
        val storageService = BatchStorageService(properties)
        val zipFile =
            createZipFile(
                mapOf(
                    "morning.fit" to byteArrayOf(1, 2, 3, 4),
                    "sub/evening.fit" to byteArrayOf(5, 6, 7, 8),
                    "__MACOSX/._morning.fit" to byteArrayOf(9),
                    ".DS_Store" to byteArrayOf(10),
                    "readme.txt" to byteArrayOf(11),
                ),
            )

        val staged = extractor.extractValidEntries(zipFile, 123L, storageService)
        assertEquals(2, staged.size)
        assertEquals("morning.fit", staged[0].originalFilename)
        assertEquals("evening.fit", staged[1].originalFilename)

        assertTrue(Files.exists(staged[0].stagedPath))
        assertTrue(Files.exists(staged[1].stagedPath))
        assertEquals(4, Files.size(staged[0].stagedPath))
        assertEquals(4, Files.size(staged[1].stagedPath))
    }

    private fun createZipFile(entries: Map<String, ByteArray>): File {
        val file = Files.createTempFile(tempDir, "test-", ".zip").toFile()
        ZipOutputStream(file.outputStream()).use { zos ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    private fun createZipFileWithClaimedSize(
        entryName: String,
        claimedSize: Long,
    ): File {
        val file = Files.createTempFile(tempDir, "test-", ".zip").toFile()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val entry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()
        }
        val bytes = baos.toByteArray()
        for (i in 0 until bytes.size - 28) {
            if (bytes[i] == 0x50.toByte() &&
                bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x01.toByte() &&
                bytes[i + 3] == 0x02.toByte()
            ) {
                val offset = i + 24
                bytes[offset] = (claimedSize and 0xFF).toByte()
                bytes[offset + 1] = ((claimedSize shr 8) and 0xFF).toByte()
                bytes[offset + 2] = ((claimedSize shr 16) and 0xFF).toByte()
                bytes[offset + 3] = ((claimedSize shr 24) and 0xFF).toByte()
                break
            }
        }
        file.writeBytes(bytes)
        return file
    }
}
