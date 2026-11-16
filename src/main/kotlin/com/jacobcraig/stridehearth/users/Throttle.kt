package com.jacobcraig.stridehearth.users

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.security.core.AuthenticationException
import org.springframework.stereotype.Component
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Duration

/**
 * How many attempts one key may make in one fixed [window] before the Server throttles it until the window ends.
 */
data class AttemptLimit(
    var maxAttempts: Int,
    var window: Duration,
)

/**
 * The Throttle's limits, under `stridehearth.throttle` (for example `STRIDEHEARTH_THROTTLE_SIGNIN_PERSOURCE_MAXATTEMPTS`).
 * The defaults never throttle normal use, a few sign-ins a day and an occasional mistyped password, but cut a
 * password guesser down to a few dozen guesses an hour.
 */
@Component
@ConfigurationProperties(prefix = "stridehearth.throttle")
data class ThrottleProperties(
    /** Failed sign-ins from one source, whichever usernames they try. */
    var signInPerSource: AttemptLimit = AttemptLimit(maxAttempts = 10, window = Duration.ofMinutes(15)),
    /**
     * Failed sign-ins against one username, from any number of sources, to slow guessing spread across many
     * addresses. Higher than [signInPerSource], so one source always reaches its own limit first and an athlete who
     * mistypes their password is throttled on that device's address, not locked out of their account everywhere.
     */
    var signInPerUsername: AttemptLimit = AttemptLimit(maxAttempts = 20, window = Duration.ofMinutes(15)),
    /** Sign-up attempts from one source, accepted or refused. */
    var signUpPerSource: AttemptLimit = AttemptLimit(maxAttempts = 10, window = Duration.ofHours(1)),
)

/** An attempt the Throttle refused; the same attempt may be made again after [retryAfter]. */
class TooManyAttemptsException(
    val retryAfter: Duration,
) : RuntimeException(tooManyAttemptsMessage(retryAfter))

/**
 * The message of a `TOO_MANY_ATTEMPTS` error, naming the wait rounded up: whole minutes (at least one) below an hour,
 * whole hours from 59 minutes 1 second up.
 */
fun tooManyAttemptsMessage(retryAfter: Duration): String {
    val minutes = maxOf(1L, (retryAfter.toMillis() + 59_999) / 60_000)
    val wait =
        if (minutes < 60) {
            plural(minutes, "minute")
        } else {
            plural((minutes + 59) / 60, "hour")
        }
    return "Too many attempts. Please try again in $wait."
}

private fun plural(
    count: Long,
    unit: String,
) = if (count == 1L) "$count $unit" else "$count ${unit}s"

/**
 * Where an attempt comes from, as the Throttle counts it: the request's remote address, with an IPv6 address
 * widened to its /64 network, because one IPv6 host is usually given a whole /64 and could otherwise step through
 * it to dodge the limit.
 *
 * The remote address is the connecting peer unless the Operator runs the Server with
 * `server.forward-headers-strategy` (`SERVER_FORWARD_HEADERS_STRATEGY=native`), which takes it from a trusted
 * reverse proxy's `X-Forwarded-For` instead (Tomcat believes it from private-network peers, per
 * `server.tomcat.remoteip.internal-proxies`). No request header is read here, so without that setting a client
 * cannot choose its own source; behind a reverse proxy without it, every attempt comes from the proxy and all
 * athletes share one budget.
 */
fun throttleSourceOf(request: HttpServletRequest): String = throttleSourceOf(request.remoteAddr)

fun throttleSourceOf(remoteAddress: String): String {
    // Only an IP literal is parsed, never looked up; anything else is a source of its own.
    val isIpLiteral = remoteAddress.contains(':') || remoteAddress.all { it.isDigit() || it == '.' }
    if (!isIpLiteral) return remoteAddress
    val address = runCatching { InetAddress.getByName(remoteAddress) }.getOrNull() ?: return remoteAddress
    if (address !is Inet6Address) return address.hostAddress
    val network = address.address.copyOf().also { it.fill(0, fromIndex = 8, toIndex = 16) }
    return InetAddress.getByAddress(network).hostAddress + "/64"
}

/**
 * A fixed-window attempt counter per key, kept in Redis so every app instance of a Server shares it and a restart
 * does not reset it. A key's window starts at its first attempt and its count disappears when the window ends.
 */
@Component
class AttemptCounter(
    private val redis: StringRedisTemplate,
) {
    /**
     * Counts one attempt by [key] and returns null if it is within [limit], or how long until the key's window ends
     * if it is over it. An attempt over the limit is not counted, so a throttled client never extends its own wait.
     * If Redis cannot be reached the attempt is allowed: losing the Throttle briefly is better than locking every
     * athlete out of the Server.
     */
    fun take(
        key: String,
        limit: AttemptLimit,
    ): Duration? =
        try {
            val result =
                redis.execute(
                    TAKE,
                    listOf(KEY_PREFIX + key),
                    limit.window.toMillis().toString(),
                    limit.maxAttempts.toString(),
                )
            val count = result[0] as Long
            val remainingMillis = result[1] as Long
            if (count <= limit.maxAttempts) null else Duration.ofMillis(remainingMillis)
        } catch (ex: DataAccessException) {
            log.warn("The Throttle could not reach Redis, so this attempt is not limited: {}", ex.message)
            null
        }

    /** Takes back one attempt counted by [take], as when it turned out not to be the kind that is limited. */
    fun refund(key: String) {
        try {
            redis.execute(REFUND, listOf(KEY_PREFIX + key))
        } catch (ex: DataAccessException) {
            log.warn("The Throttle could not reach Redis to take back an attempt: {}", ex.message)
        }
    }

    companion object {
        /** Prefix of every Throttle key in Redis. */
        const val KEY_PREFIX = "stridehearth:throttle:"

        private val log = LoggerFactory.getLogger(AttemptCounter::class.java)

        /** Counts the attempt, starting the window on the first, and takes it back again when it is over the limit. */
        @Suppress("UNCHECKED_CAST")
        private val TAKE: RedisScript<List<Any>> =
            RedisScript.of(
                """
                local count = redis.call('INCR', KEYS[1])
                local ttl = redis.call('PTTL', KEYS[1])
                if ttl < 0 then
                  redis.call('PEXPIRE', KEYS[1], ARGV[1])
                  ttl = tonumber(ARGV[1])
                end
                if count > tonumber(ARGV[2]) then redis.call('DECR', KEYS[1]) end
                return {count, ttl}
                """.trimIndent(),
                List::class.java,
            ) as RedisScript<List<Any>>

        /** Decrements a live count, never creating a key (and so never one without a window). */
        private val REFUND: RedisScript<Long> =
            RedisScript.of(
                """
                if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('DECR', KEYS[1]) end
                return 0
                """.trimIndent(),
                Long::class.java,
            )
    }
}

/**
 * The sign-in half of the Throttle: counts failed sign-ins per source and per username, and refuses every sign-in,
 * even one with the right password, from a source or against a username that has reached its limit. Successful
 * sign-ins are not counted, so signing in often is never throttled.
 */
@Component
class SignInThrottle(
    private val counter: AttemptCounter,
    private val properties: ThrottleProperties,
) {
    /**
     * Runs [signIn] unless the source or username is throttled, in which case it throws [TooManyAttemptsException].
     * The attempt is counted before [signIn] runs, so many sign-ins sent at once cannot all slip past the limit,
     * and is taken back if [signIn] does not fail with an [AuthenticationException].
     */
    fun <T> attempt(
        source: String,
        username: String,
        signIn: () -> T,
    ): T {
        val keys =
            listOf(
                "sign-in:source:$source" to properties.signInPerSource,
                "sign-in:username:${usernameKey(username)}" to properties.signInPerUsername,
            )
        val waits = keys.map { (key, limit) -> counter.take(key, limit) }
        val longestWait = waits.filterNotNull().maxOrNull()
        if (longestWait != null) {
            // A refused sign-in never reached the password check, so it is not a failed guess.
            keys.zip(waits).filter { (_, wait) -> wait == null }.forEach { (entry) -> counter.refund(entry.first) }
            throw TooManyAttemptsException(longestWait)
        }
        val refundAll = { keys.forEach { (key) -> counter.refund(key) } }
        try {
            return signIn().also { refundAll() }
        } catch (ex: AuthenticationException) {
            throw ex
        } catch (ex: RuntimeException) {
            // Not a rejected password, so not a failed guess either.
            refundAll()
            throw ex
        }
    }

    private fun usernameKey(username: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(username.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/**
 * The sign-up half of the Throttle: counts every sign-up attempt from one source, accepted or refused, so neither
 * account creation nor probing for taken usernames can be done in bulk. It applies whatever the Server's
 * Registration Mode, and is the check every way of creating an account (sign-up and Invite redemption)
 * passes first.
 */
@Component
class SignUpThrottle(
    private val counter: AttemptCounter,
    private val properties: ThrottleProperties,
) {
    /** Counts one sign-up attempt from [source], or throws [TooManyAttemptsException] if it has reached its limit. */
    fun admit(source: String) {
        counter.take("sign-up:source:$source", properties.signUpPerSource)?.let { throw TooManyAttemptsException(it) }
    }
}
