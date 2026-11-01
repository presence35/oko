package com.odesaplay.oko.domain

/**
 * Whether Android will let the monitor keep running.
 *
 * Pure, with the two platform facts read by the caller: one rule then answers the silent standing
 * notice, the settings row and the log bundle, so those three cannot disagree about whether the
 * phone is covered. There is deliberately no "partly covered" state -- a missing exemption is not a
 * degradation of monitoring, it is the reason the monitor stops when the screen goes off.
 *
 * `null` means "the OS would not say", and resolves to [Covered]: a query that fails must never be
 * the thing that tells a user their safety app is off.
 */
sealed interface MonitorCoverage {

    data object Covered : MonitorCoverage

    data class Restricted(val cause: Cause) : MonitorCoverage

    /**
     * Whether the platform will actually stop background work in this state.
     *
     * Only `Battery -> Restricted` does: an app left on the default "Optimized" keeps its
     * foreground service, and the missing Doze exemption merely throttles how quickly it comes
     * back after a stop. This is the line between "tell the user something is wrong" and "tell
     * the user nothing is running" -- a standing alarm belongs only on the second.
     */
    val stopsBackgroundWork: Boolean
        get() = this is Restricted && cause == Cause.BACKGROUND_RESTRICTED

    /** Which restriction is binding. Ordered by severity: the harder one is reported first. */
    enum class Cause {
        /** Battery -> Restricted in app info: the platform stops services of an idle app. */
        BACKGROUND_RESTRICTED,

        /** Not exempt from battery optimization: Doze throttles recovery after any stop. */
        BATTERY_OPTIMIZED
    }

    companion object {
        fun of(
            exemptFromBatteryOptimization: Boolean?,
            backgroundRestricted: Boolean?
        ): MonitorCoverage = when {
            backgroundRestricted == true -> Restricted(Cause.BACKGROUND_RESTRICTED)
            exemptFromBatteryOptimization == false -> Restricted(Cause.BATTERY_OPTIMIZED)
            else -> Covered
        }
    }
}
