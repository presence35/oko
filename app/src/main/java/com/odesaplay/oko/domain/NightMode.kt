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

/**
 * The active value of a *night policy* key (official red/yellow enable, siren overrides, city
 * scope) for this tick. The night value is an **override**, not a second setting: `null` means the
 * user never set one, so the day value carries over for the whole window.
 *
 * That `null` case is the fix for the reported bug. Every one of these keys used to be a plain
 * boolean with its own hardcoded default, so an active window silently replaced a day choice the
 * user had made — with "city level" on, a raion yellow anywhere in the oblast still rang the
 * banner at night, because the night default for that key was `false` = whole oblast. A quiet mode
 * may narrow what reaches the user, never widen it behind their back, and four other keys carried
 * the same trap.
 *
 * One function, not five copies: the copies each carried their own default, which is exactly how
 * they drifted apart.
 */
fun effectiveNightPolicy(day: Boolean, night: Boolean?, nightActive: Boolean): Boolean =
    if (nightActive) night ?: day else day

/** A night override resolved for writing: an unset override means "same as day", so snapshots and
 *  presets state the [day] value they would inherit rather than inventing a third answer. */
fun nightOverrideOr(night: Boolean?, day: Boolean): Boolean = night ?: day

/**
 * The five night policy rows as the night page shows them: what the night will actually do,
 * resolved. Built once by the ViewModel ([UserPreferences.nightPolicy]) so the card renders instead
 * of re-running the resolution at every row (mirror rule).
 */
@Immutable
data class NightPolicy(
    val officialRed: Boolean,
    val officialYellow: Boolean,
    val officialSirenOverride: Boolean,
    val zoneSirenOverride: Boolean,
    val cityScope: Boolean
)

/** The night's effective alert policy: each key's night value if it has one, else the day value. */
fun UserPreferences.nightPolicy(): NightPolicy = NightPolicy(
    officialRed = nightOverrideOr(nightOfficialRedEnabled, officialRedAlertsEnabled),
    officialYellow = nightOverrideOr(nightOfficialYellowEnabled, officialYellowAlertsEnabled),
    officialSirenOverride = nightOverrideOr(nightOfficialSirenOverride, sirenOverride),
    zoneSirenOverride = nightOverrideOr(nightZoneSirenOverride, sirenOverride),
    cityScope = nightOverrideOr(nightOfficialAlertCityScope, officialAlertCityScope)
)

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
    /** The four policy keys are tri-state: `null` = the night had no opinion and was following the
     *  day value, which the restore must put back rather than pin. */
    val zoneSirenOverride: Boolean?,
    val officialSirenOverride: Boolean?,
    val officialRed: Boolean?,
    val officialYellow: Boolean?
) {
    /** Every alert is off — an inherited key is not a choice to be off. */
    val isSilent: Boolean
        get() = !slowRedArmed && !slowYellowArmed && !fastRedArmed && !fastYellowArmed &&
            zoneSirenOverride == false && officialSirenOverride == false &&
            officialRed == false && officialYellow == false

    /** The button's ON state: custom zones on (so the night bells actually apply) and every alert off. */
    val isMuted: Boolean get() = useCustomZones && isSilent

    fun encode(): String = buildString(BITS) {
        append(bit(useCustomZones))
        append(bit(slowRedArmed))
        append(bit(slowYellowArmed))
        append(bit(fastRedArmed))
        append(bit(fastYellowArmed))
        append(bit(zoneSirenOverride))
        append(bit(officialSirenOverride))
        append(bit(officialRed))
        append(bit(officialYellow))
    }

    companion object {
        private const val BITS = 9

        /** '1' on, '0' off, 'i' inherit the day value. */
        private fun bit(v: Boolean?): Char = when (v) {
            true -> '1'
            false -> '0'
            null -> 'i'
        }

        private fun read(ch: Char): Boolean? = when (ch) {
            '1' -> true
            '0' -> false
            else -> null
        }

        /** The exact settings "Just let me sleep!" writes. */
        val MUTED = NightSleepPreset(
            useCustomZones = true,
            slowRedArmed = false, slowYellowArmed = false,
            fastRedArmed = false, fastYellowArmed = false,
            zoneSirenOverride = false, officialSirenOverride = false,
            officialRed = false, officialYellow = false
        )

        fun decode(raw: String?): NightSleepPreset? {
            if (raw == null || raw.length != BITS || raw.any { it != '0' && it != '1' && it != 'i' }) return null
            return NightSleepPreset(
                useCustomZones = raw[0] == '1',
                slowRedArmed = raw[1] == '1',
                slowYellowArmed = raw[2] == '1',
                fastRedArmed = raw[3] == '1',
                fastYellowArmed = raw[4] == '1',
                zoneSirenOverride = read(raw[5]),
                officialSirenOverride = read(raw[6]),
                officialRed = read(raw[7]),
                officialYellow = read(raw[8])
            )
        }
    }
}
