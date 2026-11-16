package com.jacobcraig.stridehearth.batch

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

fun interface DiskSpaceChecker {
    fun getUsableSpace(path: Path): Long
}

@Component
class DefaultDiskSpaceChecker : DiskSpaceChecker {
    override fun getUsableSpace(path: Path): Long {
        var p = path.toAbsolutePath()
        while (!Files.exists(p) && p.parent != null) {
            p = p.parent
        }
        return p.toFile().usableSpace
    }
}
