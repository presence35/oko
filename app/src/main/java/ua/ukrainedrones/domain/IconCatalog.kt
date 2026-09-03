package ua.ukrainedrones

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

/**
 * Single source of truth for which drawable represents a threat type in which icon set.
 * The classic set is the monochrome vector set; the photo/army/comic sets are the bundled
 * direction-oriented rasters (see `iconpacks/{photo,army,comic}/drawable-nodpi/threat_*_*.png`).
 */
object IconCatalog {

    /** Classic vector icon for [type]. */
    fun classicRes(type: ThreatType): Int = when (type) {
        ThreatType.SHAHED -> R.drawable.shahed
        ThreatType.FPV_LOITERING -> R.drawable.ic_threat_fpv
        ThreatType.CRUISE_MISSILE -> R.drawable.ic_threat_cruise
        ThreatType.BALLISTIC -> R.drawable.ic_threat_ballistic
        ThreatType.KAB -> R.drawable.ic_threat_kab
        ThreatType.AVIATION -> R.drawable.ic_threat_aviation
        ThreatType.RECON -> R.drawable.ic_threat_recon
        ThreatType.UNKNOWN -> R.drawable.ic_threat_unknown
    }

    /** Photo icon for [type], or the classic vector when no photo is bundled (UNKNOWN). */
    fun res(type: ThreatType, set: ThreatIconSet): Int = when (set) {
    ThreatIconSet.PHOTO -> photoRes(type) ?: classicRes(type)
        ThreatIconSet.ARMY -> armyRes(type) ?: classicRes(type)
        ThreatIconSet.COMIC -> comicRes(type) ?: classicRes(type)
        ThreatIconSet.RUSSIAN -> russianRes(type) ?: classicRes(type)
    }

    /** Army icon for [type], or null when the set has no asset (UNKNOWN). */
    internal fun armyRes(type: ThreatType): Int? = when (type) {
        ThreatType.SHAHED -> R.drawable.threat_army_shahed
        ThreatType.FPV_LOITERING -> R.drawable.threat_army_fpv
        ThreatType.CRUISE_MISSILE -> R.drawable.threat_army_cruise
        ThreatType.BALLISTIC -> R.drawable.threat_army_ballistic
        ThreatType.KAB -> R.drawable.threat_army_kab
        ThreatType.AVIATION -> R.drawable.threat_army_aviation
        ThreatType.RECON -> R.drawable.threat_army_recon
        ThreatType.UNKNOWN -> null
    }

    internal fun photoRes(type: ThreatType): Int? = when (type) {
        ThreatType.SHAHED -> R.drawable.threat_photo_shahed
        ThreatType.FPV_LOITERING -> R.drawable.threat_photo_fpv
        ThreatType.CRUISE_MISSILE -> R.drawable.threat_photo_cruise
        ThreatType.BALLISTIC -> R.drawable.threat_photo_ballistic
        ThreatType.KAB -> R.drawable.threat_photo_kab
        ThreatType.AVIATION -> R.drawable.threat_photo_aviation
        ThreatType.RECON -> R.drawable.threat_photo_recon
        ThreatType.UNKNOWN -> null
    }

    /** Russian icon for [type], or null when the set has no asset (UNKNOWN). */
    internal fun russianRes(type: ThreatType): Int? = when (type) {
        ThreatType.SHAHED -> R.drawable.threat_russian_shahed
        ThreatType.FPV_LOITERING -> R.drawable.threat_russian_fpv
        ThreatType.CRUISE_MISSILE -> R.drawable.threat_russian_cruise
        ThreatType.BALLISTIC -> R.drawable.threat_russian_ballistic
        ThreatType.KAB -> R.drawable.threat_russian_kab
        ThreatType.AVIATION -> R.drawable.threat_russian_aviation
        ThreatType.RECON -> R.drawable.threat_russian_recon
        ThreatType.UNKNOWN -> null
    }

    /** Comic icon for [type], or null when the set has no asset (UNKNOWN). */
    internal fun comicRes(type: ThreatType): Int? = when (type) {
        ThreatType.SHAHED -> R.drawable.threat_comic_shahed
        ThreatType.FPV_LOITERING -> R.drawable.threat_comic_fpv
        ThreatType.CRUISE_MISSILE -> R.drawable.threat_comic_cruise
        ThreatType.BALLISTIC -> R.drawable.threat_comic_ballistic
        ThreatType.KAB -> R.drawable.threat_comic_kab
        ThreatType.AVIATION -> R.drawable.threat_comic_aviation
        ThreatType.RECON -> R.drawable.threat_comic_recon
        ThreatType.UNKNOWN -> null
    }

    /** The seven threat types that have bundled photos, in display order. */
    fun photoTypes(): List<ThreatType> = listOf(
        ThreatType.SHAHED,
        ThreatType.FPV_LOITERING,
        ThreatType.CRUISE_MISSILE,
        ThreatType.BALLISTIC,
        ThreatType.KAB,
        ThreatType.AVIATION,
        ThreatType.RECON
    )

    /**
     * Direction each photo's subject faces as baked into the image, in degrees clockwise
     * from north/up (the filename suffix, e.g. "top_right" = 45°). Map markers rotate the
     * icon by `courseDeg - base` so the subject points along its true course.
     */
    fun photoBaseDeg(type: ThreatType): Float = when (type) {
        ThreatType.BALLISTIC -> 0f       // vertical (up), after the 90°-right photo rotation
        ThreatType.CRUISE_MISSILE -> 45f  // _top_right (PNGs re-faced)
        ThreatType.FPV_LOITERING -> 180f  // _bottom_middle
        ThreatType.KAB -> 240f            // _bottom_left
        ThreatType.AVIATION -> 265f       // _left
        ThreatType.RECON -> 247f          // _bottom_left
        ThreatType.SHAHED -> 0f           // _top_middle
        ThreatType.UNKNOWN -> 0f
    }

    /**
     * Direction each army icon's subject faces as baked into the image, in degrees clockwise
     * from north/up (the source-file name, e.g. "top_right" = 45°).
     */
    fun armyBaseDeg(type: ThreatType): Float = when (type) {
        ThreatType.BALLISTIC -> 45f       // _top_right
        ThreatType.CRUISE_MISSILE -> 90f  // _right
        ThreatType.FPV_LOITERING -> 180f  // _bottom
        ThreatType.KAB -> 240f            // _bottom_left
        ThreatType.AVIATION -> 265f       // _left
        ThreatType.RECON -> 247f          // _bottom_left
        ThreatType.SHAHED -> 0f           // _top
        ThreatType.UNKNOWN -> 0f
    }

    /**
     * Direction each comic icon's subject faces as baked into the image, in degrees clockwise
     * from north/up (the source-file name, e.g. "top_right" = 45°).
     */
    fun comicBaseDeg(type: ThreatType): Float = armyBaseDeg(type)

    /** Direction each russian icon's subject faces as baked into the image (same orientation as army). */
    fun russianBaseDeg(type: ThreatType): Float = armyBaseDeg(type)

    /** Baked-in facing direction for the active set (0° for the rotation-free classic set). */
    fun baseDeg(type: ThreatType, set: ThreatIconSet): Float = when (set) {
        ThreatIconSet.PHOTO -> photoBaseDeg(type)
        ThreatIconSet.ARMY -> armyBaseDeg(type)
        ThreatIconSet.COMIC -> comicBaseDeg(type)
        ThreatIconSet.RUSSIAN -> russianBaseDeg(type)
    }

    fun aviationGeometry(set: ThreatIconSet): AviationGeometry = when (set) {
        ThreatIconSet.RUSSIAN -> AviationGeometry(
            facingDeg = baseDeg(ThreatType.AVIATION, set),
            exhausts = listOf(
                Exhaust(0.218816f, -0.051800f),
                Exhaust(0.402748f, 0.022199f)
            )
        )
        ThreatIconSet.PHOTO -> AviationGeometry(
            facingDeg = baseDeg(ThreatType.AVIATION, set),
            exhausts = listOf(
                Exhaust(0.299205f, -0.014911f),
                Exhaust(0.454274f, 0.024851f)
            )
        )
        ThreatIconSet.ARMY -> AviationGeometry(
            facingDeg = baseDeg(ThreatType.AVIATION, set),
            exhausts = listOf(
                Exhaust(0.354871f, -0.014911f),
                Exhaust(0.454274f, 0.024851f)
            )
        )
        ThreatIconSet.COMIC -> AviationGeometry(
            facingDeg = baseDeg(ThreatType.AVIATION, set),
            exhausts = listOf(
                Exhaust(0.201717f, -0.066524f),
                Exhaust(0.379828f, 0.000000f)
            )
        )
    }
}

/** Greys the photo/army/comic image itself (keeps its shape) instead of flat-tinting it. */
private val DimmedPhotoFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.11f, 0.36f, 0.04f, 0f, 0f,
            0.11f, 0.36f, 0.04f, 0f, 0f,
            0.11f, 0.36f, 0.04f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

/**
 * Threat icon that letterboxes the photo set inside its square slot (the vector set draws
 * as-is). The photo assets are wide/short or narrow/tall, so they must keep their aspect
 * ratio instead of stretching into the box like `Icon` would.
 */
@Composable
fun ThreatIcon(
    type: ThreatType,
    set: ThreatIconSet,
    size: Dp,
    tint: Color = Color.Unspecified,
    dimmed: Boolean = false,
    contentDescription: String? = null
) {
    if (type != ThreatType.UNKNOWN && (IconCatalog.photoRes(type) == null && IconCatalog.armyRes(type) == null && IconCatalog.comicRes(type) == null && IconCatalog.russianRes(type) == null)) {
        Icon(
            painter = painterResource(id = IconCatalog.res(type, set)),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size)
        )
    } else {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = IconCatalog.res(type, set)),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                colorFilter = when {
                    dimmed -> DimmedPhotoFilter
                    tint == Color.Unspecified -> null
                    else -> ColorFilter.tint(tint)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}