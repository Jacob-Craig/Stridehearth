package com.jacobcraig.stridehearth

import com.jacobcraig.stridehearth.upload.storage.StorageProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(StorageProperties::class)
class StridehearthApplication

fun main(args: Array<String>) {
    runApplication<StridehearthApplication>(*args)
}
