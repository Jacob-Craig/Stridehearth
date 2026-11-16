package com.jacobcraig.stridehearth.activities.history

import com.jacobcraig.stridehearth.TestcontainersConfiguration
import com.jacobcraig.stridehearth.activities.Activity
import com.jacobcraig.stridehearth.activities.ActivityRepository
import com.jacobcraig.stridehearth.activities.ActivitySource
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Activity History module through its interface, against real Postgres jsonb. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ActivityHistoryTest {
    @Autowired
    private lateinit var activityHistory: ActivityHistory

    @Autowired
    private lateinit var activityRepository: ActivityRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var activity: Activity

    @BeforeEach
    fun setUp() {
        val runner = userRepository.save(User(email = "history@example.com", passwordHash = "x", username = "history_runner"))
        activity =
            activityRepository.save(
                Activity(
                    title = "History Run",
                    startedAt = Instant.parse("2026-01-01T08:00:00Z"),
                    distance = 5.0,
                    timeTakenSeconds = 1500,
                    user = runner,
                    source = ActivitySource.FIT_UPLOAD,
                ),
            )
    }

    @AfterEach
    fun tearDown() {
        activityRepository.deleteAll()
        userRepository.deleteAll()
    }

    private fun record(change: ActivityChange) = transactionTemplate.executeWithoutResult { activityHistory.record(activity, change) }

    private val everyVariant =
        listOf(
            ActivityChange.Created.MANUAL,
            ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = 3, lapCount = 2),
            ActivityChange.Edited(
                listOf(
                    FieldChange.Title("Run, with -> arrows", "B"),
                    FieldChange.Sport(ActivitySport.RUN, ActivitySport.RIDE),
                    FieldChange.StartedAt(Instant.parse("2026-01-01T08:00:00Z"), Instant.parse("2026-01-01T09:30:15.250Z")),
                    FieldChange.Distance(5.0, 10.25),
                    FieldChange.TimeTaken(1500, 3000),
                    FieldChange.AveragePace(12.0, null),
                    FieldChange.AverageHeartRate(null, 150),
                    FieldChange.Calories(400, 410),
                ),
            ),
            ActivityChange.BatchEdited(listOf(FieldChange.Sport(ActivitySport.RIDE, ActivitySport.WALK))),
            ActivityChange.Upgraded(DetailsUpgrade.Backfilled(1), LapsUpgrade.Replaced(3)),
            ActivityChange.Upgraded(DetailsUpgrade.ParserVersionUpgraded(1, 2), LapsUpgrade.Replaced(1)),
            ActivityChange.Upgraded(DetailsUpgrade.DistanceBackfilled(2), null),
            ActivityChange.Upgraded(null, LapsUpgrade.Backfilled(4)),
        )

    @Test
    fun `every change round-trips through jsonb to an equal value, oldest first`() {
        everyVariant.forEach(::record)

        val entries = activityHistory.entriesFor(activity.id!!)

        assertEquals(everyVariant, entries.map { it.change })
        assertEquals(everyVariant.map { it.changeType }, entries.map { it.changeType })
        entries.forEach { assertNull(it.legacyDescription) }
    }

    @Test
    fun `the stored jsonb names each kind and keeps values in the Activity's own units`() {
        record(
            ActivityChange.Edited(
                listOf(FieldChange.StartedAt(Instant.parse("2026-01-01T08:00:00Z"), null), FieldChange.Distance(5.0, 10.0)),
            ),
        )
        record(ActivityChange.Upgraded(DetailsUpgrade.ParserVersionUpgraded(1, 2), LapsUpgrade.Replaced(3)))

        val stored =
            jdbcTemplate.queryForList(
                "SELECT change::text FROM activity_history WHERE activity_id = ? ORDER BY id",
                String::class.java,
                activity.id,
            )

        assertEquals(
            listOf(
                """{"kind": "EDITED", "fields": [{"to": null, "from": "2026-01-01T08:00:00Z", "kind": "STARTED_AT"}, """ +
                    """{"to": 10.0, "from": 5.0, "kind": "DISTANCE"}]}""",
                """{"kind": "UPGRADED", "laps": {"kind": "REPLACED", "count": 3}, "details": {"to": 2, "from": 1, "kind": "PARSER_VERSION_UPGRADED"}}""",
            ),
            stored,
        )
    }

    @Test
    fun `a free-text row written before typed changes is read back as a legacy entry`() {
        jdbcTemplate.update(
            "INSERT INTO activity_history (activity_id, change_type, description, created_at) VALUES (?, 'EDITED', ?, ?)",
            activity.id,
            "title: A, B -> C",
            java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
        )
        record(ActivityChange.Created.MANUAL)

        val entries = activityHistory.entriesFor(activity.id!!)

        assertEquals(2, entries.size)
        val legacy = entries.first()
        assertEquals(ActivityChangeType.EDITED to "title: A, B -> C", legacy.changeType to legacy.legacyDescription)
        assertNull(legacy.change)
        assertEquals(ActivityChange.Created.MANUAL, entries.last().change)
    }

    @Test
    fun `record requires the caller's transaction`() {
        assertThrows<IllegalTransactionStateException> { activityHistory.record(activity, ActivityChange.Created.MANUAL) }
    }

    @Test
    fun `a recorded entry rolls back with its caller's transaction`() {
        assertThrows<IllegalStateException> {
            transactionTemplate.executeWithoutResult {
                activityHistory.record(activity, ActivityChange.Created.MANUAL)
                error("the caller fails after recording")
            }
        }

        assertEquals(emptyList(), activityHistory.entriesFor(activity.id!!))
    }

    @Test
    fun `the database rejects a row that is both typed and legacy, or neither`() {
        val typedAndLegacy = """'{"kind":"CREATED","source":"MANUAL","parserVersion":null,"lapCount":0}'::jsonb, 'text'"""
        listOf(typedAndLegacy, "NULL, NULL").forEach { values ->
            assertThrows<Exception> {
                jdbcTemplate.update(
                    "INSERT INTO activity_history (activity_id, change_type, change, description) VALUES (?, 'CREATED', $values)",
                    activity.id,
                )
            }
        }
    }
}
