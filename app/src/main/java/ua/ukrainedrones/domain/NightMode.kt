package ua.ukrainedrones

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import ua.ukrainedrones.engine.ZoneParams

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
    val minute = now.hour * 60 + now.minute
    return if (config.startMin < config.endMin) {
        minute in config.startMin until config.endMin
    } else {
        minute >= config.startMin || minute < config.endMin
    }
}

/** The active zone thresholds: night custom values while the window is active, else the day ones. */
fun effectiveZoneParams(
    day: ZoneParams,
    night: NightZones,
    useNightZones: Boolean,
    nightActive: Boolean
): ZoneParams = if (nightActive && useNightZones) {
    ZoneParams(night.slowRedKm, night.slowYellowKm, night.fastRedMin, night.fastYellowMin)
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

/** Simple minute-based night window check. Overnight windows (start > end) wrap past midnight. */
fun isWithinNight(nowMin: Int, startMin: Int, endMin: Int): Boolean =
    if (startMin < endMin) nowMin in startMin until endMin
    else nowMin >= startMin || nowMin < endMin
