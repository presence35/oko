package ua.ukrainedrones

/** Ukraine (incl. Crimea) tight bounds — ~0.5° margin, used for UI clamping and map pan limits. */
const val UA_TIGHT_MIN_LAT = 43.9
const val UA_TIGHT_MAX_LAT = 52.7
const val UA_TIGHT_MIN_LON = 21.7
const val UA_TIGHT_MAX_LON = 40.6

/** Ukraine wide bounds — ~2° margin, used for tile coverage so bordering areas get a base map. */
const val UA_WIDE_MIN_LAT = 42.4
const val UA_WIDE_MAX_LAT = 54.4
const val UA_WIDE_MIN_LON = 20.1
const val UA_WIDE_MAX_LON = 42.2

/** Odesa city centre — fallback camera target before the first GPS fix. */
const val ODESA_LAT = 46.4832
const val ODESA_LON = 30.7346
