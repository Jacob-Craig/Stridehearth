package com.jacobcraig.stridehearth.testutils

import com.jacobcraig.stridehearth.users.AttemptCounter
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Forgets every attempt the Throttle has counted. MockMvc requests all come from 127.0.0.1 and test classes share a
 * Spring context (and so a Redis), so a test that signs in or up starts from a clean count.
 */
fun StringRedisTemplate.clearThrottle() {
    val keys = keys("${AttemptCounter.KEY_PREFIX}*")
    if (keys.isNotEmpty()) delete(keys)
}
