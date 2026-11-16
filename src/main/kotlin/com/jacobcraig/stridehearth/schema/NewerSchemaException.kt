package com.jacobcraig.stridehearth.schema

/**
 * Thrown at startup when this Server's database holds migrations this Release does not know: the Server was upgraded
 * to a newer One-way Release, and the only way back is the backup taken before that upgrade (ADR-0010).
 */
class NewerSchemaException(
    unknownVersion: String,
) : IllegalStateException(
        "This Server's database was upgraded by a newer One-way Release (database schema version $unknownVersion), " +
            "which this Release does not know, so it will not start. Going back to this Release means restoring the " +
            "backup taken before that upgrade; otherwise, start the newer Release again.",
    )
