package com.jacobcraig.stridehearth.schema

/**
 * Thrown at startup when the backup taken before applying pending migrations fails. The Server stops before any
 * migration runs, because without that backup there would be no supported way back (ADR-0010).
 */
class BackupFailedException(
    reason: String,
    cause: Throwable? = null,
) : IllegalStateException(
        "The Server could not take the database backup it makes before applying pending migrations, so it will not " +
            "migrate or start: $reason",
        cause,
    )
