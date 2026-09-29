package com.odesaplay.oko

/**
 * One reason the app is less protective than the user believes, plus the one place that can fix
 * it. Resolved in a single pure function so every surface (the header's warning glyph, the map's
 * notice line, the first-appearance toast) reads the same ordered list instead of re-deriving
 * policy — adding the next degradation is one arm here and, at most, one `when` case in the UI.
 *
 * Deliberately NOT the same thing as [SilentReason]: this answers "something is wrong, here is how
 * to fix it", while [SilentReason] answers "why are the bells quiet right now" — and a deliberate
 * raid mute belongs in the second and never in the first.
 */
enum class ProblemSeverity {
    /** You will not be warned at all until this is fixed. */
    Critical,

    /** Warnings exist but are narrower than expected. */
    Warn
}

/** Where tapping the problem sends the user. Out-of-app arms are self-explanatory once opened. */
enum class ProblemFix {
    NotificationSettings,
    LocationPermission,
    ZonesPanel,

    /** A source problem is diagnosed in the log, which is where the connection pill leads too. */
    OpenLogs
}

/** Stable identity so the guidance toast can fire once per problem and never repeat. */
enum class ProblemId {
    NotificationsDisabled,
    GpsUnreliable,
    AllChannelsOff,
    AllTypesSilenced,
    SourceOffline
}

data class Problem(
    val id: ProblemId,
    val severity: ProblemSeverity,
    val fix: ProblemFix
)

/**
 * Ordered list of current degradations, most serious first — [List.first] is the one the header
 * acts on. Empty means nothing is wrong.
 *
 * Monitoring being stopped is not listed here: it replaces the whole header with the offline
 * banner, so it is a state of the app rather than a fixable problem inside it.
 */
fun deriveProblems(
    notificationsDisabled: Boolean,
    gpsUnreliable: Boolean,
    anyZoneArmed: Boolean,
    officialRedAlertsEnabled: Boolean,
    officialYellowAlertsEnabled: Boolean,
    allTypesSilenced: Boolean,
    criticalOfflineOverride: Boolean,
    sourceOffline: Boolean
): List<Problem> = buildList {
    // The two causes that mean no warning reaches the user at all outrank the narrower ones.
    if (notificationsDisabled) {
        add(Problem(ProblemId.NotificationsDisabled, ProblemSeverity.Critical, ProblemFix.NotificationSettings))
    }
    if (gpsUnreliable) {
        add(Problem(ProblemId.GpsUnreliable, ProblemSeverity.Critical, ProblemFix.LocationPermission))
    }
    if (!anyZoneArmed && !(officialRedAlertsEnabled || officialYellowAlertsEnabled)) {
        add(Problem(ProblemId.AllChannelsOff, ProblemSeverity.Warn, ProblemFix.ZonesPanel))
    }
    if (allTypesSilenced) {
        add(Problem(ProblemId.AllTypesSilenced, ProblemSeverity.Warn, ProblemFix.ZonesPanel))
    }
    if (!criticalOfflineOverride && sourceOffline) {
        add(Problem(ProblemId.SourceOffline, ProblemSeverity.Warn, ProblemFix.OpenLogs))
    }
}
