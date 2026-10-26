package com.odesaplay.oko

/**
 * A ready-made answer to "who is holding this phone?", offered at the top of Settings → Alerts.
 *
 * Same contract as [AppIntensity]: a ONE-SHOT writer, never a mode. Picking one writes the switches
 * it owns and then forgets it ever existed; nothing re-applies behind the user's back, and every
 * switch stays hand-editable afterwards. Tapping another tile simply states the whole setup again.
 *
 * A persona owns everything on the Alerts page that decides what the phone does about a threat:
 * official-alert levels and scope, the loud overrides, the repeat policy, auto-dismiss, the debris
 * countdown, the day and night bells, and which types ring. It owns no map, morale, GPS, shelter or
 * visual setting — those belong to the person, not to the persona.
 */
enum class AlertPersona {
    /** Loud, few signals, no repair work asked of her: only her own city and the things that kill. */
    GRANDMA,

    /** Nothing missed, nothing repeated: once per threat, region-wide, clears itself. */
    PARENT,

    /** Every level, every type, every change, and it fights silent mode: the earliest warning there is. */
    FRONTLINE,

    /** Red only and in-zone fast bells, loud enough to cut through a schoolbag. */
    TEEN,

    /** A phone in another country watching one city: official levels only, nothing screaming. */
    ABROAD,

    /** A desk job and a phone in a bag: official levels at normal volume, nothing embarrassing. */
    AT_WORK,

    /** Behind the wheel: region-wide official levels, loud, and no repair work to do at 90 km/h. */
    ON_ROAD
}

/**
 * Everything an [AlertPersona] decides. Every field non-nullable: a persona states its full intent,
 * so re-applying one restores exactly that setup instead of half-restoring it.
 */
data class AlertPersonaPatch(
    val officialRed: Boolean,
    val officialYellow: Boolean,
    /** True = official alerts only for the pinned/current city; false = region-wide. */
    val officialCityScope: Boolean,
    /** The loud alarm tone. Silent mode and Do Not Disturb still apply. */
    val sirenOverride: Boolean,
    val criticalOfflineOverride: Boolean,
    val criticalOfflineBypassSilent: Boolean,
    val bootRestartEnabled: Boolean,
    val notifyPolicyEnabled: Boolean,
    val zonePolicy: ZonePolicy,
    val digestWindow: DigestWindow,
    val digestPerType: Boolean,
    val digestMax: Int,
    val autoDismissAllClear: Boolean,
    /** Seconds of debris countdown after the all-clear. 0 = off. */
    val fallingDebrisDelaySec: Int,
    val fastRedMin: Int,
    val fastYellowMin: Int,
    val slowRedArmed: Boolean,
    val slowYellowArmed: Boolean,
    val fastRedArmed: Boolean,
    val fastYellowArmed: Boolean,
    /** Types that get a bell. Everything stays on the map; this is only about sound. */
    val alertTypes: Set<ThreatType>,
    /** The night bells the persona claims. Always stated: half of these people sleep through the war. */
    val night: IntensityNight
)

private val ALL_TYPES = ThreatType.values().toSet()

/** The types that actually kill: drones, cruise, ballistic, glide bombs. */
private val ROCKET_TYPES = setOf(
    ThreatType.SHAHED,
    ThreatType.CRUISE_MISSILE,
    ThreatType.BALLISTIC,
    ThreatType.KAB
)

/** How long debris keeps falling after an all-clear, for the household with children outside. */
private const val PARENT_DEBRIS_SEC = 120

/**
 * The single mapping from persona to settings. Pure, no I/O — [UserPrefs.applyAlertPersona] is the
 * only thing that writes, in one transaction.
 */
fun AlertPersona.patch(): AlertPersonaPatch = when (this) {
    AlertPersona.GRANDMA -> AlertPersonaPatch(
        officialRed = true,
        officialYellow = true,
        // Her own city, because a region-wide feed reads as "everything is coming" to someone who
        // is not going to check where a dot is.
        officialCityScope = true,
        sirenOverride = true,
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = true,
        bootRestartEnabled = true,
        // Master off sounds on every change: fewer decisions to get wrong, nothing swallowed.
        notifyPolicyEnabled = false,
        zonePolicy = ZonePolicy.EVERY_CHANGE,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false,
        digestMax = 10,
        // No auto-dismiss: a card that removes itself is a thing she has to trust.
        autoDismissAllClear = false,
        fallingDebrisDelaySec = 0,
        fastRedMin = 5,
        fastYellowMin = 20,
        // Personal rings off. Distance rings are the most confusing control in the app and she does
        // not travel; official levels already cover her city.
        slowRedArmed = false,
        slowYellowArmed = false,
        fastRedArmed = false,
        fastYellowArmed = false,
        alertTypes = ROCKET_TYPES,
        night = IntensityNight(
            zoneSirenOverride = false,
            officialSirenOverride = true,
            officialRed = true,
            officialYellow = false,
            slowRedArmed = false,
            slowYellowArmed = false,
            fastRedArmed = false,
            fastYellowArmed = false
        )
    )
    AlertPersona.PARENT -> AlertPersonaPatch(
        officialRed = true,
        officialYellow = true,
        // Region-wide: the thing that reaches the kids' school starts somewhere else first.
        officialCityScope = false,
        sirenOverride = true,
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = false,
        bootRestartEnabled = true,
        notifyPolicyEnabled = true,
        zonePolicy = ZonePolicy.ONCE_PER_THREAT,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false,
        digestMax = 10,
        // Cards get out of the way; the notification already did its job.
        autoDismissAllClear = true,
        // The only persona that keeps counting after the all-clear: children are outside again
        // before the debris has stopped coming down.
        fallingDebrisDelaySec = PARENT_DEBRIS_SEC,
        fastRedMin = 5,
        fastYellowMin = 20,
        slowRedArmed = true,
        slowYellowArmed = true,
        fastRedArmed = true,
        fastYellowArmed = true,
        // Flight and recon are noise when your hands are full.
        alertTypes = ALL_TYPES - setOf(ThreatType.AVIATION, ThreatType.RECON, ThreatType.UNKNOWN),
        night = IntensityNight(
            zoneSirenOverride = true,
            officialSirenOverride = true,
            officialRed = true,
            officialYellow = true,
            slowRedArmed = true,
            slowYellowArmed = true,
            fastRedArmed = true,
            fastYellowArmed = true
        )
    )
    AlertPersona.FRONTLINE -> AlertPersonaPatch(
        officialRed = true,
        officialYellow = true,
        officialCityScope = false,
        sirenOverride = true,
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = true,
        bootRestartEnabled = true,
        notifyPolicyEnabled = false,
        zonePolicy = ZonePolicy.EVERY_CHANGE,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false,
        digestMax = 10,
        autoDismissAllClear = false,
        fallingDebrisDelaySec = 0,
        fastRedMin = 5,
        fastYellowMin = 20,
        slowRedArmed = true,
        slowYellowArmed = true,
        fastRedArmed = true,
        fastYellowArmed = true,
        // Everything, including recon: for this person an early, vague signal is the point.
        alertTypes = ALL_TYPES,
        night = IntensityNight(
            zoneSirenOverride = true,
            officialSirenOverride = true,
            officialRed = true,
            officialYellow = true,
            slowRedArmed = true,
            slowYellowArmed = true,
            fastRedArmed = true,
            fastYellowArmed = true
        )
    )
    AlertPersona.TEEN -> AlertPersonaPatch(
        officialRed = true,
        // Yellow off: a warning the teen can do nothing about, every few hours, teaches them to
        // ignore the phone. Red is the one they act on.
        officialYellow = false,
        officialCityScope = true,
        sirenOverride = true,
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = true,
        bootRestartEnabled = true,
        notifyPolicyEnabled = true,
        zonePolicy = ZonePolicy.ONCE_PER_THREAT,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false,
        digestMax = 10,
        autoDismissAllClear = false,
        fallingDebrisDelaySec = 0,
        fastRedMin = 5,
        fastYellowMin = 20,
        // Only the in-zone red bell: one rule that fits on a card, no ring-distance reasoning.
        slowRedArmed = false,
        slowYellowArmed = false,
        fastRedArmed = true,
        fastYellowArmed = false,
        alertTypes = ROCKET_TYPES + ThreatType.FPV_LOITERING,
        night = IntensityNight(
            zoneSirenOverride = true,
            officialSirenOverride = true,
            officialRed = true,
            officialYellow = false,
            slowRedArmed = false,
            slowYellowArmed = false,
            fastRedArmed = true,
            fastYellowArmed = false
        )
    )
    AlertPersona.ABROAD -> AlertPersonaPatch(
        officialRed = true,
        officialYellow = true,
        // The city they are watching, not whatever region the map is showing.
        officialCityScope = true,
        // Their phone is in a quiet office abroad; an alarm tone there buys nothing.
        sirenOverride = false,
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = false,
        // Nobody restarts an app for a city they do not live in.
        bootRestartEnabled = false,
        notifyPolicyEnabled = true,
        zonePolicy = ZonePolicy.ONCE_PER_THREAT,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false,
        digestMax = 10,
        autoDismissAllClear = true,
        fallingDebrisDelaySec = 0,
        fastRedMin = 5,
        fastYellowMin = 20,
        slowRedArmed = false,
        slowYellowArmed = false,
        fastRedArmed = false,
        fastYellowArmed = false,
        alertTypes = ALL_TYPES,
        night = IntensityNight(
            zoneSirenOverride = false,
            officialSirenOverride = false,
            officialRed = true,
            officialYellow = false,
            slowRedArmed = false,
            slowYellowArmed = false,
            fastRedArmed = false,
            fastYellowArmed = false
        )
    )
    AlertPersona.AT_WORK -> AlertPersonaPatch(
        officialRed = true,
        officialYellow = true,
        officialCityScope = true,
        // Normal notification volume: an alarm tone across an open-plan office for a yellow
        // warning is how the app gets muted for good.
        sirenOverride = false,
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = false,
        bootRestartEnabled = true,
        notifyPolicyEnabled = true,
        zonePolicy = ZonePolicy.ONCE_PER_THREAT,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false,
        digestMax = 10,
        autoDismissAllClear = true,
        fallingDebrisDelaySec = 0,
        fastRedMin = 5,
        fastYellowMin = 20,
        // No repair work from someone who cannot leave the desk; official levels carry the shift.
        slowRedArmed = false,
        slowYellowArmed = false,
        fastRedArmed = false,
        fastYellowArmed = false,
        alertTypes = ALL_TYPES - setOf(ThreatType.RECON),
        night = IntensityNight(
            zoneSirenOverride = false,
            officialSirenOverride = false,
            officialRed = true,
            officialYellow = false,
            slowRedArmed = false,
            slowYellowArmed = false,
            fastRedArmed = false,
            fastYellowArmed = false
        )
    )
    AlertPersona.ON_ROAD -> AlertPersonaPatch(
        officialRed = true,
        officialYellow = true,
        // They cover ground across regions; the threat that matters may start two oblasts away.
        officialCityScope = false,
        // Phone in a pocket over road noise: it has to be heard.
        sirenOverride = true,
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = true,
        bootRestartEnabled = true,
        notifyPolicyEnabled = true,
        zonePolicy = ZonePolicy.ONCE_PER_THREAT,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false,
        digestMax = 10,
        autoDismissAllClear = true,
        fallingDebrisDelaySec = 0,
        fastRedMin = 5,
        fastYellowMin = 20,
        // No rings to reason about while driving.
        slowRedArmed = false,
        slowYellowArmed = false,
        fastRedArmed = false,
        fastYellowArmed = false,
        alertTypes = ALL_TYPES - setOf(ThreatType.RECON),
        night = IntensityNight(
            zoneSirenOverride = true,
            officialSirenOverride = true,
            officialRed = true,
            officialYellow = true,
            slowRedArmed = true,
            slowYellowArmed = true,
            fastRedArmed = true,
            fastYellowArmed = true
        )
    )
}

/**
 * The persona the live settings currently describe, or null when they are a hand-made mix.
 * Derived the same way as [matchingIntensity], so a persona tile stops looking selected the moment
 * any switch it owns is changed — a lookup, never a remembered tap.
 */
fun matchingAlertPersona(snapshot: AlertPersonaPatch): AlertPersona? =
    AlertPersona.entries.firstOrNull { it.patch() == snapshot }
