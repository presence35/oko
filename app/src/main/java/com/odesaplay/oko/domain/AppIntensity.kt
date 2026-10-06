package com.odesaplay.oko

/**
 * How hard the app is allowed to work. Four ready-made setups, offered in the first-launch
 * wizard and again in Settings → Reliability.
 *
 * An intensity is a ONE-SHOT writer, never a mode: picking one writes the settings it owns and
 * then forgets it ever existed. Every switch stays individually editable afterwards, and the
 * app never re-applies behind the user's back. The alternative — a live profile that re-asserts
 * itself — silently undoes hand-tuning, which is the one thing a settings screen must not do.
 *
 * The presets exist because the app is deliberately paranoid (background survival, offline
 * handling, aggressive sound). That paranoia is correct near the front line and absurd in a
 * living room, and the knobs that control it are scattered across four settings pages.
 */
enum class AppIntensity { ALWAYS_READY, BALANCED, QUIET, WATCHING }

/**
 * The night window, as a preset sees it. A group rather than eight flat fields because it is the
 * one part of the patch that is **claimed, not always stated**: only ALWAYS_READY has an opinion
 * about 3am, and a preset that wrote night bells it did not mean to change would silently cancel
 * the user's own "Just let me sleep!" (see [UserPrefs.applyIntensity]).
 */
data class IntensityNight(
    val zoneSirenOverride: Boolean,
    val officialSirenOverride: Boolean,
    val officialRed: Boolean,
    val officialYellow: Boolean,
    val slowRedArmed: Boolean,
    val slowYellowArmed: Boolean,
    val fastRedArmed: Boolean,
    val fastYellowArmed: Boolean
)

/**
 * Every setting an [AppIntensity] decides. No nullable fields except [night]: a preset states its
 * full intent, so re-applying one from Settings restores exactly that setup instead of
 * half-restoring it.
 *
 * Fields deliberately absent, because no preset has an opinion on them: language, map appearance,
 * official-alert levels, the neutralized/alarm tallies, the morale master, GPS follow-me,
 * shelters-for-kids and the pinned city. Those belong to the person, not to a persona.
 */
data class IntensityPatch(
    // Reliability: how much the phone is expected to fight for the alert.
    val sirenOverride: Boolean,
    val criticalOfflineOverride: Boolean,
    val criticalOfflineBypassSilent: Boolean,
    val bootRestartEnabled: Boolean,
    val periodicGps: Boolean,
    /** False arms the contextual "let it run in the background" ask; true silences it for good. */
    val batteryAskEnabled: Boolean,
    // Alert frequency.
    val notifyPolicyEnabled: Boolean,
    val zonePolicy: ZonePolicy,
    val digestWindow: DigestWindow,
    val digestPerType: Boolean,
    val digestMax: Int,
    // Personal rings and how fast they escalate.
    val slowRedKm: Int,
    val slowYellowKm: Int,
    val fastRedMin: Int,
    val fastYellowMin: Int,
    val slowRedArmed: Boolean,
    val slowYellowArmed: Boolean,
    val fastRedArmed: Boolean,
    val fastYellowArmed: Boolean,
    /** Types that get a bell. Everything stays on the map; this is only about sound. */
    val alertTypes: Set<ThreatType>,
    /** Null = the night window is not this preset's business. See [IntensityNight]. */
    val night: IntensityNight? = null,
    // How much the map performs.
    val followBullet: Boolean,
    val highQualityExplosions: Boolean,
    val deathAnimationStyle: DeathAnimationStyle,
    val sheltersEnabled: Boolean
)

/** The quiet types — worth seeing on the map, not worth a notification. */
private val OVERFLIGHT_TYPES = setOf(ThreatType.AVIATION, ThreatType.RECON, ThreatType.UNKNOWN)

private val ALL_TYPES = ThreatType.values().toSet()

/**
 * The single mapping from intensity to settings. Pure, no I/O — [UserPrefs.applyIntensity] is the
 * only thing that writes, in one transaction.
 */
fun AppIntensity.patch(): IntensityPatch = when (this) {
    AppIntensity.ALWAYS_READY -> IntensityPatch(
        sirenOverride = true,
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = true,
        bootRestartEnabled = true,
        periodicGps = true,
        batteryAskEnabled = true,
        // Master off means "every entry and escalation sounds", which is the same verdict as
        // EVERY_CHANGE — so leave the throttle off rather than pretending to configure it.
        notifyPolicyEnabled = false,
        zonePolicy = ZonePolicy.EVERY_CHANGE,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false,
        digestMax = 10,
        slowRedKm = 20,
        slowYellowKm = 50,
        fastRedMin = 5,
        fastYellowMin = 20,
        slowRedArmed = true,
        slowYellowArmed = true,
        fastRedArmed = true,
        fastYellowArmed = true,
        alertTypes = ALL_TYPES,
        followBullet = true,
        highQualityExplosions = true,
        deathAnimationStyle = DeathAnimationStyle.ADVANCED,
        sheltersEnabled = true,
        // "Always ready" means 3am too: every night bell armed and both siren overrides on, so the
        // night window stops being the one place the app goes quiet.
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
    AppIntensity.BALANCED -> IntensityPatch(
        sirenOverride = false,
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = false,
        bootRestartEnabled = true,
        periodicGps = false,
        batteryAskEnabled = true,
        // On, so ONCE_PER_THREAT is the policy that actually runs. The stored default leaves the
        // master off, which makes the engine sound EVERY_CHANGE regardless of the preset below.
        // On, so ONCE_PER_THREAT is the policy that actually runs — the shipped default used to
        // leave this off, which made the engine sound EVERY_CHANGE no matter what was stored.
        notifyPolicyEnabled = true,
        zonePolicy = ZonePolicy.ONCE_PER_THREAT,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false,
        digestMax = 10,
        slowRedKm = 20,
        slowYellowKm = 50,
        fastRedMin = 5,
        fastYellowMin = 20,
        slowRedArmed = true,
        slowYellowArmed = true,
        fastRedArmed = true,
        fastYellowArmed = false,
        alertTypes = ALL_TYPES,
        followBullet = true,
        highQualityExplosions = true,
        deathAnimationStyle = DeathAnimationStyle.ADVANCED,
        sheltersEnabled = true
    )
    AppIntensity.QUIET -> IntensityPatch(
        sirenOverride = false,
        // A stale feed must still be able to say "red", even for someone with no time for this.
        criticalOfflineOverride = true,
        criticalOfflineBypassSilent = false,
        bootRestartEnabled = true,
        periodicGps = false,
        batteryAskEnabled = true,
        notifyPolicyEnabled = true,
        zonePolicy = ZonePolicy.DIGEST,
        digestWindow = DigestWindow.MIN_10,
        digestPerType = true,
        digestMax = 5,
        slowRedKm = 15,
        slowYellowKm = 40,
        fastRedMin = 5,
        fastYellowMin = 20,
        slowRedArmed = true,
        slowYellowArmed = true,
        fastRedArmed = true,
        fastYellowArmed = false,
        alertTypes = ALL_TYPES - OVERFLIGHT_TYPES,
        followBullet = false,
        highQualityExplosions = false,
        deathAnimationStyle = DeathAnimationStyle.SIMPLE,
        // Morale stays on and the tally stays on: the counter is the cheap thirty-second payoff,
        // the effects are the expensive noise.
        sheltersEnabled = true
    )
    AppIntensity.WATCHING -> IntensityPatch(
        sirenOverride = false,
        // No overriding an offline or stale feed: for curiosity, a wrong alert is worse than none.
        criticalOfflineOverride = false,
        criticalOfflineBypassSilent = false,
        // Nobody restarts an app they are not watching. Nothing gets woken up on purpose.
        bootRestartEnabled = false,
        periodicGps = false,
        // No atom rearranging in Pluto. This also silences the "Oko was asleep" notice.
        batteryAskEnabled = false,
        notifyPolicyEnabled = true,
        zonePolicy = ZonePolicy.DIGEST,
        digestWindow = DigestWindow.MIN_60,
        digestPerType = false,
        digestMax = 10,
        slowRedKm = 20,
        slowYellowKm = 50,
        // Slow escalation, and not armed at all: no in-zone bells for someone who lives far away.
        fastRedMin = 10,
        fastYellowMin = 45,
        slowRedArmed = false,
        slowYellowArmed = false,
        fastRedArmed = false,
        fastYellowArmed = false,
        alertTypes = ALL_TYPES - setOf(ThreatType.UNKNOWN),
        followBullet = false,
        highQualityExplosions = false,
        deathAnimationStyle = DeathAnimationStyle.CLASSIC,
        sheltersEnabled = false
    )
}