package com.jacobcraig.stridehearth.upload

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityImportService
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import jakarta.servlet.ServletException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.TestExecutionEvent
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import kotlin.test.assertIs

/** How single upload behaves around the database: the transaction boundary and integrity violations. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@WithUserDetails(value = "race_runner", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class SingleFileUploadPersistenceIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoSpyBean
    private lateinit var activityRepository: ActivityRepository

    @MockitoSpyBean
    private lateinit var fitActivityParser: FitActivityParser

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var activityImportService: ActivityImportService

    private lateinit var runner: User

    private val sampleFitBytes = ClassPathResource("runs/sample.fit").inputStream.readBytes()

    @BeforeEach
    fun setUp() {
        runner = userRepository.save(User(email = "race_runner@example.com", passwordHash = "x", username = "race_runner"))
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun uploadSample(): ResultActions =
        mockMvc.perform(
            multipart("/api/activities/upload")
                .file(MockMultipartFile("file", "sample.fit", "application/octet-stream", sampleFitBytes))
                .with(csrf()),
        )

    /** On the upload's first lookup, a concurrent import of the same file commits first, then the lookup misses it. */
    private fun concurrentImportWinsTheFirstLookup() {
        var raced = false
        doAnswer { invocation ->
            val externalId = invocation.getArgument<String>(2)
            // A spied Spring Data proxy cannot call through, so other lookups answer from the stored rows.
            if (raced) return@doAnswer activityRepository.findByUserId(runner.id!!).firstOrNull { it.externalId == externalId }
            raced = true
            // Committed on another thread, as a concurrent request's import would be.
            val concurrent = Thread { activityImportService.import(ByteArrayResource(sampleFitBytes), runner.id!!) }
            concurrent.start()
            concurrent.join()
            null
        }.whenever(activityRepository).findByUserIdAndSourceAndExternalId(eq(runner.id!!), eq(ActivitySource.FIT_UPLOAD), any())
    }

    @Test
    fun `an upload that loses the duplicate race to a concurrent import returns DUPLICATE and keeps one Activity`() {
        concurrentImportWinsTheFirstLookup()

        uploadSample()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.kind").value("DUPLICATE"))

        assertEquals(1, activityRepository.findByUserId(runner.id!!).size)
    }

    @Test
    fun `an integrity violation other than the duplicate race fails the upload as a server error and saves nothing`() {
        doThrow(DataIntegrityViolationException("violates check constraint \"activity_distance_check\""))
            .whenever(activityRepository)
            .save(any<Activity>())

        // MockMvc surfaces an unhandled exception directly; the servlet container turns it into a 500.
        val failure = assertThrows<ServletException> { uploadSample() }

        assertIs<DataIntegrityViolationException>(failure.cause)
        assertEquals(0, activityRepository.findByUserId(runner.id!!).size)
    }

    @Test
    fun `an upload is parsed with no database transaction open`() {
        var transactionActiveDuringParse: Boolean? = null
        doAnswer { invocation ->
            transactionActiveDuringParse = TransactionSynchronizationManager.isActualTransactionActive()
            invocation.callRealMethod()
        }.whenever(fitActivityParser).parse(any())

        uploadSample().andExpect(status().isOk)

        assertFalse(transactionActiveDuringParse!!)
    }
}
