package com.jacobcraig.stridehearth.system.fit

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path

/**
 * Spring configuration that decides which [FitFileRepairer] the app uses.
 *
 * A `@Configuration` class is a place to write `@Bean` methods. At startup Spring calls each one
 * once, and gives the returned object to anything that asks for that type. Here, that is
 * [FitActivityParser].
 *
 * The choice is made from the optional `stridehearth.fit.repair-tool-path` setting in
 * `application.yaml` (or the `FIT_REPAIR_TOOL_PATH` environment variable). A missing tool never
 * stops the app from starting.
 */
@Configuration
class FitRepairConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Creates the app-wide [FitFileRepairer].
     *
     * @param repairToolPath the value of `stridehearth.fit.repair-tool-path`. The `:` at the end
     *   of the `@Value` expression means "default to an empty string if the setting is missing".
     * @return an [ActivityRepairToolRepairer] if the path points to an existing file. Otherwise
     *   [NoOpFitFileRepairer] (repair turned off): if the setting is blank, or if the file is
     *   missing, in which case a warning is logged.
     */
    @Bean
    fun fitFileRepairer(
        @Value("\${stridehearth.fit.repair-tool-path:}") repairToolPath: String,
    ): FitFileRepairer {
        if (repairToolPath.isBlank()) return NoOpFitFileRepairer
        val jar = Path.of(repairToolPath)
        if (!Files.isRegularFile(jar)) {
            log.warn("FIT repair tool not found at {}; FIT repair disabled", jar)
            return NoOpFitFileRepairer
        }
        return ActivityRepairToolRepairer(jar)
    }
}
