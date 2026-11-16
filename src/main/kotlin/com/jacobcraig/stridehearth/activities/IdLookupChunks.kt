package com.jacobcraig.stridehearth.activities

/**
 * Postgres caps one statement at 65,535 bind parameters, and a `size=all` selection can hold thousands of
 * **Activity** ids; `IN (...)` lookups are split into chunks of this size to stay far below the limit.
 */
private const val ID_LOOKUP_CHUNK_SIZE = 1_000

/** Splits ids bound into an `IN (...)` lookup into chunks that each stay under the driver's bind-parameter limit. */
fun Iterable<Long>.chunkedForIdLookup(): List<List<Long>> = chunked(ID_LOOKUP_CHUNK_SIZE)
