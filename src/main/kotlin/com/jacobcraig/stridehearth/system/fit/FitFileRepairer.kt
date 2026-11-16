package com.jacobcraig.stridehearth.system.fit

/**
 * Something that can try to fix a **damaged** FIT file. This usually means one that was cut off
 * when the watch's battery died, or whose CRC checksum no longer matches.
 *
 * **Position in the file:** a repairer works on the raw bytes of the whole file. It runs only after
 * [FitActivityParser] has rejected the file as damaged, and never for a valid file of the wrong
 * type. [FitActivityParser] calls it at most once per parse attempt.
 *
 * This is an *interface* (a contract with no implementation), so the parsing code doesn't need to
 * know *how* repair happens. Implementations:
 * - [NoOpFitFileRepairer]: never repairs. Used when no repair tool is configured, and in tests.
 * - [ActivityRepairToolRepairer]: runs Garmin's `ActivityRepairTool.jar` as a separate process.
 *
 * `fun interface` means the interface has a single method. That allows creating one from a lambda,
 * which is handy in tests, e.g. `FitFileRepairer { bytes -> null }`.
 */
fun interface FitFileRepairer {
    /**
     * Tries to repair a damaged FIT file.
     *
     * @param bytes the complete original upload, exactly as received.
     * @return the repaired file's bytes, or `null` if it could not be repaired. The returned bytes
     *   are not guaranteed to be valid; the caller decodes them again to find out.
     */
    fun repair(bytes: ByteArray): ByteArray?
}

/**
 * A [FitFileRepairer] that never repairs anything. It always returns `null`.
 *
 * It is used when no repair tool is configured, so damaged files are rejected straight away.
 * It is an `object` (the single, automatically created instance of a stateless class, similar to a
 * static class) because it holds no state, so one shared instance is enough.
 */
object NoOpFitFileRepairer : FitFileRepairer {
    /** Always returns `null` ("could not repair"). */
    override fun repair(bytes: ByteArray): ByteArray? = null
}
