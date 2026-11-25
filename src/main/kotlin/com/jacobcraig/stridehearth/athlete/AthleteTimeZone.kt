package com.jacobcraig.stridehearth.athlete

import com.jacobcraig.stridehearth.api.RequestValidationException
import com.jacobcraig.stridehearth.users.User
import com.jacobcraig.stridehearth.users.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The **Athlete Time Zone** (ADR-0015): the IANA zone an athlete's local dates are counted in, such as the date of an
 * Activity for the Thresholds in force on it. UTC until the athlete (or the SPA, from the browser) sets it.
 */
@Service
class AthleteTimeZone(
    private val userRepository: UserRepository,
    private val events: ApplicationEventPublisher,
    private val jdbcClient: JdbcClient,
) {
    /** [userId]'s zone: the stored one, else UTC. */
    @Transactional(readOnly = true)
    fun of(userId: Long): ZoneId = zoneOf(userRepository.findById(userId).orElseThrow().timeZone)

    /** The athlete-local date of [instant] for [userId]. */
    @Transactional(readOnly = true)
    fun localDate(
        userId: Long,
        instant: Instant,
    ): LocalDate = instant.atZone(of(userId)).toLocalDate()

    /**
     * Sets [userId]'s zone to the IANA region id [timeZone], publishing [AthleteTimeZoneChanged] when it differs from
     * the stored one. An unknown id, or a bare offset, is a [RequestValidationException]. So is an id the JDK knows
     * but the database does not, such as a zone newer than its tzdata: the rollups and the recompute worker count
     * local dates with `AT TIME ZONE`, which would fail on it for as long as it stayed stored.
     */
    @Transactional
    fun set(
        userId: Long,
        timeZone: String,
    ): User {
        // Region ids only: PostgreSQL reads a bare offset such as "+02:00" with the POSIX sign, the other way round.
        if (timeZone !in ZoneId.getAvailableZoneIds()) {
            throw RequestValidationException(mapOf("timeZone" to "Unknown timezone"))
        }
        val user = userRepository.findById(userId).orElseThrow()
        if (user.timeZone != timeZone) {
            if (!knownToDatabase(timeZone)) throw RequestValidationException(mapOf("timeZone" to "Unknown timezone"))
            user.timeZone = timeZone
            userRepository.save(user)
            events.publishEvent(AthleteTimeZoneChanged(userId))
        }
        return user
    }

    private fun knownToDatabase(timeZone: String): Boolean =
        jdbcClient
            .sql("SELECT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = :timeZone)")
            .param("timeZone", timeZone)
            .query(Boolean::class.java)
            .single()

    companion object {
        /** The zone a stored [timeZone] names, or UTC when none is stored. */
        fun zoneOf(timeZone: String?): ZoneId = timeZone?.let(ZoneId::of) ?: ZoneOffset.UTC
    }
}
