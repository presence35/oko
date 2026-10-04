package com.odesaplay.oko

import com.odesaplay.oko.engine.AlertLevel

/**
 * Whether the user wants *this* official level announced. Red and yellow are two separate voices
 * with two separate toggles; a level that is switched off must produce nothing — neither its
 * announcement nor the all-clear that would otherwise close it.
 *
 * Never the `red || yellow` master: that answers "are official alerts on at all", a different
 * question. Using the master as the gate is how an all-clear could fire (with sound) for a level
 * the user had muted.
 */
fun isOfficialLevelEnabled(level: AlertLevel, redEnabled: Boolean, yellowEnabled: Boolean): Boolean =
    when (level) {
        AlertLevel.RED -> redEnabled
        AlertLevel.YELLOW -> yellowEnabled
        AlertLevel.NONE -> false
    }