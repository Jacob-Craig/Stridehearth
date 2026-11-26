package com.jacobcraig.stridehearth.spa

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * Serves the React SPA bundled into `classpath:/static/` (ADR-0004).
 *
 * Real files (`/assets/index-*.js`, `/index.html`, …) are served as-is. Every other extension-less GET
 * outside the API, such as `/activities/42` or `/activities/manage?size=all`, is answered with `index.html`
 * and status 200, so TanStack Router owns client-side routing and a new client route needs no backend change.
 * API, actuator, OpenAPI and file-like paths that don't exist keep their plain 404.
 */
@Configuration
class SpaForwardingConfiguration : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/**")
            .addResourceLocations(STATIC_LOCATION)
            .resourceChain(true)
            .addResolver(SpaFallbackResourceResolver())
    }
}

private class SpaFallbackResourceResolver : PathResourceResolver() {
    override fun getResource(
        resourcePath: String,
        location: Resource,
    ): Resource? {
        val requested = location.createRelative(resourcePath)
        if (requested.exists() && requested.isReadable) return requested
        if (!isClientRoute(resourcePath)) return null
        return ClassPathResource(INDEX_HTML).takeIf { it.exists() }
    }

    private fun isClientRoute(resourcePath: String): Boolean =
        NON_CLIENT_PREFIXES.none { resourcePath == it || resourcePath.startsWith("$it/") } &&
            !resourcePath.substringAfterLast('/').contains('.')

    private companion object {
        val NON_CLIENT_PREFIXES = listOf("api", "actuator", "v3/api-docs", "swagger-ui", "assets")
    }
}

private const val STATIC_LOCATION = "classpath:/static/"
private const val INDEX_HTML = "static/index.html"
