package ua.ukrainedrones.community

/**
 * Gate evaluator that protects the network by ensuring a REST poll to Ubilling
 * is ONLY executed if:
 * 1. An incoming alert touches a frontline district that supports community sirens.
 * 2. AND the user's active focus (GPS location or raw city name) is actually in or near that district.
 *
 * For users in Kyiv, Odesa, Lviv, etc., or during quiet periods, this gate returns
 * [GateDecision.shouldPoll] = false, completely eliminating unnecessary network calls.
 *
 * Fully language-agnostic: operates purely on raw IDs, names, and stems.
 */
object CommunityAlertGate {

    /**
     * Evaluates whether a REST fetch is required given the current alerts and user focus.
     *
     * @param activeAlertKeysOrNames List of raw alert names or keys from WebSocket.
     * @param userLocation User's current GPS position, if available.
     * @param userCityName User's raw city string, if available.
     * @return [GateDecision] indicating whether to poll and why.
     */
    fun evaluate(
        activeAlertKeysOrNames: Collection<String>,
        userLocation: LatLon?,
        userCityName: String?
    ): GateDecision {
        if (activeAlertKeysOrNames.isEmpty()) {
            return GateDecision(
                shouldPoll = false,
                reason = "No active alerts"
            )
        }

        // 1. Identify which frontline raions currently have active alerts
        val alertedFrontlineRaions = mutableListOf<FrontlineRaion>()
        for (alertKey in activeAlertKeysOrNames) {
            val matched = FrontlineCommunityCatalog.findRaionForAlert(alertKey)
            if (matched != null && !alertedFrontlineRaions.contains(matched)) {
                alertedFrontlineRaions.add(matched)
            }
        }

        if (alertedFrontlineRaions.isEmpty()) {
            return GateDecision(
                shouldPoll = false,
                reason = "No frontline community-capable raions in active alert list"
            )
        }

        // 2. Resolve user community or proximity to any of the alerted frontline raions
        // Case A: User has a city set
        if (!userCityName.isNullOrBlank()) {
            val userResolved = FrontlineCommunityCatalog.findCommunityForCity(userCityName)
            if (userResolved != null) {
                val (userRaion, userCommunity) = userResolved
                val isAlerted = alertedFrontlineRaions.any { it.id == userRaion.id }
                if (isAlerted) {
                    return GateDecision(
                        shouldPoll = true,
                        targetRaion = userRaion,
                        matchedCommunity = userCommunity,
                        reason = "User in ${userCityName} (${userCommunity.name}) and ${userRaion.name} is alerted"
                    )
                }
            }
        }

        // Case B: User has a GPS location
        if (userLocation != null) {
            val userResolved = FrontlineCommunityCatalog.findCommunityForLocation(userLocation)
            if (userResolved != null) {
                val (userRaion, userCommunity) = userResolved
                val isAlerted = alertedFrontlineRaions.any { it.id == userRaion.id }
                if (isAlerted) {
                    return GateDecision(
                        shouldPoll = true,
                        targetRaion = userRaion,
                        matchedCommunity = userCommunity,
                        reason = "User GPS inside ${userCommunity.name} (${userRaion.name}) and raion is alerted"
                    )
                }
            }

            // Also check if user is within the general perimeter or polygon of the alerted raion
            for (alertedRaion in alertedFrontlineRaions) {
                if (FrontlineCommunityCatalog.isLocationInRaion(userLocation, alertedRaion)) {
                    return GateDecision(
                        shouldPoll = true,
                        targetRaion = alertedRaion,
                        matchedCommunity = null,
                        reason = "User GPS is within perimeter of alerted ${alertedRaion.name}"
                    )
                }
            }
        }

        // User is not near any alerted frontline raion (e.g. user is in Kyiv, Odesa, etc.)
        return GateDecision(
            shouldPoll = false,
            reason = "Frontline raion alerted, but user location is not in or near the affected area"
        )
    }
}
