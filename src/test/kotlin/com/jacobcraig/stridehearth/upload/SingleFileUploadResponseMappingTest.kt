package com.jacobcraig.stridehearth.upload

import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityDetailsChange
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.activities.ActivityUpgrade
import com.jacobcraig.stridehearth.activities.DetailsChange
import com.jacobcraig.stridehearth.activities.ImportOutcome
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.users.User
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class SingleFileUploadResponseMappingTest {
    private fun activity(id: Long) =
        Activity(
            id = id,
            title = "Run $id",
            sport = ActivitySport.RUN,
            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
            distance = 5.0,
            timeTakenSeconds = 1500,
            user = User(id = 1, email = "u@example.com", passwordHash = "x", username = "u"),
            source = ActivitySource.FIT_UPLOAD,
        )

    private fun upgrade(id: Long) =
        ActivityUpgrade(
            activity(id),
            ActivityDetailsChange.Changed(DetailsChange.Upgraded(ParserVersion(1), ParserVersion(2)), laps = null),
        )

    @Test
    fun `a file that created exactly one Activity is CREATED with its id, even if it also skipped duplicates`() {
        val response = ImportOutcome.Created(created = listOf(activity(7)), duplicates = listOf(activity(3))).toSingleFileUploadResponse()

        assertEquals(CreatedUploadResponseDTO(activityId = 7), response)
    }

    @Test
    fun `a file that upgraded exactly one Activity is UPGRADED with its id`() {
        val response = ImportOutcome.Upgraded(upgrades = listOf(upgrade(8))).toSingleFileUploadResponse()

        assertEquals(UpgradedUploadResponseDTO(activityId = 8), response)
    }

    @Test
    fun `a file that created several Activities is MULTI_PROCESSED with the count`() {
        val response = ImportOutcome.Created(created = listOf(activity(1), activity(2))).toSingleFileUploadResponse()

        assertEquals(MultiProcessedUploadResponseDTO(processedCount = 2), response)
    }

    @Test
    fun `a file that upgraded several Activities is MULTI_PROCESSED with the count`() {
        val response = ImportOutcome.Upgraded(upgrades = listOf(upgrade(1), upgrade(2))).toSingleFileUploadResponse()

        assertEquals(MultiProcessedUploadResponseDTO(processedCount = 2), response)
    }

    @Test
    fun `a file that both created and upgraded Activities is MULTI_PROCESSED counting both, not duplicates`() {
        val response =
            ImportOutcome
                .Mixed(created = listOf(activity(1)), upgrades = listOf(upgrade(2)), duplicates = listOf(activity(3)))
                .toSingleFileUploadResponse()

        assertEquals(MultiProcessedUploadResponseDTO(processedCount = 2), response)
    }

    @Test
    fun `a file whose every activity was already imported is a DUPLICATE`() {
        val response = ImportOutcome.Duplicate(duplicates = listOf(activity(1))).toSingleFileUploadResponse()

        assertEquals(SingleFileUploadKind.DUPLICATE, response.kind)
    }
}
