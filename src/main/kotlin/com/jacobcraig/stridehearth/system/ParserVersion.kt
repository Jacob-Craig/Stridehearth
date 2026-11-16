package com.jacobcraig.stridehearth.system

/**
 * The **Parser Version**: the revision of the decoding logic that produced a set of Activity Details and Laps.
 * It rises whenever decoding the same file would produce different Activity Details or Laps.
 *
 * Every [ParsedActivity] carries the version that decoded it; a parser's own current version is a constant
 * on that parser (e.g. [com.jacobcraig.stridehearth.system.fit.FitActivityParser.PARSER_VERSION]).
 *
 * @property value the stored form (`activity_details.parser_version`, a SMALLINT).
 */
data class ParserVersion(
    val value: Short,
) : Comparable<ParserVersion> {
    constructor(value: Int) : this(value.toShort())

    override fun compareTo(other: ParserVersion): Int = value.compareTo(other.value)
}
