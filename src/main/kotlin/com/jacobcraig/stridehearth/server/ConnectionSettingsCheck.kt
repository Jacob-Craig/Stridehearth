package com.jacobcraig.stridehearth.server

import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.EnvironmentAware
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Stops startup when the Server has not been told where its database and Redis are (ADR-0009).
 *
 * The base configuration carries no credentials or `localhost` addresses, so without the `dev` profile the
 * Operator must supply them through the environment. Rather than let Spring fall back to `localhost:6379` or
 * fail on the first missing setting, this check runs before any bean is created and names every missing
 * variable at once.
 *
 * Connection details supplied as beans, such as Testcontainers `@ServiceConnection`s, count as configured.
 */
@Component
class ConnectionSettingsCheck :
    BeanFactoryPostProcessor,
    EnvironmentAware {
    private lateinit var environment: Environment

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        val missing = mutableListOf<String>()
        if (!beanFactory.suppliesConnectionDetails(JdbcConnectionDetails::class.java)) {
            missing += DATASOURCE_SETTINGS.filterNot(::isSet).map(::toEnvironmentVariable)
        }
        if (!beanFactory.suppliesConnectionDetails(DataRedisConnectionDetails::class.java) &&
            REDIS_SETTINGS.none(::isSet)
        ) {
            missing += toEnvironmentVariable(REDIS_SETTINGS.first())
        }
        if (missing.isNotEmpty()) throw MissingConnectionSettingsException(missing)
    }

    private fun isSet(property: String): Boolean = !environment.getProperty(property).isNullOrBlank()

    /** True when something other than Spring Boot's property-backed default provides [type]. */
    private fun ConfigurableListableBeanFactory.suppliesConnectionDetails(type: Class<*>): Boolean =
        getBeanNamesForType(type, true, false).any { name ->
            getType(name, false)?.simpleName?.startsWith(PROPERTY_BACKED_PREFIX) == false
        }

    private companion object {
        const val PROPERTY_BACKED_PREFIX = "Properties"
        val DATASOURCE_SETTINGS =
            listOf("spring.datasource.url", "spring.datasource.username", "spring.datasource.password")

        /** Either is enough; the host is the one named when both are missing. */
        val REDIS_SETTINGS = listOf("spring.data.redis.host", "spring.data.redis.url")

        fun toEnvironmentVariable(property: String): String = property.uppercase().replace('.', '_')
    }
}

/** Thrown when the Server starts without database or Redis settings; [missing] are environment variable names. */
class MissingConnectionSettingsException(
    val missing: List<String>,
) : IllegalStateException(
        "Stridehearth has no database or Redis settings. Set these environment variables: " +
            "${missing.joinToString(", ")}. For local development, enable the 'dev' profile " +
            "(SPRING_PROFILES_ACTIVE=dev).",
    )

/** Turns [MissingConnectionSettingsException] into Spring Boot's short "APPLICATION FAILED TO START" report. */
class MissingConnectionSettingsFailureAnalyzer : AbstractFailureAnalyzer<MissingConnectionSettingsException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: MissingConnectionSettingsException,
    ): FailureAnalysis =
        FailureAnalysis(
            "Stridehearth has no database or Redis settings. Missing: ${cause.missing.joinToString(", ")}.",
            "Set ${cause.missing.joinToString(", ")} in the Server's environment. For local development, " +
                "enable the 'dev' profile instead (SPRING_PROFILES_ACTIVE=dev).",
            cause,
        )
}
