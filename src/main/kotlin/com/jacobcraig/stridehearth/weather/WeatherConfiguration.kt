package com.jacobcraig.stridehearth.weather

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Builds the Open-Meteo client only for `stridehearth.weather.provider=open-meteo` (ADR-0017). With the default
 * `none` no HTTP client exists in the application context. Any other value is not recognised and builds nothing.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["stridehearth.weather.provider"], havingValue = "open-meteo")
class WeatherConfiguration {
    @Bean
    fun openMeteoClient(
        @Value("\${stridehearth.weather.open-meteo.base-url:https://archive-api.open-meteo.com}") baseUrl: String,
        @Value("\${stridehearth.weather.open-meteo.timeout:PT10S}") timeout: Duration,
    ): OpenMeteoClient = OpenMeteoClient(baseUrl, timeout)
}
