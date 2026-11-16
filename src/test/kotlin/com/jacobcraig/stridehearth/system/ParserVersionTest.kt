package com.jacobcraig.stridehearth.system

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserVersionTest {
    @Test
    fun `Parser Versions compare by revision`() {
        assertTrue(ParserVersion(0) < ParserVersion(1))
        assertTrue(ParserVersion(2) > ParserVersion(1))
        assertEquals(0, ParserVersion(1).compareTo(ParserVersion(1.toShort())))
    }
}
