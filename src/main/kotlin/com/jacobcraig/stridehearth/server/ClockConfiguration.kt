package com.jacobcraig.stridehearth.server

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The application's "now", as a bean so tests can fix it. */
@Configuration
class ClockConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
