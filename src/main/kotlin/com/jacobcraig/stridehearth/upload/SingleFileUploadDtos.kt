package com.jacobcraig.stridehearth.upload

import com.jacobcraig.stridehearth.activities.ImportOutcome
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema

/**
 * The single source of the [SingleFileUploadResponseDTO.kind] discriminator values. They are
 * compile-time constants so the OpenAPI annotations and the DTOs share them.
 */
object SingleFileUploadKind {
    const val CREATED = "CREATED"
    const val UPGRADED = "UPGRADED"
    const val MULTI_PROCESSED = "MULTI_PROCESSED"
    const val DUPLICATE = "DUPLICATE"
}

/**
 * Outcome of uploading a single activity file, discriminated by [kind] so the client can match it
 * exhaustively (mirroring this sealed interface). Only canonical primitives are returned; the client
 * builds every user-facing message from [kind] and its counts.
 */
@Schema(
    oneOf = [
        CreatedUploadResponseDTO::class,
        UpgradedUploadResponseDTO::class,
        MultiProcessedUploadResponseDTO::class,
        DuplicateUploadResponseDTO::class,
    ],
    discriminatorProperty = "kind",
    discriminatorMapping = [
        DiscriminatorMapping(value = SingleFileUploadKind.CREATED, schema = CreatedUploadResponseDTO::class),
        DiscriminatorMapping(value = SingleFileUploadKind.UPGRADED, schema = UpgradedUploadResponseDTO::class),
        DiscriminatorMapping(value = SingleFileUploadKind.MULTI_PROCESSED, schema = MultiProcessedUploadResponseDTO::class),
        DiscriminatorMapping(value = SingleFileUploadKind.DUPLICATE, schema = DuplicateUploadResponseDTO::class),
    ],
)
sealed interface SingleFileUploadResponseDTO {
    val kind: String
}

/** The file held exactly one new **Activity**. */
data class CreatedUploadResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityId: Long,
) : SingleFileUploadResponseDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [SingleFileUploadKind.CREATED])
    override val kind: String = SingleFileUploadKind.CREATED
}

/** The file matched one already-imported **Activity** whose **Activity Details** or **Laps** were upgraded. */
data class UpgradedUploadResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val activityId: Long,
) : SingleFileUploadResponseDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [SingleFileUploadKind.UPGRADED])
    override val kind: String = SingleFileUploadKind.UPGRADED
}

/** The file held several activities, which were created or upgraded. */
data class MultiProcessedUploadResponseDTO(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    val processedCount: Int,
) : SingleFileUploadResponseDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [SingleFileUploadKind.MULTI_PROCESSED])
    override val kind: String = SingleFileUploadKind.MULTI_PROCESSED
}

/** Every activity in the file had already been imported and nothing changed. */
class DuplicateUploadResponseDTO : SingleFileUploadResponseDTO {
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = [SingleFileUploadKind.DUPLICATE])
    override val kind: String = SingleFileUploadKind.DUPLICATE
}

/** Maps an **Import Outcome** onto its upload response. */
fun ImportOutcome.toSingleFileUploadResponse(): SingleFileUploadResponseDTO =
    when (this) {
        is ImportOutcome.Duplicate -> DuplicateUploadResponseDTO()
        is ImportOutcome.Created ->
            created.singleOrNull()?.let { CreatedUploadResponseDTO(activityId = it.id!!) }
                ?: MultiProcessedUploadResponseDTO(processedCount = created.size)
        is ImportOutcome.Upgraded ->
            upgrades.singleOrNull()?.let { UpgradedUploadResponseDTO(activityId = it.activity.id!!) }
                ?: MultiProcessedUploadResponseDTO(processedCount = upgrades.size)
        is ImportOutcome.Mixed -> MultiProcessedUploadResponseDTO(processedCount = created.size + upgrades.size)
    }
