package com.presaince.oko

/** Ukraine (incl. Crimea) tight bounds — ~0.5° margin, used for UI clamping and map pan limits. */
const val UA_TIGHT_MIN_LAT = 43.9
const val UA_TIGHT_MAX_LAT = 52.7
const val UA_TIGHT_MIN_LON = 21.7
const val UA_TIGHT_MAX_LON = 40.6

/** Ukraine wide bounds — ~2° margin, used for tile coverage and bounds checks. */
const val UA_WIDE_MIN_LAT = 42.4
const val UA_WIDE_MAX_LAT = 54.4
const val UA_WIDE_MIN_LON = 20.1
const val UA_WIDE_MAX_LON = 42.2

// Relaxed camera target bounds providing breathing room so fast pans and flings decelerate smoothly without boundary snapping.
const val UA_PAN_MIN_LAT = 41.0
const val UA_PAN_MAX_LAT = 56.0
const val UA_PAN_MIN_LON = 18.0
const val UA_PAN_MAX_LON = 45.0

/** Odesa city centre — fallback camera target before the first GPS fix. */
const val ODESA_LAT = 46.4832
const val ODESA_LON = 30.7346

/** True when a fix is usable as an app focus: finite and inside the wide Ukraine bounds. */
fun isInsideUkraine(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() &&
        lat in UA_WIDE_MIN_LAT..UA_WIDE_MAX_LAT && lon in UA_WIDE_MIN_LON..UA_WIDE_MAX_LON
