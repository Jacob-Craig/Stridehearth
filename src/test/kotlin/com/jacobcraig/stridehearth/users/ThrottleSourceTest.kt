package com.jacobcraig.stridehearth.users

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class ThrottleSourceTest {
    @Test
    fun `an IPv4 address is its own source`() {
        assertEquals("203.0.113.7", throttleSourceOf("203.0.113.7"))
    }

    @Test
    fun `every IPv6 address in one 64-bit network is the same source`() {
        val source = throttleSourceOf("2001:db8:1:2:aaaa:bbbb:cccc:dddd")
        assertEquals("2001:db8:1:2:0:0:0:0/64", source)
        assertEquals(source, throttleSourceOf("2001:db8:1:2::1"))
        assertEquals("2001:db8:1:3:0:0:0:0/64", throttleSourceOf("2001:db8:1:3::1"))
    }

    @Test
    fun `an IPv4-mapped IPv6 address is the IPv4 source`() {
        assertEquals("203.0.113.7", throttleSourceOf("::ffff:203.0.113.7"))
    }

    @Test
    fun `a value that is not an IP literal is never looked up and is its own source`() {
        assertEquals("localhost", throttleSourceOf("localhost"))
    }

    @Test
    fun `the throttled message names the whole minutes to wait, at least one`() {
        assertEquals("Too many attempts. Please try again in 1 minute.", tooManyAttemptsMessage(Duration.ofSeconds(5)))
        assertEquals("Too many attempts. Please try again in 1 minute.", tooManyAttemptsMessage(Duration.ofSeconds(60)))
        assertEquals("Too many attempts. Please try again in 2 minutes.", tooManyAttemptsMessage(Duration.ofSeconds(61)))
        assertEquals("Too many attempts. Please try again in 15 minutes.", tooManyAttemptsMessage(Duration.ofMinutes(15)))
        assertEquals("Too many attempts. Please try again in 59 minutes.", tooManyAttemptsMessage(Duration.ofSeconds(3_500)))
        assertEquals("Too many attempts. Please try again in 1 hour.", tooManyAttemptsMessage(Duration.ofHours(1)))
        assertEquals("Too many attempts. Please try again in 1 hour.", tooManyAttemptsMessage(Duration.ofSeconds(3_599)))
        assertEquals("Too many attempts. Please try again in 2 hours.", tooManyAttemptsMessage(Duration.ofMinutes(61)))
    }
}
