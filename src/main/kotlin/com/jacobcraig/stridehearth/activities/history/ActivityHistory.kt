package com.jacobcraig.stridehearth.activities.history

import com.jacobcraig.stridehearth.activities.Activity
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * One **Activity History Entry** as read back: either typed ([change] set) or legacy ([change] null, with the
 * [legacyDescription] and [changeType] it was written with before typed changes existed).
 */
data class HistoryEntry(
    val id: Long,
    val createdAt: Instant,
    val changeType: ActivityChangeType,
    val change: ActivityChange?,
    val legacyDescription: String?,
) {
    init {
        require((change == null) != (legacyDescription == null)) { "An entry is either typed or legacy" }
    }
}

/**
 * The Activity History module: the only code that writes or reads **Activity History Entries**.
 *
 * Interface invariants:
 * - [record] joins its caller's transaction and never opens one, so an entry commits or rolls back with the
 *   change it records.
 * - [entriesFor] returns entries oldest first, legacy free-text entries included and never parsed.
 * - It also owns the typed upgrades an **Import Batch Item** stores: [encodeItemUpgrades] writes them and
 *   [itemUpgradeChanges] reads them back, legacy text included.
 */
@Component
class ActivityHistory internal constructor(
    private val repository: ActivityHistoryEntryRepository,
    private val codec: ActivityHistoryCodec,
) {
    /** Records [change] to [activity] as a new entry, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        activity: Activity,
        change: ActivityChange,
    ) {
        repository.save(ActivityHistoryEntry(activity, change, codec))
    }

    /** The entries of the Activity [activityId], oldest first (by creation time, then id). */
    @Transactional(readOnly = true)
    fun entriesFor(activityId: Long): List<HistoryEntry> =
        repository.findByActivityIdOrderByCreatedAtAscIdAsc(activityId).map { entry ->
            HistoryEntry(
                id = checkNotNull(entry.id),
                createdAt = entry.createdAt,
                changeType = entry.changeType,
                change = entry.change?.let(codec::decode),
                legacyDescription = entry.description.takeIf { entry.change == null },
            )
        }

    /** Encodes an Import Batch Item's typed [upgrades] for its `upgrades` column; null when it has none. */
    fun encodeItemUpgrades(upgrades: List<ActivityChange.Upgraded>): String? =
        upgrades.takeIf { it.isNotEmpty() }?.let(codec::encodeUpgrades)

    /** Decodes an Import Batch Item's `upgrades` column, as written by [encodeItemUpgrades]. */
    fun decodeItemUpgrades(upgrades: String): List<ActivityChange.Upgraded> = codec.decodeUpgrades(upgrades)

    /**
     * An upgraded Import Batch Item's changes: its typed [upgrades], else its [legacyMessage] as one `LEGACY`
     * upgrade, else none (the client then shows a generic upgrade).
     */
    fun itemUpgradeChanges(
        upgrades: String?,
        legacyMessage: String?,
    ): List<ActivityChangeDTO> =
        upgrades?.let { decodeItemUpgrades(it).map(ActivityChange::toDto) }
            ?: listOfNotNull(legacyMessage?.let { legacyChangeDto(ActivityChangeType.UPGRADED, it) })
}
