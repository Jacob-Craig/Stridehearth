package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.activities.history.ActivityChange
import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.activities.history.DetailsUpgrade
import com.jacobcraig.stridehearth.activities.history.LapsUpgrade
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.system.InvalidActivityFileException
import com.jacobcraig.stridehearth.system.ParsedActivity
import com.jacobcraig.stridehearth.system.ParsedActivityLap
import com.jacobcraig.stridehearth.system.ParserVersion
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import com.jacobcraig.stridehearth.system.fit.FitTelemetry
import com.jacobcraig.stridehearth.testutils.parsedActivity
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.InputStreamSource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActivityImportServiceTest {
    private val fitActivityParser: FitActivityParser = mock()
    private val activityRepository: ActivityRepository = mock()
    private val userRepository: UserRepository = mock()
    private val activityDetailsService: ActivityDetailsService = mock()
    private val activityHistory: ActivityHistory = mock()
    private val bestEffortRefresh: BestEffortRefresh = mock()
    private val derivedDataRefresh: DerivedDataRefresh = mock()
    private val transactionTemplate: TransactionTemplate = mock()

    private val service =
        ActivityImportService(
            fitActivityParser = fitActivityParser,
            activityRepository = activityRepository,
            userRepository = userRepository,
            activityDetailsService = activityDetailsService,
            activityHistory = activityHistory,
            bestEffortRefresh = bestEffortRefresh,
            derivedDataRefresh = derivedDataRefresh,
            transactionTemplate = transactionTemplate,
        )

    @BeforeEach
    fun runTransactionCallbacksInline() {
        doAnswer { invocation ->
            invocation.getArgument<TransactionCallback<Any>>(0).doInTransaction(mock<TransactionStatus>())
        }.whenever(transactionTemplate).execute(any<TransactionCallback<Any>>())
        whenever(activityDetailsService.offer(any(), anyOrNull(), any(), any())).thenReturn(ActivityDetailsChange.Unchanged)
    }

    /** Imports a file whose parse yields [parsed], as both callers now do through the single entry point. */
    private fun importing(
        vararg parsed: ParsedActivity,
        recordOutcome: (ImportOutcome) -> Unit = {},
    ): ImportOutcome {
        whenever(fitActivityParser.parse(any<InputStreamSource>())).thenReturn(parsed.toList())
        return service.import(ByteArrayResource(byteArrayOf(1)), 42L, ActivitySource.FIT_UPLOAD, recordOutcome)
    }

    private fun activity(
        id: Long,
        externalId: String,
    ) = Activity(
        id = id,
        user = owner,
        title = "Run $id",
        sport = ActivitySport.RUN,
        startedAt = Instant.parse("2026-03-01T08:00:00Z"),
        distance = 5.0,
        timeTakenSeconds = 1800,
        source = ActivitySource.FIT_UPLOAD,
        externalId = externalId,
    )

    private fun parsedRun(
        externalId: String,
        telemetry: FitTelemetry? = null,
    ) = parsedActivity(
        sport = ActivitySport.RUN,
        title = "Run",
        startedAt = Instant.parse("2026-03-01T08:00:00Z"),
        distance = 5.0,
        timeTakenSeconds = 1800,
        externalId = externalId,
        telemetry = telemetry,
    )

    private val currentTelemetry =
        FitTelemetry(t = intArrayOf(0), lons = doubleArrayOf(), lats = doubleArrayOf(), routeIndex = emptyArray())

    private val lap =
        ParsedActivityLap(lapNumber = 1, startedAt = Instant.parse("2026-03-01T08:00:00Z"), distance = 5.0, timeTakenSeconds = 1800)

    private val upgradedDetails =
        ActivityDetailsChange.Changed(DetailsChange.Upgraded(from = ParserVersion(1), to = ParserVersion(2)), laps = null)

    private val lapsOnly = ActivityDetailsChange.Changed(details = null, laps = LapsChange.Backfilled(1))

    /** An already-imported Activity whose offer the Activity Details module answers with [change]. */
    private fun alreadyImported(
        id: Long,
        externalId: String,
        change: ActivityDetailsChange = ActivityDetailsChange.Unchanged,
    ): Activity {
        val existing = activity(id, externalId)
        whenever(activityRepository.findByUserIdAndSourceAndExternalId(42L, ActivitySource.FIT_UPLOAD, externalId))
            .thenReturn(existing)
        whenever(activityDetailsService.offer(eq(existing), anyOrNull(), any(), any())).thenReturn(change)
        return existing
    }

    /** The Activity History Entries the import recorded, as (Activity id, change). */
    private fun recordedHistory(): List<Pair<Long, ActivityChange>> {
        val activities = argumentCaptor<Activity>()
        val changes = argumentCaptor<ActivityChange>()
        verify(activityHistory, atLeast(0)).record(activities.capture(), changes.capture())
        return activities.allValues.map { it.id!! }.zip(changes.allValues)
    }

    @Test
    fun `a file whose activities are all new is Created, parsed before the persist transaction opens`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val saved = activity(101L, "ext-new")
        whenever(activityRepository.save(any<Activity>())).thenReturn(saved)

        val outcome = importing(parsedRun("ext-new"))

        assertEquals(ImportOutcome.Created(created = listOf(saved)), outcome)
        val order = inOrder(fitActivityParser, transactionTemplate)
        order.verify(fitActivityParser).parse(any<InputStreamSource>())
        order.verify(transactionTemplate).execute(any<TransactionCallback<Any>>())
    }

    @Test
    fun `a file that only upgrades an already-imported Activity is Upgraded with the change the module returned`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val existing = alreadyImported(200L, "ext-old", upgradedDetails)

        val outcome = importing(parsedRun("ext-old", currentTelemetry))

        val upgraded = assertIs<ImportOutcome.Upgraded>(outcome)
        assertEquals(listOf(ActivityUpgrade(existing, upgradedDetails)), upgraded.upgrades)
        assertEquals(
            listOf(200L to ActivityChange.Upgraded(DetailsUpgrade.ParserVersionUpgraded(from = 1, to = 2), laps = null)),
            recordedHistory(),
        )
        verify(activityRepository, never()).save(any<Activity>())
    }

    @Test
    fun `a file that both creates and upgrades Activities is Mixed, keeping its unchanged duplicates`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val saved = activity(301L, "ext-new")
        whenever(activityRepository.save(any<Activity>())).thenReturn(saved)
        val existing = alreadyImported(302L, "ext-old", upgradedDetails)
        val unchanged = alreadyImported(303L, "ext-same")

        val outcome = importing(parsedRun("ext-new"), parsedRun("ext-old", currentTelemetry), parsedRun("ext-same"))

        val mixed = assertIs<ImportOutcome.Mixed>(outcome)
        assertEquals(listOf(saved), mixed.created)
        assertEquals(listOf(existing), mixed.upgrades.map { it.activity })
        assertEquals(listOf(unchanged), mixed.duplicates)
    }

    @Test
    fun `every parsed Activity is offered with its details, Laps and the Parser Version stamped on it`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val saved = activity(101L, "ext-new")
        whenever(activityRepository.save(any<Activity>())).thenReturn(saved)
        val existing = alreadyImported(102L, "ext-old")
        val stampedNew = parsedRun("ext-new", currentTelemetry).copy(laps = listOf(lap), parserVersion = ParserVersion(7))
        val stampedOld = parsedRun("ext-old").copy(parserVersion = ParserVersion(9))

        importing(stampedNew, stampedOld)

        verify(activityDetailsService).offer(saved, currentTelemetry, listOf(lap), ParserVersion(7))
        verify(activityDetailsService).offer(existing, null, emptyList(), ParserVersion(9))
    }

    @Test
    fun `an Unchanged offer for an already-imported Activity is a duplicate with no history and no refresh`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val existing = alreadyImported(99L, "dup-1")

        val outcome = importing(parsedRun("dup-1", qualifyingDetails).copy(laps = listOf(lap)))

        assertEquals(ImportOutcome.Duplicate(duplicates = listOf(existing)), outcome)
        verify(activityRepository, never()).save(any())
        verify(activityHistory, never()).record(any(), any())
        verify(bestEffortRefresh, never()).refresh(any<Activity>(), anyOrNull())
        verify(derivedDataRefresh, never()).refresh(any<Activity>(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a new Activity's history records the Parser Version and Lap count from the change`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val withBoth = activity(101L, "with-both")
        val withLapsOnly = activity(102L, "laps-only")
        whenever(activityRepository.save(any<Activity>())).thenReturn(withBoth, withLapsOnly)
        whenever(activityDetailsService.offer(eq(withBoth), anyOrNull(), any(), any())).thenReturn(
            ActivityDetailsChange.Changed(DetailsChange.Backfilled(ParserVersion(3)), LapsChange.Replaced(2)),
        )
        whenever(activityDetailsService.offer(eq(withLapsOnly), anyOrNull(), any(), any())).thenReturn(lapsOnly)

        importing(parsedRun("with-both"), parsedRun("laps-only"))

        assertEquals(
            listOf(
                101L to ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = 3, lapCount = 2),
                102L to ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = null, lapCount = 1),
            ),
            recordedHistory(),
        )
    }

    @Test
    fun `a new Activity with nothing to store is still Created with a bare history entry`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val saved = activity(102L, "walk-1")
        whenever(activityRepository.save(any<Activity>())).thenReturn(saved)

        val outcome = importing(parsedRun("walk-1"))

        assertEquals(ImportOutcome.Created(created = listOf(saved)), outcome)
        assertEquals(
            listOf(102L to ActivityChange.Created(ActivitySource.FIT_UPLOAD, parserVersion = null, lapCount = 0)),
            recordedHistory(),
        )
    }

    @Test
    fun `a file that yields no Activities is an invalid activity file and opens no transaction`() {
        assertThrows<InvalidActivityFileException> { importing() }

        verify(transactionTemplate, never()).execute(any<TransactionCallback<Any>>())
    }

    @Test
    fun `the caller's recorder receives the outcome inside the persist transaction`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        whenever(activityRepository.save(any<Activity>())).thenReturn(activity(101L, "ext-new"))
        var recordedInsideTransaction: ImportOutcome? = null
        var insideTransaction = false
        doAnswer { invocation ->
            insideTransaction = true
            try {
                invocation.getArgument<TransactionCallback<Any>>(0).doInTransaction(mock<TransactionStatus>())
            } finally {
                insideTransaction = false
            }
        }.whenever(transactionTemplate).execute(any<TransactionCallback<Any>>())

        val outcome = importing(parsedRun("ext-new")) { if (insideTransaction) recordedInsideTransaction = it }

        assertEquals(outcome, recordedInsideTransaction)
    }

    @Test
    fun `losing the external-id race to a concurrent import retries once and classifies the file as Duplicate`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val concurrentlyImported = activity(400L, "ext-race")
        whenever(activityRepository.findByUserIdAndSourceAndExternalId(42L, ActivitySource.FIT_UPLOAD, "ext-race"))
            .thenReturn(null, concurrentlyImported)
        whenever(activityRepository.save(any<Activity>())).thenThrow(
            DataIntegrityViolationException(
                "could not execute statement",
                RuntimeException(
                    "ERROR: duplicate key value violates unique constraint \"uq_activity_user_source_external_id\"",
                ),
            ),
        )
        val recorded = mutableListOf<ImportOutcome>()

        val outcome = importing(parsedRun("ext-race")) { recorded += it }

        assertEquals(ImportOutcome.Duplicate(duplicates = listOf(concurrentlyImported)), outcome)
        assertEquals(listOf(outcome), recorded, "only the committed attempt is recorded")
        verify(transactionTemplate, times(2)).execute(any<TransactionCallback<Any>>())
    }

    @Test
    fun `only the most specific cause decides whether a violation is the external-id race`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val violation =
            DataIntegrityViolationException(
                "insert failed near uq_activity_user_source_external_id",
                RuntimeException("ERROR: null value in column \"distance\" violates not-null constraint"),
            )
        whenever(activityRepository.save(any<Activity>())).thenThrow(violation)

        assertThrows<DataIntegrityViolationException> { importing(parsedRun("ext-new")) }

        verify(transactionTemplate, times(1)).execute(any<TransactionCallback<Any>>())
    }

    @Test
    fun `importing inside a caller's transaction is refused, so the race retry always gets a fresh transaction`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThrows<IllegalStateException> { importing(parsedRun("ext-new")) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        verify(transactionTemplate, never()).execute(any<TransactionCallback<Any>>())
    }

    @Test
    fun `an integrity violation other than the external-id race propagates without a retry`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val violation = DataIntegrityViolationException("violates check constraint \"activity_distance_check\"")
        whenever(activityRepository.save(any<Activity>())).thenThrow(violation)

        val thrown = assertThrows<DataIntegrityViolationException> { importing(parsedRun("ext-new")) }

        assertEquals(violation, thrown)
        verify(transactionTemplate, times(1)).execute(any<TransactionCallback<Any>>())
    }

    private val owner = User(id = 42L, email = "runner@example.com", passwordHash = "x", username = "runner")

    @Test
    fun `import handles activities with null externalId as fresh`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val savedActivity = activity(103L, "ignored")
        whenever(activityRepository.save(any<Activity>())).thenReturn(savedActivity)

        val outcome = importing(parsedRun("ignored").copy(externalId = null))

        assertEquals(ImportOutcome.Created(created = listOf(savedActivity)), outcome)
        verify(activityRepository, never()).findByUserIdAndSourceAndExternalId(any(), any(), any())
    }

    private val qualifyingDetails =
        FitTelemetry(
            t = IntArray(101) { it },
            lons = doubleArrayOf(),
            lats = doubleArrayOf(),
            routeIndex = arrayOfNulls(101),
            distance = Array<Float?>(101) { it * 10.0f },
        )

    @Test
    fun `a new Activity is refreshed with the Activity Details the offer stored`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val saved = activity(501L, "fresh-be-1")
        whenever(activityRepository.save(any<Activity>())).thenReturn(saved)
        whenever(activityDetailsService.offer(eq(saved), anyOrNull(), any(), any()))
            .thenReturn(ActivityDetailsChange.Changed(DetailsChange.Backfilled(ParserVersion(1)), laps = null))

        importing(parsedRun("fresh-be-1", qualifyingDetails))

        val order = inOrder(bestEffortRefresh, derivedDataRefresh, activityHistory)
        order.verify(bestEffortRefresh).refresh(saved, qualifyingDetails)
        order.verify(derivedDataRefresh).refresh(saved, qualifyingDetails)
        order.verify(activityHistory).record(any(), any())
    }

    @Test
    fun `a new Activity without Activity Details gets no Best Efforts but still gets its derived-data row`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val saved = activity(502L, "fresh-no-details")
        whenever(activityRepository.save(any<Activity>())).thenReturn(saved)

        importing(parsedRun("fresh-no-details"))

        verify(bestEffortRefresh, never()).refresh(any<Activity>(), anyOrNull())
        val order = inOrder(derivedDataRefresh, activityHistory)
        order.verify(derivedDataRefresh).refresh(saved, null)
        order.verify(activityHistory).record(any(), any())
    }

    @Test
    fun `an Activity whose Activity Details changed is refreshed with the new details`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val existing = alreadyImported(601L, "upgrade-be-1", upgradedDetails)

        importing(parsedRun("upgrade-be-1", qualifyingDetails))

        val order = inOrder(bestEffortRefresh, derivedDataRefresh, activityHistory)
        order.verify(bestEffortRefresh).refresh(existing, qualifyingDetails)
        order.verify(derivedDataRefresh).refresh(existing, qualifyingDetails)
        order.verify(activityHistory).record(any(), any())
    }

    @Test
    fun `a laps-only backfill is an upgrade that is not refreshed`() {
        whenever(userRepository.getReferenceById(42L)).thenReturn(owner)
        val lapless = alreadyImported(701L, "laps-only", lapsOnly)

        val outcome = importing(parsedRun("laps-only", qualifyingDetails).copy(laps = listOf(lap)))

        assertEquals(listOf(lapless), assertIs<ImportOutcome.Upgraded>(outcome).upgrades.map { it.activity })
        assertEquals(listOf(701L to ActivityChange.Upgraded(details = null, laps = LapsUpgrade.Backfilled(1))), recordedHistory())
        verify(bestEffortRefresh, never()).refresh(any<Activity>(), anyOrNull())
        verify(derivedDataRefresh, never()).refresh(any<Activity>(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(bestEffortRefresh, never()).refresh(any<Collection<Activity>>())
    }
}
