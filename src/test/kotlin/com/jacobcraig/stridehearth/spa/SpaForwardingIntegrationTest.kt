package com.jacobcraig.stridehearth.spa

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The React SPA owns client-side routing (ADR-0004): refreshing the browser on any client route must
 * serve `index.html` from the JAR, while the `/api` endpoints stay pure JSON and the removed Thymeleaf routes are gone.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
class SpaForwardingIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/login",
            "/register",
            "/dashboard",
            "/activities",
            "/activities/42",
            "/activities/42/edit",
            "/activities/add_activity",
            "/activities/manage?size=all",
            "/activities/manage?q=tempo&sport=RUN&source=MANUAL&page=2",
            "/activities/import",
            "/activities/import/history",
        ],
    )
    fun `unauthenticated deep link GET is served the SPA shell so the client router can guard it`(path: String) {
        mockMvc
            .perform(get(path).accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(SPA_ROOT)))
    }

    @Test
    fun `root GET is forwarded to the SPA shell by the welcome page`() {
        mockMvc
            .perform(get("/").accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("index.html"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/dashboard", "/activities/42", "/activities/manage?size=all", "/activities/import/history"])
    @WithMockUser
    fun `authenticated deep link GET is served the SPA shell`(path: String) {
        mockMvc
            .perform(get(path).accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(SPA_ROOT)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/some/future/client/route", "/activities/42/some-new-tab", "/settings"])
    fun `any other extension-less non-api GET is served the SPA shell with 200 so new client routes need no backend change`(path: String) {
        mockMvc
            .perform(get(path).accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString(SPA_ROOT)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/runs", "/runs/add_run", "/runs/42", "/runs/42/edit"])
    @WithMockUser
    fun `removed legacy runs routes are no longer redirected and just get the SPA shell`(path: String) {
        mockMvc
            .perform(get(path).accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist("Location"))
            .andExpect(content().string(containsString(SPA_ROOT)))
    }

    @Test
    fun `missing file with an extension outside assets is a plain 404, not the SPA shell`() {
        mockMvc
            .perform(get("/missing-icon.svg"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `unauthenticated api request returns a 401 UNAUTHORIZED envelope rather than the SPA shell or a login redirect`() {
        mockMvc
            .perform(get("/api/activities").accept(MediaType.TEXT_HTML, MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
            .andExpect(forwardedUrl(null))
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }

    @Test
    @WithMockUser
    fun `unknown api path is never forwarded to the SPA shell`() {
        mockMvc
            .perform(get("/api/does-not-exist"))
            .andExpect(status().isNotFound)
            .andExpect(forwardedUrl(null))
    }

    @Test
    fun `static asset requests are not forwarded to the SPA shell`() {
        mockMvc
            .perform(get("/assets/missing-bundle.js"))
            .andExpect(status().isNotFound)
            .andExpect(forwardedUrl(null))
    }

    @Test
    @WithMockUser
    fun `unknown api path with an html accept header is still not served the SPA shell`() {
        mockMvc
            .perform(get("/api/does-not-exist").accept(MediaType.TEXT_HTML))
            .andExpect(status().isNotFound)
            .andExpect(content().string(not(containsString(SPA_ROOT))))
    }

    private companion object {
        const val SPA_ROOT = "<div id=\"root\">"
    }
}
