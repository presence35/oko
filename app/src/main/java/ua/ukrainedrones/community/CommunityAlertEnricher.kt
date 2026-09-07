package ua.ukrainedrones.community

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-level coordinator that bridges high-frequency WebSocket alert frames
 * with on-demand, proximity-gated REST community alert enrichment.
 *
 * Fully language-agnostic: operates on raw names, IDs, and stems.
 *
 * Workflow:
 * 1. When an alert frame arrives from WebSocket, call [processAlertsUpdate].
 * 2. If the user is nowhere near an alerting frontline raion, the gate immediately
 *    skips any network call (0ms latency, zero battery/data cost).
 * 3. If the user is inside an alerted frontline raion (e.g. Nikopol, Kharkiv, Zaporizhzhia),
 *    a 1-shot REST poll to Ubilling is performed (debounced to protect against 429).
 * 4. Produces a [CommunityEnrichmentResult] that indicates whether the user's exact
 *    community (hromada) is ringing or safe.
 */
class CommunityAlertEnricher(
    private val client: UbillingCommunityAlertClient = UbillingCommunityAlertClient()
) {
    private val _latestResult = MutableStateFlow(
        CommunityEnrichmentResult(
            isFrontlineCommunityArea = false,
            specificCommunityAlert = null
        )
    )
    val latestResult: StateFlow<CommunityEnrichmentResult> = _latestResult.asStateFlow()

    /**
     * Evaluates incoming alert keys against the user's focus point and performs
     * an on-demand REST poll only when the proximity gate triggers.
     *
     * @param activeAlertKeys Collection of raw alert keys or names.
     * @param userLocation User's GPS coordinate, if available.
     * @param userCityName User's raw city string, if available.
     * @return [CommunityEnrichmentResult] with granular community alert state.
     */
    suspend fun processAlertsUpdate(
        activeAlertKeys: Collection<String>,
        userLocation: LatLon?,
        userCityName: String?
    ): CommunityEnrichmentResult {
        val gate = CommunityAlertGate.evaluate(
            activeAlertKeysOrNames = activeAlertKeys,
            userLocation = userLocation,
            userCityName = userCityName
        )

        // Case 1: Proximity gate triggered -> User is in an alerted frontline raion!
        if (gate.shouldPoll && gate.targetRaion != null) {
            val fetchResult = client.fetchCommunities()
            val communities = fetchResult.getOrDefault(emptyList())

            val userCommunity = gate.matchedCommunity
                ?: (userLocation?.let { FrontlineCommunityCatalog.findCommunityForLocation(it)?.second }
                    ?: userCityName?.let { FrontlineCommunityCatalog.findCommunityForCity(it)?.second })

            val specificStatus = if (userCommunity != null) {
                // Find matching record in fetched communities by stem or name
                val matchedRecord = communities.firstOrNull { alert ->
                    val n = alert.name.lowercase()
                    userCommunity.stems.any { stem -> n.contains(stem) } ||
                        alert.name.equals(userCommunity.name, ignoreCase = true)
                }
                matchedRecord?.isActive ?: false
            } else {
                // If user is inside raion but community cannot be resolved, default to raion-level status (true)
                true
            }

            val result = CommunityEnrichmentResult(
                isFrontlineCommunityArea = true,
                specificCommunityAlert = specificStatus,
                communityName = userCommunity?.name,
                raionName = gate.targetRaion.name,
                fetchedCommunities = communities
            )
            _latestResult.value = result
            return result
        }

        // Case 2: Proximity gate did NOT trigger
        // Check if the user is in a frontline community that is currently quiet (no raion alert)
        val userFrontlinePair = (userCityName?.let { FrontlineCommunityCatalog.findCommunityForCity(it) }
            ?: userLocation?.let { FrontlineCommunityCatalog.findCommunityForLocation(it) })

        val result = if (userFrontlinePair != null) {
            // User is in a frontline community, but the raion is not alerting -> safe
            CommunityEnrichmentResult(
                isFrontlineCommunityArea = true,
                specificCommunityAlert = false,
                communityName = userFrontlinePair.second.name,
                raionName = userFrontlinePair.first.name
            )
        } else {
            // User is in a standard oblast/raion (Kyiv, Odesa, Lviv, etc.)
            CommunityEnrichmentResult(
                isFrontlineCommunityArea = false,
                specificCommunityAlert = null
            )
        }

        _latestResult.value = result
        return result
    }

    /**
     * Resolves the effective alert status for the focus user, combining standard
     * raion/oblast matching with hyper-local community status if available.
     *
     * @param isStandardAlertActive True if standard raion/oblast alert is active for the city.
     * @param enrichment Result from [processAlertsUpdate].
     * @return Effective alert status (true = alert active, false = quiet).
     */
    fun resolveEffectiveAlert(
        isStandardAlertActive: Boolean,
        enrichment: CommunityEnrichmentResult
    ): Boolean {
        // If the user is in a frontline community area and we have specific status:
        if (enrichment.isFrontlineCommunityArea && enrichment.specificCommunityAlert != null) {
            return enrichment.specificCommunityAlert
        }
        // Otherwise, fall back to standard raion/oblast alert behavior
        return isStandardAlertActive
    }
}
