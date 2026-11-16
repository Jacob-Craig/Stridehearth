package com.jacobcraig.stridehearth.upload

import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.Mesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import com.garmin.fit.File as FitFile

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "upload_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class SingleFileUploadApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        userRepository.save(User(email = "upload_runner@example.com", passwordHash = "x", username = "upload_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun sampleFit() =
        MockMultipartFile("file", "sample.fit", "application/octet-stream", ClassPathResource("runs/sample.fit").inputStream.readBytes())

    private fun upload(file: MockMultipartFile): ResultActions =
        mockMvc.perform(multipart("/api/activities/upload").file(file).with(csrf()))

    private fun activityIdFrom(result: ResultActions): Long =
        objectMapper
            .readTree(result.andReturn().response.contentAsString)
            .get("activityId")
            .asLong()

    @Test
    @WithAnonymousUser
    fun `unauthenticated upload is rejected with 401`() {
        upload(sampleFit())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.kind").value("UNAUTHORIZED"))
    }

    @Test
    fun `uploading a new single-activity FIT file returns CREATED with the new activity id`() {
        val result =
            upload(sampleFit())
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.kind").value("CREATED"))
                .andExpect(jsonPath("$.activityId").isNumber)
                .andExpect(jsonPath("$.message").doesNotExist())

        val owner = userRepository.findByUsername("upload_runner")!!
        assertNotNull(activityRepository.findByIdAndUserId(activityIdFrom(result), owner.id!!))
    }

    @Test
    fun `re-uploading an already imported FIT file returns DUPLICATE and saves nothing new`() {
        upload(sampleFit()).andExpect(jsonPath("$.kind").value("CREATED"))

        upload(sampleFit())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("DUPLICATE"))
            .andExpect(jsonPath("$.activityId").doesNotExist())
            .andExpect(jsonPath("$.message").doesNotExist())
        assertEquals(1, activityRepository.count())
    }

    @Test
    fun `re-uploading a FIT file whose Activity Details predate the current Parser Version returns UPGRADED`() {
        val activityId = activityIdFrom(upload(sampleFit()))
        jdbcTemplate.update("UPDATE activity_details SET parser_version = 0 WHERE activity_id = ?", activityId)

        upload(sampleFit())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("UPGRADED"))
            .andExpect(jsonPath("$.activityId").value(activityId))
            .andExpect(jsonPath("$.message").doesNotExist())
        assertEquals(1, activityRepository.count())
    }

    @Test
    fun `uploading a FIT file containing several activities returns MULTI_PROCESSED with the count`() {
        val start = Instant.parse("2026-06-01T06:00:00Z")
        val bytes =
            encodeFitFile(
                FileIdMesg().apply {
                    type = FitFile.ACTIVITY
                    serialNumber = 7777L
                    manufacturer = 1
                    product = 42
                    timeCreated = DateTime(start)
                },
                session(start, 3000f, 900f),
                session(start.plusSeconds(7200), 2000f, 600f),
            )

        upload(MockMultipartFile("file", "multisport.fit", "application/octet-stream", bytes))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("MULTI_PROCESSED"))
            .andExpect(jsonPath("$.processedCount").value(2))
            .andExpect(jsonPath("$.activityId").doesNotExist())
        assertEquals(2, activityRepository.count())
    }

    @Test
    fun `uploading a corrupt file returns 400 INVALID_ACTIVITY_FILE and saves nothing`() {
        val corrupt =
            MockMultipartFile(
                "file",
                "corrupt_sample.fit",
                "application/octet-stream",
                ClassPathResource("runs/corrupt_sample.fit").inputStream.readBytes(),
            )

        upload(corrupt)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("INVALID_ACTIVITY_FILE"))
            .andExpect(jsonPath("$.message").isString)
        assertEquals(0, activityRepository.count())
    }

    @Test
    fun `an upload without a file part returns a 400 BAD_REQUEST envelope`() {
        mockMvc
            .perform(multipart("/api/activities/upload").with(csrf()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.kind").value("BAD_REQUEST"))
    }

    private fun session(
        start: Instant,
        distanceMeters: Float,
        seconds: Float,
    ) = SessionMesg().apply {
        startTime = DateTime(start)
        totalDistance = distanceMeters
        totalTimerTime = seconds
        totalElapsedTime = seconds
        sport = Sport.RUNNING
    }

    private fun encodeFitFile(vararg mesgs: Mesg): ByteArray =
        BufferEncoder(Fit.ProtocolVersion.V2_0).run {
            mesgs.forEach { write(it) }
            close()
        }
}
