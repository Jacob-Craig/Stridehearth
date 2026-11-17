package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.system.InvalidActivityFileException
import com.jacobcraig.stridehearth.system.ParsedActivity
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.users.UserRepository
import org.springframework.core.io.InputStreamSource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The Activity import module: turns one activity file into an **Import Outcome**.
 *
 * Owns the ordering ADR-0001 requires: the file is parsed (including any repair) outside the database
 * transaction, then persisted inside one. It also owns the duplicate race on
 * `uq_activity_user_source_external_id`: when a concurrent import inserts the same **Activity** between
 * lookup and insert, the persist is retried once in a fresh transaction, which then sees that row.
 */
@Service
class ActivityImportService(
    private val fitActivityParser: FitActivityParser,
    private val activityRepository: ActivityRepository,
    private val userRepository: UserRepository,
    private val activityDetailsService: ActivityDetailsService,
    private val activityHistory: ActivityHistory,
    private val bestEffortRefresh: BestEffortRefresh,
    private val derivedDataRefresh: DerivedDataRefresh,
    private val transactionTemplate: TransactionTemplate,
) {
    /**
     * Parses the activity [file] and persists its **Activities** for [userId].
     *
     * [recordOutcome] runs inside the persist transaction, so a caller's own bookkeeping commits or rolls
     * back together with the Activities.
     *
     * Must not be called inside a transaction: the duplicate-race retry needs a fresh one.
     *
     * @throws InvalidActivityFileException when the file cannot be read as an activity or yields none.
     */
    fun import(
        file: InputStreamSource,
        userId: Long,
        source: ActivitySource = ActivitySource.FIT_UPLOAD,
        recordOutcome: (ImportOutcome) -> Unit = {},
    ): ImportOutcome {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Activity import owns its transaction and must not run inside a caller's transaction"
        }
        val parsed = fitActivityParser.parse(file)
        if (parsed.isEmpty()) throw InvalidActivityFileException("The file contains no activities")
        return try {
            persistInTransaction(parsed, userId, source, recordOutcome)
        } catch (e: DataIntegrityViolationException) {
            if (!e.isExternalIdRace()) throw e
            persistInTransaction(parsed, userId, source, recordOutcome)
        }
    }

    private fun persistInTransaction(
        parsedActivities: List<ParsedActivity>,
        userId: Long,
        source: ActivitySource,
        recordOutcome: (ImportOutcome) -> Unit,
    ): ImportOutcome =
        transactionTemplate.execute {
            persist(parsedActivities, userId, source).also(recordOutcome)
        }!!

    private fun DataIntegrityViolationException.isExternalIdRace(): Boolean =
        mostSpecificCause.message?.contains(EXTERNAL_ID_CONSTRAINT) == true

    private fun persist(
        parsedActivities: List<ParsedActivity>,
        userId: Long,
        source: ActivitySource,
    ): ImportOutcome {
        val owner = userRepository.getReferenceById(userId)
        val savedActivities = mutableListOf<Activity>()
        val duplicateActivities = mutableListOf<Activity>()
        val upgradedActivities = mutableListOf<ActivityUpgrade>()

        // Each offer below locks its Activity's row. Lock every already-imported one now, in one statement and in id
        // order as a derived-data recompute page does (ADR-0014): offered in session order, a file whose sessions'
        // ids are out of order could deadlock with a page. The lookups below find the same rows already locked.
        val alreadyImported =
            parsedActivities.mapNotNull { parsed ->
                parsed.externalId?.let { findAfterPersonalRecordLock(userId, source, it)?.id }
            }
        if (alreadyImported.isNotEmpty()) activityDetailsService.lockActivities(alreadyImported)

        for (parsed in parsedActivities) {
            val existing = parsed.externalId?.let { findAfterPersonalRecordLock(userId, source, it) }
            val activity = existing ?: activityRepository.save(parsed.toActivity(owner, source))
            val change = activityDetailsService.offer(activity, parsed.telemetry, parsed.laps, parsed.parserVersion)
            // Best Efforts are refreshed only from Activity Details this offer wrote: a new Activity without any can
            // hold none, and a laps-only Backfill or a duplicate leaves them as they are. Every new Activity gets its
            // derived-data row, details or not (ADR-0014).
            val writtenDetails = parsed.telemetry.takeIf { change.detailsWritten }

            if (existing == null) {
                writtenDetails?.let { bestEffortRefresh.refresh(activity, it) }
                derivedDataRefresh.refresh(activity, writtenDetails)
                activityHistory.record(activity, ActivityChange.Created.of(source, change))
                savedActivities.add(activity)
            } else {
                when (change) {
                    ActivityDetailsChange.Unchanged -> duplicateActivities.add(existing)
                    is ActivityDetailsChange.Changed -> {
                        writtenDetails?.let {
                            bestEffortRefresh.refresh(existing, it)
                            derivedDataRefresh.refresh(existing, it)
                        }
                        activityHistory.record(existing, ActivityChange.Upgraded.of(change))
                        upgradedActivities.add(ActivityUpgrade(existing, change))
                    }
                }
            }
        }

        return checkNotNull(ImportOutcome.of(savedActivities, upgradedActivities, duplicateActivities)) {
            "A non-empty parse always creates, upgrades or matches an Activity"
        }
    }

    /**
     * The athlete's Activity with [externalId], once this transaction holds the Personal Record lock its Upgrade
     * would take, or null if there is none. Offering Activity Details locks the Activity's row, so the lock is
     * taken first (ADR-0006). The Activity is looked up again after the lock: if a delete committed while this
     * import waited, the file is imported as a new Activity, just as if the delete had run first.
     *
     * A new Activity needs no early lock: no other transaction can see its row until this one commits.
     */
    private fun findAfterPersonalRecordLock(
        userId: Long,
        source: ActivitySource,
        externalId: String,
    ): Activity? {
        val found = activityRepository.findByUserIdAndSourceAndExternalId(userId, source, externalId) ?: return null
        bestEffortRefresh.lockBeforeChange(found)
        return activityRepository.findByUserIdAndSourceAndExternalId(userId, source, externalId)
    }

    private companion object {
        const val EXTERNAL_ID_CONSTRAINT = "uq_activity_user_source_external_id"
    }
}
