package com.jacobcraig.stridehearth.system

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The application's [Clock], so a test can fix "now" for code that asks what day it is. */
@Configuration
class ClockConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
