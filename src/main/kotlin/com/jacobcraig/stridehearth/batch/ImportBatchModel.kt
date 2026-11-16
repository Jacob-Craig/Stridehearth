package com.jacobcraig.stridehearth.batch

import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.users.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

enum class ImportBatchStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    CANCELLED,
    INTERRUPTED,
    FAILED,
    ;

    companion object {
        /** The statuses of a batch still accepting recorded items. Every other status is terminal. */
        val ACTIVE: Set<ImportBatchStatus> = setOf(PENDING, PROCESSING)
    }
}

enum class ImportBatchItemStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    UPGRADED,

    /** The file both created new **Activities** and upgraded already-imported ones. */
    MIXED,
    DUPLICATE,
    FAILED,
    ;

    companion object {
        /** **In-flight Import Batch Item** statuses: waiting for, or being processed by, a worker. */
        val IN_FLIGHT: Set<ImportBatchItemStatus> = setOf(PENDING, PROCESSING)

        /** The terminal statuses of items that could not be ingested, carrying an error message. */
        val WITH_ERRORS: Set<ImportBatchItemStatus> = setOf(FAILED)

        /** The terminal statuses of items that upgraded already-imported **Activities**, carrying an upgrade message. */
        val WITH_UPGRADES: Set<ImportBatchItemStatus> = setOf(UPGRADED, MIXED)
    }
}

/**
 * An **Import Batch**. After creation, its status, counters and messages are written only by
 * [ImportBatchLifecycle], through compare-and-set queries; the setters are protected (not private, because
 * JPA entities are open classes).
 */
@Entity
@Table(name = "import_batch")
class ImportBatch(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,
    status: ImportBatchStatus = ImportBatchStatus.PENDING,
    @Column(name = "total_files", nullable = false)
    var totalFiles: Int = 0,
    processedFiles: Int = 0,
    successfulFiles: Int = 0,
    duplicateFiles: Int = 0,
    upgradedFiles: Int = 0,
    failedFiles: Int = 0,
    errorMessage: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    completedAt: Instant? = null,
) {
    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    var status: ImportBatchStatus = status
        protected set

    @Column(name = "processed_files", nullable = false)
    var processedFiles: Int = processedFiles
        protected set

    @Column(name = "successful_files", nullable = false)
    var successfulFiles: Int = successfulFiles
        protected set

    @Column(name = "duplicate_files", nullable = false)
    var duplicateFiles: Int = duplicateFiles
        protected set

    @Column(name = "upgraded_files", nullable = false)
    var upgradedFiles: Int = upgradedFiles
        protected set

    @Column(name = "failed_files", nullable = false)
    var failedFiles: Int = failedFiles
        protected set

    @Column(name = "error_message")
    var errorMessage: String? = errorMessage
        protected set

    @Column(name = "completed_at")
    var completedAt: Instant? = completedAt
        protected set
}

/**
 * An **Import Batch Item**. After creation, its status, messages, links and timestamps are written only by
 * [ImportBatchLifecycle]; the setters are protected (not private, because JPA entities are open classes).
 */
@Entity
@Table(name = "import_batch_item")
class ImportBatchItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    var batch: ImportBatch,
    @Column(name = "original_filename", nullable = false)
    var originalFilename: String,
    @Column(name = "staged_file_path", nullable = false, length = 1024)
    var stagedFilePath: String,
    status: ImportBatchItemStatus = ImportBatchItemStatus.PENDING,
    activities: List<Activity> = emptyList(),
    errorMessage: String? = null,
    upgradeMessage: String? = null,
    upgrades: String? = null,
    processedAt: Instant? = null,
    lastReconciledAt: Instant = batch.createdAt,
) {
    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    var status: ImportBatchItemStatus = status
        protected set

    /** Every Activity the item's file resolved to: created, upgraded and duplicate. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "import_batch_item_activity",
        joinColumns = [JoinColumn(name = "batch_item_id")],
        inverseJoinColumns = [JoinColumn(name = "activity_id")],
    )
    var activities: List<Activity> = activities.toMutableList()
        protected set

    /** The failure reason of a FAILED item. */
    @Column(name = "error_message")
    var errorMessage: String? = errorMessage
        protected set

    /**
     * What an UPGRADED or MIXED item's file upgraded, as free text: legacy, written only before V12 added
     * [upgrades]. Never parsed; served as a legacy change.
     */
    @Column(name = "upgrade_message")
    var upgradeMessage: String? = upgradeMessage
        protected set

    /**
     * What an UPGRADED or MIXED item's file upgraded: its typed upgrades as jsonb, encoded by the Activity History
     * module's codec. Null on legacy items, which have [upgradeMessage] instead.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "upgrades")
    var upgrades: String? = upgrades
        protected set

    @Column(name = "processed_at")
    var processedAt: Instant? = processedAt
        protected set

    @Column(name = "last_reconciled_at", nullable = false)
    var lastReconciledAt: Instant = lastReconciledAt
        protected set
}
