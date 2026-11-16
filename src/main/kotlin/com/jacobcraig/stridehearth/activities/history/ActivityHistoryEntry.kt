package com.jacobcraig.stridehearth.activities.history

import com.jacobcraig.stridehearth.activities.Activity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** The kind of an **Activity History Entry**, stored as an index column and derived from its [ActivityChange]. */
enum class ActivityChangeType {
    CREATED,
    EDITED,
    UPGRADED,
    BATCH_EDITED,
}

/**
 * One **Activity History Entry**. Constructed and read only by [ActivityHistory].
 *
 * A row holds exactly one of [change] (the [ActivityChange] as jsonb, written by [ActivityHistoryCodec]) and
 * [description]: rows written before typed changes existed (V12) keep their free-text description and are served
 * as legacy entries. They are never parsed, since their titles may contain any separator.
 */
@Entity
@Table(name = "activity_history")
class ActivityHistoryEntry private constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    var activity: Activity,
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 32)
    var changeType: ActivityChangeType,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change")
    var change: String?,
    /** The free text of a legacy entry; null on every entry written since V12. */
    @Column(name = "description")
    var description: String?,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    internal constructor(activity: Activity, change: ActivityChange, codec: ActivityHistoryCodec) : this(
        activity = activity,
        changeType = change.changeType,
        change = codec.encode(change),
        description = null,
    )
}
