package com.jacobcraig.stridehearth

import com.jacobcraig.stridehearth.testutils.TestProcessUtils.findProjectRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * Contract freshness guardrail (ADR-0004).
 *
 * `frontend/openapi.json` is the committed OpenAPI snapshot that `npm run codegen` consumes to
 * produce `frontend/src/api/generated`. This test fails whenever the backend's live
 * `/v3/api-docs` drifts from that snapshot; `npm run codegen:check` (Gradle `frontendCodegenCheck`)
 * then fails whenever the generated client drifts from the snapshot. Together they guarantee the
 * TypeScript client matches the Kotlin controllers.
 *
 * To accept an intentional API change: `./gradlew test -PupdateOpenApiSnapshot
 * --tests '*OpenApiContractTest'`, then `cd frontend && npm run codegen`, and commit both.
 *
 * `/v3/api-docs` is served only with the `dev` profile (ADR-0009), so this test switches it on directly.
 */
@SpringBootTest(properties = ["springdoc.api-docs.enabled=true"])
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
class OpenApiContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val jsonMapper = JsonMapper.builder().build()

    private fun liveSpec(): String =
        mockMvc
            .perform(
                get("/v3/api-docs")
                    .accept(MediaType.APPLICATION_JSON),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

    @Test
    fun `committed openapi snapshot consumed by frontend codegen matches the live backend spec`() {
        val live = jsonMapper.readTree(liveSpec())
        val snapshotFile = File(findProjectRoot(), "frontend/openapi.json")

        if (System.getProperty("openapi.snapshot.update") == "true") {
            snapshotFile.writeText(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(live) + "\n")
        }

        assertTrue(
            snapshotFile.isFile,
            "frontend/openapi.json is missing. Regenerate it with -PupdateOpenApiSnapshot and commit it.",
        )
        // JsonNode equality ignores object key order, so springdoc ordering noise cannot flake this.
        assertEquals(
            jsonMapper.readTree(snapshotFile),
            live,
            "frontend/openapi.json is stale versus the live /v3/api-docs. Run " +
                "`./gradlew test -PupdateOpenApiSnapshot --tests '*OpenApiContractTest'`, then " +
                "`cd frontend && npm run codegen`, and commit both the snapshot and src/api/generated.",
        )
    }
}
