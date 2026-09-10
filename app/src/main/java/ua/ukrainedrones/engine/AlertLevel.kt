package ua.ukrainedrones.engine

/** Severity a zone/threat evaluation resolves to for alert routing. Mirrors [OblastAlert.level]'s
 *  two real values (red = air-raid siren, yellow = tactical/artillery warning) plus NONE for no alert.
 *  Used only for alert gating in [AlertService]; never persisted or serialised. */
enum class AlertLevel {
    /** No active alert at this point. */
    NONE,

    /** Tactical / artillery warning — visual + chime, not a full air-raid siren.
     *  Maps to [OblastAlert.level] == "yellow". */
    YELLOW,

    /** Air-raid alert — siren + banner. Maps to [OblastAlert.level] == "red". */
    RED
}

/** Highest severity present in [alerts] for the given focus. When [cityUa] is non-null and
 *  [scope] is true, only city-covered alerts count. Compares [OblastAlert.level] strings directly
 *  (the plugin layer already normalises them to "red"/"yellow"). */
fun List<OblastAlert>.maxLevelFor(
    token: String?,
    cityUa: String?,
    scope: Boolean
): AlertLevel {
    if (token.isNullOrBlank()) return AlertLevel.NONE
    val red = alertsForLevel("red", token, cityUa, scope)
    val yellow = alertsForLevel("yellow", token, cityUa, scope)
    return when {
        red -> AlertLevel.RED
        yellow -> AlertLevel.YELLOW
        else -> AlertLevel.NONE
    }
}

private fun List<OblastAlert>.alertsForLevel(
    level: String,
    token: String,
    cityUa: String?,
    scope: Boolean
): Boolean {
    if (scope && cityUa != null) {
        return any { it.level == level && it.inOblast(token) && it.coversCity(cityUa) }
    }
    return any { it.level == level && it.inOblast(token) }
}
