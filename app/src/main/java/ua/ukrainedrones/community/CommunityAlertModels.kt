package ua.ukrainedrones.community

/**
 * Granular community (hromada) alert status parsed from raw feed.
 */
data class CommunityAlert(
    val name: String,
    val isActive: Boolean,
    val changedAt: String? = null,
    val regionId: Int? = null
)

/**
 * Definition of a specific territorial community (hromada)
 * that can receive hyper-local air raid sirens (e.g. artillery / FPV alarms).
 *
 * Language-agnostic: uses raw IDs, raw names, and matching stems.
 */
data class FrontlineCommunity(
    val id: String,
    val name: String,
    val stems: List<String>,
    val centerLat: Double,
    val centerLon: Double,
    val radiusKm: Double = 25.0,
    val polygon: ScaledRing? = null
)

/**
 * Definition of a frontline district (raion) that supports sub-district community alerts.
 * Language-agnostic: uses raw IDs, raw names, and matching stems.
 */
data class FrontlineRaion(
    val id: String,
    val name: String,
    val stems: List<String>,
    val centerLat: Double,
    val centerLon: Double,
    val radiusKm: Double = 50.0,
    val communities: List<FrontlineCommunity>,
    val polygon: ScaledRing? = null
)

/**
 * Evaluation output of the proximity & alert gate.
 */
data class GateDecision(
    val shouldPoll: Boolean,
    val targetRaion: FrontlineRaion? = null,
    val matchedCommunity: FrontlineCommunity? = null,
    val reason: String
)

/**
 * High-level enrichment result returned by [CommunityAlertEnricher].
 */
data class CommunityEnrichmentResult(
    /** True if the user is in or near a frontline community alert zone. */
    val isFrontlineCommunityArea: Boolean,
    /**
     * Specific alert status for the user's community:
     * - `true`: Active siren specifically for this community.
     * - `false`: Community is safe (even if the broader raion is alerted).
     * - `null`: User is not in a community-alert area (use standard raion/oblast siren).
     */
    val specificCommunityAlert: Boolean?,
    /** Raw name of the matched community, if applicable. */
    val communityName: String? = null,
    /** Raw name of the matched frontline raion, if applicable. */
    val raionName: String? = null,
    /** All parsed community records from the fetch, if any. */
    val fetchedCommunities: List<CommunityAlert> = emptyList()
)
