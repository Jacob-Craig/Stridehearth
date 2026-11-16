package com.jacobcraig.stridehearth.batch

open class ArchiveValidationException(
    message: String,
) : IllegalArgumentException(message)

class ZipSlipException(
    entryName: String,
) : ArchiveValidationException("Archive contains forbidden path traversal entry: $entryName")

class ArchiveTooLargeException(
    uncompressedBytes: Long,
    maxBytes: Long,
) : ArchiveValidationException("Archive uncompressed size ($uncompressedBytes bytes) exceeds limit of $maxBytes bytes")

class ArchiveTooManyFilesException(
    fileCount: Int,
    maxFiles: Int,
) : ArchiveValidationException("Archive contains $fileCount files, which exceeds limit of $maxFiles files")

class InsufficientDiskSpaceException(
    usableBytes: Long,
    requiredBytes: Long,
) : ArchiveValidationException(
        "Insufficient disk space for archive extraction: usable space is $usableBytes bytes, but $requiredBytes bytes are required",
    )
