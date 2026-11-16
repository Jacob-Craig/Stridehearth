package com.jacobcraig.stridehearth.users

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/**
 * Turns an [UnknownRegistrationModeException] into Spring Boot's plain "APPLICATION FAILED TO START" report, so the
 * Operator sees what to fix instead of a stack trace. Registered in `META-INF/spring.factories`.
 */
class UnknownRegistrationModeFailureAnalyzer : AbstractFailureAnalyzer<UnknownRegistrationModeException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: UnknownRegistrationModeException,
    ): FailureAnalysis =
        FailureAnalysis(
            "The Server's Registration Mode is set to '${cause.setting}', which is neither 'open' nor 'closed'. " +
                "The Server will not start rather than guess, so a typo can never open it to sign-ups.",
            "Set $REGISTRATION_MODE_ENV_VAR (or $REGISTRATION_MODE_PROPERTY) to 'closed' to let only the first " +
                "account register itself, or to 'open' to let anyone create an account. Leave it unset for closed.",
            cause,
        )
}
