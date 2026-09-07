package ua.ukrainedrones.community

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Self-contained catalog and spatial query engine for frontline communities (hromadas)
 * and districts (raions) that receive hyper-local air raid sirens.
 *
 * Designed to be 100% portable with zero third-party library dependencies.
 *
 * Characteristics:
 * - Language-agnostic: matches on raw names and stems (transliteration handled externally).
 * - Normalized coordinates: strictly [LAT, LON] order everywhere.
 * - Scaled integer polygons: sub-70m boundary precision at scale 1000.
 */
object FrontlineCommunityCatalog {

    /**
     * Active frontline districts with boundary polygons and sub-district communities.
     */
    val FRONTLINE_RAIONS: List<FrontlineRaion> = listOf(
        // 1. Nikopol Raion - Dnipropetrovsk Oblast (Artillery & FPV drone line across Dnipro river)
        FrontlineRaion(
            id = "nikopolskyi",
            name = "Нікопольський район",
            stems = listOf("нікопол", "nikopol"),
            centerLat = 47.57,
            centerLon = 34.40,
            radiusKm = 45.0,
            polygon = ScaledRing(
                intArrayOf(
                    47659, 34044, 47666, 33979, 47693, 33928, 47719, 33927, 47722, 33957,
                    47743, 33960, 47751, 34021, 47797, 34069, 47807, 34043, 47874, 34025,
                    47872, 34003, 47928, 34011, 47924, 34063, 47948, 34093, 47941, 34145,
                    47968, 34168, 47978, 34303, 47929, 34318, 47919, 34343, 47929, 34410,
                    47939, 34373, 47954, 34441, 47927, 34446, 47928, 34495, 47961, 34590,
                    47979, 34586, 47982, 34610, 48013, 34602, 48027, 34731, 47985, 34744,
                    48015, 34858, 47982, 34870, 47986, 34905, 47933, 34919, 47942, 34876,
                    47916, 34802, 47864, 34817, 47881, 34861, 47851, 34871, 47853, 34897,
                    47830, 34903, 47818, 34879, 47760, 34894, 47770, 34957, 47712, 34956,
                    47712, 34930, 47677, 34938, 47674, 34906, 47601, 34942, 47580, 34928,
                    47563, 34959, 47556, 34923, 47548, 34965, 47515, 34862, 47562, 34572,
                    47475, 34115, 47552, 34109, 47570, 34045, 47596, 34088, 47614, 34051,
                    47635, 34069, 47649, 34024
                )
            ),
            communities = listOf(
                FrontlineCommunity(
                    id = "nikopolska",
                    name = "Нікопольська територіальна громада",
                    stems = listOf("нікопол", "nikopol"),
                    centerLat = 47.57,
                    centerLon = 34.40,
                    radiusKm = 18.0
                ),
                FrontlineCommunity(
                    id = "marhanetska",
                    name = "Марганецька територіальна громада",
                    stems = listOf("марганец", "marhanets"),
                    centerLat = 47.66,
                    centerLon = 34.61,
                    radiusKm = 16.0
                ),
                FrontlineCommunity(
                    id = "pokrovska",
                    name = "Покровська територіальна громада",
                    stems = listOf("покров", "pokrov"),
                    centerLat = 47.65,
                    centerLon = 34.08,
                    radiusKm = 18.0
                ),
                FrontlineCommunity(
                    id = "chervonohryhorivska",
                    name = "Червоногригорівська територіальна громада",
                    stems = listOf("червоногригор", "chervonohryhor"),
                    centerLat = 47.62,
                    centerLon = 34.54,
                    radiusKm = 14.0
                )
            )
        ),

        // 2. Kharkiv Raion - Kharkiv Oblast (Border & artillery contact line)
        FrontlineRaion(
            id = "kharkivskyi",
            name = "Харківський район",
            stems = listOf("харків", "kharkiv"),
            centerLat = 49.99,
            centerLon = 36.23,
            radiusKm = 50.0,
            polygon = ScaledRing(
                intArrayOf(
                    49616, 36066, 49633, 35966, 49582, 35866, 49648, 35832, 49617, 35769,
                    49627, 35743, 49644, 35746, 49657, 35710, 49694, 35747, 49697, 35708,
                    49724, 35676, 49744, 35679, 49750, 35655, 49782, 35685, 49775, 35708,
                    49824, 35702, 49837, 35759, 49818, 35770, 49813, 35821, 49833, 35839,
                    49836, 35822, 49859, 35824, 49875, 35873, 49916, 35873, 49937, 35819,
                    49926, 35803, 49941, 35814, 49949, 35795, 49944, 35755, 49971, 35814,
                    49997, 35810, 49996, 35835, 50017, 35780, 50064, 35845, 50119, 35819,
                    50117, 35842, 50078, 35868, 50093, 35944, 50111, 35941, 50108, 35995,
                    50136, 35987, 50133, 36013, 50181, 36036, 50180, 36078, 50201, 36072,
                    50212, 36037, 50225, 36093, 50244, 36086, 50241, 36121, 50288, 36176,
                    50326, 36151, 50346, 36172, 50365, 36105, 50406, 36185, 50336, 36284,
                    50324, 36274, 50292, 36296, 50287, 36364, 50331, 36433, 50311, 36440,
                    50312, 36488, 50286, 36524, 50286, 36585, 50271, 36591, 50251, 36561,
                    50196, 36705, 50169, 36673, 50159, 36702, 50148, 36669, 50127, 36691,
                    50103, 36671, 50098, 36568, 50081, 36549, 50036, 36567, 50006, 36646,
                    49991, 36625, 49967, 36647, 49921, 36602, 49910, 36539, 49886, 36534,
                    49869, 36433, 49814, 36409, 49797, 36422, 49792, 36334, 49819, 36321,
                    49832, 36281, 49813, 36231, 49826, 36170, 49806, 36129, 49786, 36120,
                    49778, 36160, 49755, 36133, 49770, 36084, 49750, 35975, 49763, 35968,
                    49737, 35986, 49723, 36035, 49712, 36000, 49702, 36011, 49694, 36074,
                    49672, 36073, 49674, 36039
                )
            ),
            communities = listOf(
                FrontlineCommunity(
                    id = "kharkivska",
                    name = "Харківська територіальна громада",
                    stems = listOf("харків", "kharkiv"),
                    centerLat = 49.99,
                    centerLon = 36.23,
                    radiusKm = 24.0
                ),
                FrontlineCommunity(
                    id = "lypetska",
                    name = "Липецька територіальна громада",
                    stems = listOf("липец", "lypets"),
                    centerLat = 50.21,
                    centerLon = 36.42,
                    radiusKm = 20.0
                ),
                FrontlineCommunity(
                    id = "vovchanska",
                    name = "Вовчанська територіальна громада",
                    stems = listOf("вовчан", "vovchan"),
                    centerLat = 50.29,
                    centerLon = 36.94,
                    radiusKm = 22.0
                )
            )
        ),

        // 3. Zaporizhzhia Raion - Zaporizhzhia Oblast
        FrontlineRaion(
            id = "zaporizkyi",
            name = "Запорізький район",
            stems = listOf("запоріз", "zaporiz"),
            centerLat = 47.84,
            centerLon = 35.14,
            radiusKm = 45.0,
            polygon = ScaledRing(
                intArrayOf(
                    47868, 34826, 47881, 34861, 47851, 34871, 47853, 34897, 47830, 34903,
                    47818, 34879, 47760, 34894, 47770, 34957, 47712, 34956, 47712, 34930,
                    47677, 34938, 47674, 34906, 47601, 34942, 47580, 34928, 47563, 34959,
                    47556, 34923, 47561, 34961, 47530, 34944, 47505, 35024, 47537, 35140,
                    47615, 35190, 47609, 35282, 47644, 35279, 47678, 35336, 47659, 35347,
                    47639, 35428, 47618, 35430, 47619, 35469, 47606, 35458, 47607, 35549,
                    47576, 35567, 47622, 35715, 47597, 35757, 47672, 35798, 47802, 35772,
                    47805, 35791, 47829, 35787, 47833, 35846, 47769, 35842, 47756, 35954,
                    47721, 35974, 47725, 36012, 47766, 36007, 47775, 36184, 47794, 36180,
                    47807, 36207, 47838, 36189, 47859, 36197, 47843, 36102, 47868, 36058,
                    47883, 36081, 47861, 36087, 47865, 36119, 47890, 36130, 47942, 36109,
                    47930, 36085, 47965, 36074, 47966, 36048, 47972, 36071, 48019, 36034,
                    48025, 36066, 48059, 36052, 48043, 35975, 48058, 35989, 48073, 35969,
                    48085, 35985, 48095, 35967, 48066, 35817, 48100, 35806, 48096, 35743,
                    48140, 35703, 48075, 35524, 48095, 35519, 48100, 35421, 48144, 35295,
                    48132, 35102, 48089, 34990, 48091, 34915, 48131, 34916, 48133, 34855,
                    48054, 34876, 48051, 34839, 48014, 34847, 47982, 34870, 47986, 34905,
                    47933, 34919, 47942, 34876, 47916, 34802
                )
            ),
            communities = listOf(
                FrontlineCommunity(
                    id = "zaporizka",
                    name = "Запорізька територіальна громада",
                    stems = listOf("запоріз", "zaporiz"),
                    centerLat = 47.84,
                    centerLon = 35.14,
                    radiusKm = 25.0
                )
            )
        ),

        // 4. Svitlovodsk Area - Oleksandriia Raion, Kirovohrad Oblast
        FrontlineRaion(
            id = "oleksandriiskyi",
            name = "Олександрійський район",
            stems = listOf("олександрій", "oleksandr"),
            centerLat = 49.05,
            centerLon = 33.20,
            radiusKm = 40.0,
            polygon = ScaledRing(
                intArrayOf(
                    48501, 32784, 48453, 32784, 48432, 32844, 48474, 32912, 48482, 33017,
                    48505, 33013, 48508, 33046, 48462, 33051, 48464, 33091, 48436, 33106,
                    48433, 33078, 48371, 33071, 48371, 33042, 48318, 33053, 48323, 33106,
                    48276, 33121, 48278, 33137, 48232, 33154, 48152, 33146, 48145, 33188,
                    48158, 33185, 48173, 33243, 48152, 33247, 48154, 33272, 48103, 33277,
                    48102, 33293, 48124, 33317, 48150, 33291, 48170, 33431, 48189, 33430,
                    48202, 33496, 48214, 33485, 48232, 33501, 48219, 33511, 48235, 33546,
                    48278, 33513, 48329, 33518, 48329, 33474, 48349, 33478, 48358, 33460,
                    48388, 33468, 48394, 33497, 48534, 33469, 48558, 33482, 48576, 33584,
                    48560, 33590, 48567, 33623, 48599, 33609, 48586, 33661, 48625, 33762,
                    48657, 33757, 48663, 33802, 48688, 33805, 48681, 33750, 48723, 33682,
                    48728, 33619, 48755, 33585, 48797, 33583, 48790, 33638, 48815, 33674,
                    48814, 33715, 48789, 33725, 48803, 33811, 48776, 33819, 48771, 33851,
                    48803, 33848, 48902, 33894, 48938, 33815, 48945, 33699, 48978, 33665,
                    48974, 33637, 48911, 33575, 48931, 33537, 48915, 33474, 48952, 33476,
                    48960, 33404, 48928, 33401, 48949, 33320, 49011, 33298, 49025, 33328,
                    49062, 33320, 49092, 33262, 49118, 33304, 49167, 33240, 49086, 33249,
                    49074, 33200, 49096, 33187, 49099, 33156, 49130, 33158, 49185, 33102,
                    49202, 32995, 49240, 32983, 49230, 32929, 49245, 32859, 49167, 32757,
                    49142, 32777, 49127, 32890, 49095, 32896, 49075, 32814, 49075, 32829,
                    49028, 32843, 49011, 32828, 48992, 32874, 48965, 32733, 48936, 32753,
                    48931, 32742, 48924, 32774, 48891, 32778, 48882, 32872, 48815, 32886,
                    48801, 32784, 48725, 32800, 48720, 32778, 48689, 32808, 48687, 32764,
                    48660, 32755, 48621, 32801, 48637, 32858, 48616, 32857, 48599, 32774,
                    48558, 32756, 48544, 32776
                )
            ),
            communities = listOf(
                FrontlineCommunity(
                    id = "svitlovodska",
                    name = "Світловодська територіальна громада",
                    stems = listOf("світловод", "svitlovod"),
                    centerLat = 49.05,
                    centerLon = 33.20,
                    radiusKm = 18.0
                )
            )
        )
    )

    /**
     * Finds a frontline raion definition matching an incoming raw alert name or stem.
     */
    fun findRaionForAlert(rawAlertKeyOrName: String): FrontlineRaion? {
        val needle = rawAlertKeyOrName.trim().lowercase()
        if (needle.isEmpty()) return null
        return FRONTLINE_RAIONS.firstOrNull { raion ->
            raion.id.equals(needle, ignoreCase = true) ||
                raion.name.lowercase().contains(needle) ||
                needle.contains(raion.name.lowercase()) ||
                raion.stems.any { stem -> needle.contains(stem) }
        }
    }

    /**
     * Resolves the matching frontline community for a user's GPS coordinate.
     * Uses polygon point-in-polygon when available, falling back to radial proximity.
     */
    fun findCommunityForLocation(point: LatLon): Pair<FrontlineRaion, FrontlineCommunity>? {
        for (raion in FRONTLINE_RAIONS) {
            val insideRaion = raion.polygon?.contains(point.lat, point.lon)
                ?: (haversineDistanceKm(point.lat, point.lon, raion.centerLat, raion.centerLon) <= raion.radiusKm)

            if (insideRaion) {
                var closestCommunity: FrontlineCommunity? = null
                var minDistance = Double.MAX_VALUE
                for (comm in raion.communities) {
                    val insideComm = comm.polygon?.contains(point.lat, point.lon)
                        ?: (haversineDistanceKm(point.lat, point.lon, comm.centerLat, comm.centerLon) <= comm.radiusKm)

                    if (insideComm) {
                        val dist = haversineDistanceKm(point.lat, point.lon, comm.centerLat, comm.centerLon)
                        if (dist < minDistance) {
                            minDistance = dist
                            closestCommunity = comm
                        }
                    }
                }
                if (closestCommunity != null) {
                    return Pair(raion, closestCommunity)
                }
            }
        }
        return null
    }

    /**
     * Resolves the matching frontline community for a user's raw city string.
     */
    fun findCommunityForCity(rawCityName: String): Pair<FrontlineRaion, FrontlineCommunity>? {
        val needle = rawCityName.trim().lowercase()
        if (needle.isEmpty()) return null
        for (raion in FRONTLINE_RAIONS) {
            for (comm in raion.communities) {
                if (comm.id.equals(needle, ignoreCase = true) ||
                    comm.name.lowercase().contains(needle) ||
                    comm.stems.any { stem -> needle.contains(stem) }
                ) {
                    return Pair(raion, comm)
                }
            }
        }
        return null
    }

    /**
     * Checks whether a point is in a frontline raion (using polygon test if present, else radius).
     */
    fun isLocationInRaion(point: LatLon, raion: FrontlineRaion): Boolean {
        return raion.polygon?.contains(point.lat, point.lon)
            ?: (haversineDistanceKm(point.lat, point.lon, raion.centerLat, raion.centerLon) <= raion.radiusKm)
    }

    /**
     * Great-circle Haversine distance in kilometers.
     */
    fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
