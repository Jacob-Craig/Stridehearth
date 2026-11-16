package com.jacobcraig.stridehearth

import org.springframework.boot.fromApplication
import org.springframework.boot.with

fun main(args: Array<String>) {
    fromApplication<StridehearthApplication>().with(TestcontainersConfiguration::class).run(*args)
}
