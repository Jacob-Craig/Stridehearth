package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.activities.history.ActivityHistory
import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.athlete.AcclimationRollup
import com.jacobcraig.stridehearth.athlete.AthleteFitnessService
import com.jacobcraig.stridehearth.athlete.AthleteTimeZone
import com.jacobcraig.stridehearth.heatmap.HeatmapRouteRepository
import com.jacobcraig.stridehearth.system.ActivitySport
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityServiceTest {
    private val activityRepository: ActivityRepository = mock()
    private val userRepository: UserRepository = mock()
    private val activityDetailsRepository: ActivityDetailsRepository = mock()
    private val activityLapRepository: ActivityLapRepository = mock()
    private val activityHistory: ActivityHistory = mock()
    private val activityBestEffortRepository: ActivityBestEffortRepository = mock()
    private val bestEffortRefresh: BestEffortRefresh = mock()
    private val derivedDataRefresh: DerivedDataRefresh = mock()
    private val heatmapRouteRepository: HeatmapRouteRepository = mock()
    private val derivedMetricsRepository: ActivityDerivedMetricsRepository = mock()
    private val staminaService: StaminaService = mock()
    private val acclimationRollup: AcclimationRollup = mock()
    private val athleteTimeZone: AthleteTimeZone = mock()
    private val athleteFitnessService: AthleteFitnessService = mock()
    private val detectedIntervalRepository: ActivityDetectedIntervalRepository = mock()
    private val activityService =
        ActivityService(
            activityRepository,
            userRepository,
            activityLapRepository,
            activityHistory,
            activityBestEffortRepository,
            activityDetailsRepository,
            bestEffortRefresh,
            derivedDataRefresh,
            heatmapRouteRepository,
            derivedMetricsRepository,
            staminaService,
            acclimationRollup,
            athleteTimeZone,
            athleteFitnessService,
            detectedIntervalRepository,
        )
    private val owner = User(id = 7L, email = "a@b.com", passwordHash = "x", username = "alice")

    @Test
    fun `getActivitySummaries delegates to the filtered repository query and maps entities to summary dtos`() {
        val pageable = PageRequest.of(0, 10)
        val activity1 =
            Activity(
                id = 1L,
                title = "Activity 1",
                sport = ActivitySport.RUN,
                source = ActivitySource.MANUAL,
                startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                distance = 5.0,
                timeTakenSeconds = 1800,
                user = owner,
            )
        val activity2 =
            Activity(
                id = 2L,
                title = "Activity 2",
                sport = ActivitySport.RIDE,
                source = ActivitySource.MANUAL,
                startedAt = Instant.parse("2026-01-02T00:00:00Z"),
                distance = 10.0,
                timeTakenSeconds = 3600,
                user = owner,
            )
        val entityPage = PageImpl(listOf(activity1, activity2), pageable, 2)
        val filter = ActivityFilter()
        whenever(activityRepository.findFiltered(7L, filter, pageable)).thenReturn(entityPage)
        whenever(activityDetailsRepository.findActivityIdsWithDetails(listOf(1L, 2L))).thenReturn(setOf(2L))

        val result = activityService.getActivitySummaries(7L, filter, pageable)

        assertEquals(2, result.totalElements)
        assertEquals(2, result.content.size)
        assertEquals(1L, result.content[0].id)
        assertEquals("Activity 1", result.content[0].title)
        assertEquals(ActivitySport.RUN, result.content[0].sport)
        assertEquals(2L, result.content[1].id)
        assertEquals("Activity 2", result.content[1].title)
        assertEquals(ActivitySport.RIDE, result.content[1].sport)
        assertFalse(result.content[0].hasProtectedMetrics)
        assertTrue(result.content[1].hasProtectedMetrics, "a manual activity backed by Activity Details has Protected Metrics")
        verify(activityRepository).findFiltered(7L, filter, pageable)
    }

    @Test
    fun `getActivityDetail populates laps, history, and sorted best efforts`() {
        val activity =
            Activity(
                id = 10L,
                title = "10k Run",
                sport = ActivitySport.RUN,
                source = ActivitySource.FIT_UPLOAD,
                startedAt = Instant.parse("2026-03-01T08:00:00Z"),
                distance = 10.0,
                timeTakenSeconds = 3000,
                user = owner,
            )
        whenever(activityRepository.findByIdAndUserId(10L, 7L)).thenReturn(activity)
        whenever(activityLapRepository.findByActivityIdOrderByLapNumberAsc(10L)).thenReturn(emptyList())
        whenever(activityHistory.entriesFor(10L)).thenReturn(emptyList())

        val effort1k =
            ActivityBestEffort(
                id = 1L,
                activityId = 10L,
                sport = ActivitySport.RUN,
                benchmarkDistance = BenchmarkDistance.ONE_KILOMETER,
                elapsedTimeSeconds = 270.0,
                startIndex = 0,
                endIndex = 100,
                startTimeSeconds = 0.0,
                endTimeSeconds = 270.0,
                startDistanceMeters = 0.0,
                endDistanceMeters = 1000.0,
            )
        val effort400m =
            ActivityBestEffort(
                id = 2L,
                activityId = 10L,
                sport = ActivitySport.RUN,
                benchmarkDistance = BenchmarkDistance.FOUR_HUNDRED_METERS,
                elapsedTimeSeconds = 95.0,
                startIndex = 0,
                endIndex = 40,
                startTimeSeconds = 0.0,
                endTimeSeconds = 95.0,
                startDistanceMeters = 0.0,
                endDistanceMeters = 400.0,
            )
        // Return unsorted from repository
        whenever(activityBestEffortRepository.findByActivityId(10L)).thenReturn(listOf(effort1k, effort400m))

        val result = activityService.getActivityDetail(10L, 7L)

        assertNotNull(result)
        assertEquals(2, result.bestEfforts.size)
        // Sorted ascending by benchmark distance (400m first, then 1k)
        assertEquals(BenchmarkDistance.FOUR_HUNDRED_METERS, result.bestEfforts[0].benchmarkDistance)
        assertEquals("400m", result.bestEfforts[0].benchmarkName)
        assertEquals(400.0, result.bestEfforts[0].distanceMeters)
        assertEquals(95.0, result.bestEfforts[0].elapsedTimeSeconds)

        assertEquals(BenchmarkDistance.ONE_KILOMETER, result.bestEfforts[1].benchmarkDistance)
        assertEquals("1k", result.bestEfforts[1].benchmarkName)
        assertEquals(1000.0, result.bestEfforts[1].distanceMeters)
        assertEquals(270.0, result.bestEfforts[1].elapsedTimeSeconds)
        assertEquals(100, result.bestEfforts[1].window.endIndex)
    }

    @Test
    fun `updateActivity updates existing activity and saves to repository`() {
        val existingActivity =
            Activity(
                id = 1L,
                title = "Old Title",
                sport = ActivitySport.RUN,
                source = ActivitySource.MANUAL,
                startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                distance = 5.0,
                timeTakenSeconds = 1800,
                user = owner,
                averagePaceKmh = 10.0,
                averageHeartRate = 140,
                calories = 300,
            )
        whenever(activityRepository.findByIdAndUserId(1L, 7L)).thenReturn(existingActivity)
        whenever(activityRepository.save(any<Activity>())).thenAnswer { it.arguments[0] }

        val request =
            ActivityUpdateRequestDTO(
                title = "New Title",
                sport = ActivitySport.HIKE,
                startedAt = Instant.parse("2026-02-01T00:00:00Z"),
                distance = 10.0,
                timeTakenSeconds = 3600,
                averagePaceKmh = 12.0,
                averageHeartRate = 160,
                calories = 600,
            )

        val result = activityService.updateActivity(1L, 7L, request)

        assertNotNull(result)
        assertEquals("New Title", existingActivity.title)
        assertEquals(ActivitySport.HIKE, existingActivity.sport)
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), existingActivity.startedAt)
        assertEquals(10.0, existingActivity.distance)
        assertEquals(3600, existingActivity.timeTakenSeconds)
        assertEquals(12.0, existingActivity.averagePaceKmh)
        assertEquals(160, existingActivity.averageHeartRate)
        assertEquals(600, existingActivity.calories)
        verify(activityRepository).save(existingActivity)
    }

    @Test
    fun `updateActivity returns null when activity does not exist`() {
        whenever(activityRepository.findByIdAndUserId(999L, 7L)).thenReturn(null)

        val request =
            ActivityUpdateRequestDTO(
                title = "New Title",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2026-02-01T00:00:00Z"),
                distance = 10.0,
                timeTakenSeconds = 3600,
                averagePaceKmh = 12.0,
                averageHeartRate = 160,
                calories = 600,
            )

        val result = activityService.updateActivity(999L, 7L, request)

        assertNull(result)
        verify(activityRepository, never()).save(any())
    }

    @Test
    fun `updateActivity rejects an editable activity's edit that omits required Protected Metrics`() {
        val existingActivity =
            Activity(
                id = 1L,
                title = "Old Title",
                sport = ActivitySport.RUN,
                source = ActivitySource.MANUAL,
                startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                distance = 5.0,
                timeTakenSeconds = 1800,
                user = owner,
            )
        whenever(activityRepository.findByIdAndUserId(1L, 7L)).thenReturn(existingActivity)

        val invalidRequest =
            ActivityUpdateRequestDTO(
                title = "New Title",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2026-02-01T00:00:00Z"),
                distance = 10.0,
                timeTakenSeconds = null,
            )

        val error =
            assertThrows<RequestValidationException> {
                activityService.updateActivity(1L, 7L, invalidRequest)
            }
        assertEquals(setOf("timeTakenSeconds"), error.fieldErrors.keys)
        verify(activityRepository, never()).save(any())
    }

    @Test
    fun `updateActivity returns null when activity belongs to another user`() {
        whenever(activityRepository.findByIdAndUserId(1L, 99L)).thenReturn(null)
        val request = ActivityUpdateRequestDTO("T", ActivitySport.RUN, Instant.parse("2026-02-01T00:00:00Z"), 1.0, 60)

        assertNull(activityService.updateActivity(1L, 99L, request))
        verify(activityRepository, never()).save(any())
    }

    @Test
    fun `createManualActivity assigns the owner and saves a MANUAL activity`() {
        whenever(userRepository.findById(7L)).thenReturn(Optional.of(owner))
        whenever(activityRepository.save(any<Activity>())).thenAnswer { (it.arguments[0] as Activity).apply { id = 1L } }
        val metrics = ProtectedMetrics(Instant.parse("2026-02-01T00:00:00Z"), 1.0, 60, null, null, null)

        activityService.createManualActivity(7L, "T", ActivitySport.RUN, metrics)

        val captor = org.mockito.kotlin.argumentCaptor<Activity>()
        verify(activityRepository).save(captor.capture())
        assertEquals(owner, captor.firstValue.user)
        assertEquals(ActivitySport.RUN, captor.firstValue.sport)
        assertEquals(ActivitySource.MANUAL, captor.firstValue.source)
        val order = inOrder(derivedDataRefresh, activityHistory)
        order.verify(derivedDataRefresh).refresh(captor.firstValue)
        order.verify(activityHistory).record(any(), any())
    }

    @Test
    fun `deleteActivity only deletes activities owned by the user`() {
        whenever(activityRepository.findByIdAndUserId(1L, 99L)).thenReturn(null)

        assertFalse(activityService.deleteActivity(1L, 99L))
        verify(activityRepository, never()).delete(any<Activity>())
        verifyNoInteractions(bestEffortRefresh)
    }

    private fun run(
        id: Long,
        sport: ActivitySport = ActivitySport.RUN,
    ) = Activity(
        id = id,
        title = "Run $id",
        sport = sport,
        source = ActivitySource.FIT_UPLOAD,
        startedAt = Instant.parse("2026-01-01T00:00:00Z"),
        distance = 5.0,
        timeTakenSeconds = 1800,
        user = owner,
    )

    private fun ownedActivities(vararg activities: Activity) {
        whenever(activityRepository.findByIdInAndUserId(activities.map { it.id!! }, 7L)).thenReturn(activities.toList())
    }

    @Test
    fun `reclassifying an Activity refreshes it after the edit`() {
        val activity = run(1L)
        whenever(activityRepository.findByIdAndUserId(1L, 7L)).thenReturn(activity)

        activityService.updateActivity(1L, 7L, ActivityUpdateRequestDTO(title = "Run 1", sport = ActivitySport.RIDE))

        val order = inOrder(activityRepository, bestEffortRefresh, derivedDataRefresh, heatmapRouteRepository, activityHistory)
        order.verify(activityRepository).save(activity)
        order.verify(bestEffortRefresh).refresh(activity)
        order.verify(derivedDataRefresh).refresh(activity, previousSport = ActivitySport.RUN)
        order.verify(heatmapRouteRepository).recomputeForSport(listOf(1L), ActivitySport.RIDE)
        order.verify(activityHistory).record(any(), any())
        assertEquals(ActivitySport.RIDE, activity.sport)
    }

    @Test
    fun `a title-only edit does not refresh`() {
        val activity = run(1L)
        whenever(activityRepository.findByIdAndUserId(1L, 7L)).thenReturn(activity)

        activityService.updateActivity(1L, 7L, ActivityUpdateRequestDTO(title = "Renamed", sport = ActivitySport.RUN))

        verifyNoInteractions(bestEffortRefresh)
        verifyNoInteractions(derivedDataRefresh)
    }

    @Test
    fun `deleting an Activity deletes it and then refreshes it`() {
        val activity = run(1L)
        whenever(activityRepository.findByIdAndUserId(1L, 7L)).thenReturn(activity)

        assertTrue(activityService.deleteActivity(1L, 7L))

        val order = inOrder(activityRepository, bestEffortRefresh, derivedDataRefresh)
        order.verify(activityRepository).delete(activity)
        order.verify(bestEffortRefresh).refresh(activity)
        order.verify(derivedDataRefresh).refresh(activity)
    }

    @Test
    fun `a batch edit refreshes only the reclassified Activities, in one call`() {
        val reclassified = run(1L)
        val alreadyRide = run(2L, ActivitySport.RIDE)
        ownedActivities(reclassified, alreadyRide)

        activityService.batchUpdateActivities(listOf(1L, 2L), 7L, BatchMetadataChange.of(title = null, sport = ActivitySport.RIDE)!!)

        val order = inOrder(bestEffortRefresh, derivedDataRefresh)
        order.verify(bestEffortRefresh).refresh(listOf(reclassified))
        order.verify(derivedDataRefresh).refresh(listOf(reclassified), mapOf(1L to ActivitySport.RUN))
        verifyNoMoreInteractions(bestEffortRefresh)
        verifyNoMoreInteractions(derivedDataRefresh)
    }

    @Test
    fun `a batch edit changes its Activities and records their history in ascending id order, like the recompute worker`() {
        // The lookup returns them out of id order (it has no ORDER BY, and requests are chunked).
        val second = run(2L)
        val first = run(1L)
        whenever(activityRepository.findByIdInAndUserId(listOf(2L, 1L), 7L)).thenReturn(listOf(second, first))

        activityService.batchUpdateActivities(listOf(2L, 1L), 7L, BatchMetadataChange.of(title = null, sport = ActivitySport.RIDE)!!)

        val order = inOrder(activityRepository, derivedDataRefresh, activityHistory)
        order.verify(activityRepository).save(first)
        order.verify(activityRepository).save(second)
        order.verify(derivedDataRefresh).refresh(listOf(first, second), mapOf(1L to ActivitySport.RUN, 2L to ActivitySport.RUN))
        order.verify(activityHistory).record(eq(first), any())
        order.verify(activityHistory).record(eq(second), any())
    }

    @Test
    fun `a batch delete deletes its Activities in ascending id order, like the recompute worker`() {
        val second = run(2L)
        val first = run(1L)
        whenever(activityRepository.findByIdInAndUserId(listOf(2L, 1L), 7L)).thenReturn(listOf(second, first))

        assertEquals(2, activityService.batchDeleteActivities(listOf(2L, 1L), 7L))

        verify(activityRepository).deleteAll(listOf(first, second))
    }

    @Test
    fun `a title-only batch edit does not refresh`() {
        ownedActivities(run(1L), run(2L))

        activityService.batchUpdateActivities(listOf(1L, 2L), 7L, BatchMetadataChange.of(title = "Renamed", sport = null)!!)

        verifyNoInteractions(bestEffortRefresh)
        verifyNoInteractions(derivedDataRefresh)
    }

    @Test
    fun `a batch delete deletes the Activities and then refreshes them in one call`() {
        val first = run(1L)
        val second = run(2L, ActivitySport.RIDE)
        ownedActivities(first, second)

        assertEquals(2, activityService.batchDeleteActivities(listOf(1L, 2L), 7L))

        val order = inOrder(activityRepository, bestEffortRefresh, derivedDataRefresh)
        order.verify(activityRepository).deleteAll(listOf(first, second))
        order.verify(bestEffortRefresh).refresh(listOf(first, second))
        order.verify(derivedDataRefresh).refresh(listOf(first, second))
        verifyNoMoreInteractions(bestEffortRefresh)
        verifyNoMoreInteractions(derivedDataRefresh)
    }

    @Test
    fun `editing only the start of an editable Activity refreshes its derived data with the previous start`() {
        val activity = run(1L).apply { source = ActivitySource.MANUAL }
        whenever(activityRepository.findByIdAndUserId(1L, 7L)).thenReturn(activity)
        val request =
            ActivityUpdateRequestDTO(
                title = "Run 1",
                sport = ActivitySport.RUN,
                startedAt = Instant.parse("2026-02-01T00:00:00Z"),
                distance = 5.0,
                timeTakenSeconds = 1800,
            )

        activityService.updateActivity(1L, 7L, request)

        val order = inOrder(activityRepository, derivedDataRefresh, activityHistory)
        order.verify(activityRepository).save(activity)
        order.verify(derivedDataRefresh).refresh(activity, previousStartedAt = Instant.parse("2026-01-01T00:00:00Z"))
        order.verify(activityHistory).record(any(), any())
        verifyNoInteractions(bestEffortRefresh)
    }
}
