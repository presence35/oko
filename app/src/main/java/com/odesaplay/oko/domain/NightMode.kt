package com.odesaplay.oko

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import com.odesaplay.oko.engine.ZoneParams

/** Night-mode schedule window. Times are minutes since midnight (0–1439). */
@Immutable
data class NightConfig(
    val enabled: Boolean,
    val startMin: Int,
    val endMin: Int
)

/** The four independent armed bells: slow/fast x red/yellow. */
@Immutable
data class ZoneArmed(
    val slowRed: Boolean,
    val slowYellow: Boolean,
    val fastRed: Boolean,
    val fastYellow: Boolean
)

/** Night-mode zone values: custom thresholds + armed bells, applied while the window is active. */
@Immutable
data class NightZones(
    val slowRedKm: Int,
    val slowYellowKm: Int,
    val fastRedMin: Int,
    val fastYellowMin: Int,
    val slowRedArmed: Boolean,
    val slowYellowArmed: Boolean,
    val fastRedArmed: Boolean,
    val fastYellowArmed: Boolean
)

/**
 * True when [nowMillis] falls inside the night window. Overnight windows (start > end,
 * e.g. 22:00→07:00) wrap past midnight. start == end (or disabled) means never active.
 * Shared by MainViewModel (UI) and AlertService (notifications) — the mirror rule.
 */
fun isNightActive(config: NightConfig, nowMillis: Long): Boolean {
    if (!config.enabled || config.startMin == config.endMin) return false
    val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneId.systemDefault())
        .toLocalTime()
    return isWithinNight(now.hour * 60 + now.minute, config.startMin, config.endMin)
}

/**
 * How long the night window lasts, in minutes, wrap included (22:00→07:00 = 540). Zero when
 * start == end, since such a window is never active and so has no length to state. Shown in the
 * settings card so an absurd window (12:00→10:00 reads as 1320) is visible instead of silent.
 */
fun nightWindowDurationMin(startMin: Int, endMin: Int): Int =
    if (startMin == endMin) 0 else (endMin - startMin + 1440) % 1440

/** The active zone thresholds: night custom values while the window is active, else the day ones. */
fun effectiveZoneParams(
    day: ZoneParams,
    night: NightZones,
    useNightZones: Boolean,
    nightActive: Boolean
): ZoneParams = if (nightActive && useNightZones) {
    ZoneParams(
        night.slowRedKm, night.slowYellowKm, night.fastRedMin, night.fastYellowMin,
        night.slowRedArmed, night.slowYellowArmed, night.fastRedArmed, night.fastYellowArmed
    )
} else {
    day
}

/** The active armed bells: night custom values while the window is active, else the day ones. */
fun effectiveArmed(
    day: ZoneArmed,
    night: NightZones,
    useNightZones: Boolean,
    nightActive: Boolean
): ZoneArmed = if (nightActive && useNightZones) {
    ZoneArmed(
        night.slowRedArmed, night.slowYellowArmed,
        night.fastRedArmed, night.fastYellowArmed
    )
} else {
    day
}

/** Simple minute-based night window check. Overnight windows (start > end) wrap past midnight.
 *  Assumes a non-empty window: with `start == end` the wrap branch would read as *forever*, so
 *  rejecting that case is the caller's job ([isNightActive] does). */
fun isWithinNight(nowMin: Int, startMin: Int, endMin: Int): Boolean =
    if (startMin < endMin) nowMin in startMin until endMin
    else nowMin >= startMin || nowMin < endMin

/** The official-alert enable for this tick: the night value while the window is active, else the day one. */
fun effectiveOfficialEnabled(day: Boolean, night: Boolean, nightActive: Boolean): Boolean =
    if (nightActive) night else day

/** The active city-scope gate: the night value while the window is active, else the day one.
 *  Same `if (nightActive)` idiom as [effectiveZoneParams]/[effectiveArmed]/[effectiveOfficialEnabled]
 *  — a consumer that ORs the two instead (`day || night`) cannot turn scoping off for the night,
 *  and the map ends up disagreeing with the service. */
fun effectiveCityScope(day: Boolean, night: Boolean, nightActive: Boolean): Boolean =
    if (nightActive) night else day

/** The night-mode zone values as one value object, so the service and the widget cannot read a
 *  different subset of the night prefs (the widget used to read none of them). */
val UserPreferences.nightZones: NightZones
    get() = NightZones(
        nightSlowRedKm, nightSlowYellowKm, nightFastRedMin, nightFastYellowMin,
        nightSlowRedArmed, nightSlowYellowArmed, nightFastRedArmed, nightFastYellowArmed
    )

/**
 * The night settings the "Just let me sleep!" button owns — the four armed bells, the two
 * siren overrides and the two official enables, plus the custom-zones flag (which must be
 * on for [effectiveArmed] to apply the night bells at all). Encoded to a single string so
 * the settings store can remember exactly what to put back when the button is switched off.
 */
@Immutable
data class NightSleepPreset(
    val useCustomZones: Boolean,
    val slowRedArmed: Boolean,
    val slowYellowArmed: Boolean,
    val fastRedArmed: Boolean,
    val fastYellowArmed: Boolean,
    val zoneSirenOverride: Boolean,
    val officialSirenOverride: Boolean,
    val officialRed: Boolean,
    val officialYellow: Boolean
) {
    /** Every alert is off. */
    val isSilent: Boolean
        get() = !slowRedArmed && !slowYellowArmed && !fastRedArmed && !fastYellowArmed &&
            !zoneSirenOverride && !officialSirenOverride && !officialRed && !officialYellow

    /** The button's ON state: custom zones on (so the night bells actually apply) and every alert off. */
    val isMuted: Boolean get() = useCustomZones && isSilent

    fun encode(): String = buildString(BITS) {
        append(if (useCustomZones) '1' else '0')
        append(if (slowRedArmed) '1' else '0')
        append(if (slowYellowArmed) '1' else '0')
        append(if (fastRedArmed) '1' else '0')
        append(if (fastYellowArmed) '1' else '0')
        append(if (zoneSirenOverride) '1' else '0')
        append(if (officialSirenOverride) '1' else '0')
        append(if (officialRed) '1' else '0')
        append(if (officialYellow) '1' else '0')
    }

    companion object {
        private const val BITS = 9

        /** The exact settings "Just let me sleep!" writes. */
        val MUTED = NightSleepPreset(
            useCustomZones = true,
            slowRedArmed = false, slowYellowArmed = false,
            fastRedArmed = false, fastYellowArmed = false,
            zoneSirenOverride = false, officialSirenOverride = false,
            officialRed = false, officialYellow = false
        )

        fun decode(raw: String?): NightSleepPreset? {
            if (raw == null || raw.length != BITS || raw.any { it != '0' && it != '1' }) return null
            return NightSleepPreset(
                useCustomZones = raw[0] == '1',
                slowRedArmed = raw[1] == '1',
                slowYellowArmed = raw[2] == '1',
                fastRedArmed = raw[3] == '1',
                fastYellowArmed = raw[4] == '1',
                zoneSirenOverride = raw[5] == '1',
                officialSirenOverride = raw[6] == '1',
                officialRed = raw[7] == '1',
                officialYellow = raw[8] == '1'
            )
        }
    }
}
