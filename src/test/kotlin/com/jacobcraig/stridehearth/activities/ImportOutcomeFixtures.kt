package com.jacobcraig.stridehearth.activities

import com.jacobcraig.stridehearth.system.ParsedActivity
import com.jacobcraig.stridehearth.system.fit.FitActivityParser
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.core.io.InputStreamSource
import java.io.ByteArrayInputStream
import java.io.InputStream

/** The **Activities** an import created, for fixtures that seed Activities through the import module. */
val ImportOutcome.createdActivities: List<Activity>
    get() =
        when (this) {
            is ImportOutcome.Created -> created
            is ImportOutcome.Mixed -> created
            is ImportOutcome.Upgraded, is ImportOutcome.Duplicate -> emptyList()
        }

/** A stand-in activity file that a parser prepared by [acceptParsedFixtures] "parses" into [parsed]. */
class ParsedFixtureFile(
    val parsed: List<ParsedActivity>,
) : InputStreamSource {
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
}

/**
 * Lets a spied parser return a [ParsedFixtureFile]'s activities while parsing every real file normally,
 * so fixtures can seed hand-built activities through the public import path. Safe across threads.
 */
fun FitActivityParser.acceptParsedFixtures() {
    doAnswer { invocation ->
        (invocation.getArgument<InputStreamSource>(0) as? ParsedFixtureFile)?.parsed ?: invocation.callRealMethod()
    }.whenever(this).parse(any())
}

/** Imports hand-built [parsedActivities] as one file; the parser must be prepared by [acceptParsedFixtures]. */
fun ActivityImportService.importParsed(
    parsedActivities: List<ParsedActivity>,
    userId: Long,
    source: ActivitySource = ActivitySource.FIT_UPLOAD,
): ImportOutcome = import(ParsedFixtureFile(parsedActivities), userId, source)
