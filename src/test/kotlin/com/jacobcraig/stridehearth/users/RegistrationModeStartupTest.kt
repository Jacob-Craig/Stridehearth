package com.jacobcraig.stridehearth.users

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.mock
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** How the Server reads its Registration Mode when it starts (ADR-0011): a misspelled mode stops it. */
@ExtendWith(OutputCaptureExtension::class)
class RegistrationModeStartupTest {
    /** Just the [RegistrationGate], the only reader of the Registration Mode, with no database behind it. */
    @Configuration(proxyBeanMethods = false)
    @Import(RegistrationGate::class)
    class GateOnly {
        @Bean
        fun userRepository(): UserRepository = mock()

        @Bean
        fun jdbcClient(): JdbcClient = mock()
    }

    private fun startServer(vararg properties: String) =
        SpringApplicationBuilder(GateOnly::class.java)
            .web(WebApplicationType.NONE)
            .properties(*properties)
            .run()

    @Test
    fun `a misspelled Registration Mode stops the Server from starting with a clear message`(output: CapturedOutput) {
        val failure = assertThrows<Exception> { startServer("stridehearth.registration-mode=opne") }

        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is UnknownRegistrationModeException })
        assertTrue(output.contains("APPLICATION FAILED TO START"), output.toString())
        assertTrue(
            output.contains("The Server's Registration Mode is set to 'opne', which is neither 'open' nor 'closed'."),
            output.toString(),
        )
        assertTrue(output.contains("Set STRIDEHEARTH_REGISTRATION_MODE"), output.toString())
    }

    @Test
    fun `an open Server warns in its startup log that open mode is not hardened for the public internet`(output: CapturedOutput) {
        startServer("stridehearth.registration-mode=open").use { context ->
            assertTrue(context.getBean(RegistrationGate::class.java).acceptsSignUps())
        }

        assertTrue(output.contains("WARN"), output.toString())
        assertTrue(output.contains("Open mode is not yet hardened for the public internet"), output.toString())
    }

    @Test
    fun `the Registration Mode setting names open or closed in any case, and closed when unset`() {
        assertEquals(RegistrationMode.OPEN, RegistrationMode.fromSetting(" Open "))
        assertEquals(RegistrationMode.CLOSED, RegistrationMode.fromSetting("CLOSED"))
        assertEquals(RegistrationMode.CLOSED, RegistrationMode.fromSetting(null))
        assertEquals(RegistrationMode.CLOSED, RegistrationMode.fromSetting(""))
    }

    @Test
    fun `any other Registration Mode setting is refused rather than treated as open`() {
        listOf("opne", "true", "yes", "public", "op_en", "open-ish").forEach { setting ->
            assertThrows<UnknownRegistrationModeException>(setting) { RegistrationMode.fromSetting(setting) }
        }
    }
}
