package com.jacobcraig.stridehearth.system

/**
 * Thrown when an uploaded file cannot be turned into activities. For example, it isn't the
 * expected format, it is damaged and couldn't be repaired, or it contains no usable activity.
 *
 * This is a *domain* exception, meaning it belongs to this app rather than to a library. Callers
 * (e.g. a web controller) can catch it and show the user a clear "bad file" message instead of a
 * generic server error. Format-specific errors, such as the Garmin SDK's `FitRuntimeException`,
 * are wrapped in it and kept as the [cause].
 *
 * The class is `open`, which in Kotlin means other classes may extend it. Kotlin classes are
 * closed (final) unless marked otherwise.
 *
 * @param message a human-readable explanation.
 * @param cause the underlying error, if any.
 */
open class InvalidActivityFileException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A special case of [InvalidActivityFileException]: the file is perfectly valid, but it is the
 * wrong *kind* of file. For example, a FIT course, workout or settings file instead of a recorded
 * activity.
 *
 * It is a separate type so [com.jacobcraig.stridehearth.system.fit.FitActivityParser] can tell it apart from a damaged file and
 * skip the pointless repair attempt. Repair fixes broken bytes, not the file's type.
 *
 * @param message a human-readable explanation, including the type that was found.
 */
class UnsupportedActivityFileTypeException(
    message: String,
) : InvalidActivityFileException(message)
