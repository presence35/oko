package ua.ukrainedrones.theme

/** Single source of truth for every color in the app. Shipping code must not contain
 *  raw color literals — reference a token here (Compose: `Color(AppPalette.X)`,
 *  canvas/MapLibre/service: `AppPalette.X.toInt()`). */
object AppPalette {
    const val AlertRed: Long = 0xFFEF4444
    const val AlertYellow: Long = 0xFFF59E0B
    const val CityTextDefault: Long = 0xFFE2E8F0

    const val RedFill: Long = 0x73EF4444
    const val RedLine: Long = 0xE1EF4444
    const val YellowFill: Long = 0x5AF59E0B
    const val YellowLine: Long = 0xBEF59E0B
    const val ZoneRed: Long = 0xE6EF4444
    const val ZoneYellow: Long = 0xC8F59E0B

    const val SafeGreen: Long = 0xFF4CAF50
    const val DegradedOrange: Long = 0xFFFB8C00
    const val ShelterMobile: Long = 0xFFFFA000
    const val WarningOrange: Long = 0xFFE65100
    const val AreaOnlyDot: Long = 0xFFFFB74D
    const val Primary: Long = 0xFF64B5F6
    const val GpsBlue: Long = 0xFF2196F3
    const val WidgetBlue: Long = 0xFF1E88E5
    const val UkraineBlue: Long = 0xFF005BBB
    const val Gold: Long = 0xFFFFD700

    const val Background: Long = 0xFF121212
    const val Surface: Long = 0xFF1A1A1A
    const val SurfaceVariant: Long = 0xFF232323
    const val Card: Long = 0xFF1E1E1E
    const val CardDeep: Long = 0xFF1B1B1B
    const val CardAlt: Long = 0xFF252525
    const val Panel: Long = 0xFF151515
    const val Chip: Long = 0xFF2A2A2A
    const val Toast: Long = 0xFF2A2A2E
    const val ToastBorder: Long = 0xFF4A4A4E

    const val TextPrimary: Long = 0xFFEDEDED
    const val TextSecondary: Long = 0xFF9E9E9E
    const val TextTertiary: Long = 0xFFB0B0B0
    const val TextDetail: Long = 0xFFCCCCCC
    const val PillNumber: Long = 0xFFCFCFCF
    const val Border: Long = 0xFF3A3A3A
    const val BorderSoft: Long = 0xFF555555
    const val BorderMuted: Long = 0xFF666666
    const val IconDisabled: Long = 0xFF777777
    const val HandleGrey: Long = 0xFF888888
    const val GhostTick: Long = 0xFFB0BEC5
    const val MapBackground: Long = 0xFF0D1117
    const val NightSection: Long = 0xFF1A1130
    const val NightBorder: Long = 0xFF44357A
    const val WarningBg: Long = 0xFFFFF3E0
    const val WarningLine: Long = 0xFF3A2B00
    const val StopPillBg: Long = 0xFF3A2E00

    const val Mask: Long = 0xE60D1117
    const val LandBorder: Long = 0x46FFFFFF
    const val OblastBorder: Long = 0x78B4B4C8
    const val RaionBorder: Long = 0x46B4B4C8
    const val GpsGlow: Long = 0x782196F3
    const val GpsGlowOff: Long = 0x6E9E9E9E
}