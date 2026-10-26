package com.odesaplay.oko

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp

/**
 * Single source of truth for which drawable represents a threat type in which icon set.
 * Every pack is one [PackArt] entry: the classic set is the monochrome vector fallback, the
 * rest are the bundled direction-oriented rasters (see
 * `iconpacks/{photo,army,comic,russian}/drawable-nodpi/threat_*_*.webp`). Adding a pack is a
 * table entry, not a new `when` branch.
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

    /** Icon for [type] in [set], or the classic vector when that pack has no asset (UNKNOWN). */
    fun res(type: ThreatType, set: ThreatIconSet): Int =
        PACKS[set]?.art?.get(type) ?: classicRes(type)

    /**
     * Direction each icon's subject faces as baked into the artwork, in degrees clockwise from
     * north/up. Map markers rotate by `courseDeg - base` so the subject points along its course.
     */
    fun baseDeg(type: ThreatType, set: ThreatIconSet): Float =
        PACKS[set]?.facing?.get(type) ?: 0f

    /** Exhaust anchors for the aviation flyby; packs without raster art fall back to PHOTO. */
    fun aviationGeometry(set: ThreatIconSet): AviationGeometry {
        val pack = PACKS[set] ?: PACKS.getValue(ThreatIconSet.PHOTO)
        return AviationGeometry(
            facingDeg = pack.facing.getValue(ThreatType.AVIATION),
            exhausts = pack.exhausts
        )
    }

    /** True when [type] has a bundled raster in any set (UNKNOWN never does). */
    fun hasRaster(type: ThreatType): Boolean = PACKS.values.any { type in it.art }

    /** The seven threat types that have bundled rasters, in display order. */
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
     * Colour grade for [grade], or null for the untouched artwork. A grade is a row-major 4x5
     * matrix in the same 0..255 space for Compose and for the map's bitmap path, so the two
     * surfaces cannot drift. Grades are orthogonal to packs: any master can wear any of them.
     */
    fun gradeMatrix(grade: ThreatIconGrade): FloatArray? = when (grade) {
        ThreatIconGrade.NONE -> null
        ThreatIconGrade.CYAN -> CYAN_MATRIX
        ThreatIconGrade.IR -> IR_MATRIX
        ThreatIconGrade.VIOLET -> VIOLET_MATRIX
        ThreatIconGrade.STENCIL -> STENCIL_MATRIX
    }

    /** Cool steel: luminance through a cyan ramp, hot metal stays near-white. */
    private val CYAN_MATRIX = monoRamp(1.50f, 1.80f, 1.95f, -56f, -70f, -78f)

    /** White-hot sensor return, neutral. */
    private val IR_MATRIX = monoRamp(1.85f, 1.85f, 1.85f, -82f, -82f, -82f)

    /** Violet cast; no other UI element uses this hue, so the icons never read as a status. */
    private val VIOLET_MATRIX = monoRamp(1.80f, 1.50f, 2.05f, -75f, -62f, -88f)

    /** Two-tone crush: interiors flatten into a stark decal. Pale masters (comic) go solid. */
    private val STENCIL_MATRIX = monoRamp(2.60f, 2.60f, 2.60f, -140f, -140f, -140f)

    private fun monoRamp(
        gainR: Float, gainG: Float, gainB: Float,
        offR: Float, offG: Float, offB: Float
    ): FloatArray = floatArrayOf(
        0.299f * gainR, 0.587f * gainR, 0.114f * gainR, 0f, offR,
        0.299f * gainG, 0.587f * gainG, 0.114f * gainG, 0f, offG,
        0.299f * gainB, 0.587f * gainB, 0.114f * gainB, 0f, offB,
        0f, 0f, 0f, 1f, 0f
    )

    /** One pack's artwork: which drawable per type, its baked facing, its flyby exhausts. */
    private class PackArt(
        val art: Map<ThreatType, Int>,
        val facing: Map<ThreatType, Float>,
        val exhausts: List<Exhaust>
    )

    /** Army and comic share one facing table; only their artwork and exhausts differ. */
    private val DRAWN_FACING = mapOf(
        ThreatType.SHAHED to 0f,
        ThreatType.FPV_LOITERING to 180f,
        ThreatType.CRUISE_MISSILE to 90f,
        ThreatType.BALLISTIC to 45f,
        ThreatType.KAB to 240f,
        ThreatType.AVIATION to 265f,
        ThreatType.RECON to 232f
    )

    private val PACKS: Map<ThreatIconSet, PackArt> = mapOf(
        ThreatIconSet.PHOTO to PackArt(
            art = mapOf(
                ThreatType.SHAHED to R.drawable.threat_photo_shahed,
                ThreatType.FPV_LOITERING to R.drawable.threat_photo_fpv,
                ThreatType.CRUISE_MISSILE to R.drawable.threat_photo_cruise,
                ThreatType.BALLISTIC to R.drawable.threat_photo_ballistic,
                ThreatType.KAB to R.drawable.threat_photo_kab,
                ThreatType.AVIATION to R.drawable.threat_photo_aviation,
                ThreatType.RECON to R.drawable.threat_photo_recon
            ),
            facing = mapOf(
                ThreatType.SHAHED to 0f,
                ThreatType.FPV_LOITERING to 180f,
                ThreatType.CRUISE_MISSILE to 45f,
                ThreatType.BALLISTIC to 0f,
                ThreatType.KAB to 240f,
                ThreatType.AVIATION to 265f,
                ThreatType.RECON to 232f
            ),
            exhausts = listOf(
                Exhaust(0.299205f, -0.014911f),
                Exhaust(0.454274f, 0.024851f)
            )
        ),
        ThreatIconSet.ARMY to PackArt(
            art = mapOf(
                ThreatType.SHAHED to R.drawable.threat_army_shahed,
                ThreatType.FPV_LOITERING to R.drawable.threat_army_fpv,
                ThreatType.CRUISE_MISSILE to R.drawable.threat_army_cruise,
                ThreatType.BALLISTIC to R.drawable.threat_army_ballistic,
                ThreatType.KAB to R.drawable.threat_army_kab,
                ThreatType.AVIATION to R.drawable.threat_army_aviation,
                ThreatType.RECON to R.drawable.threat_army_recon
            ),
            facing = DRAWN_FACING,
            exhausts = listOf(
                Exhaust(0.354871f, -0.014911f),
                Exhaust(0.454274f, 0.024851f)
            )
        ),
        ThreatIconSet.COMIC to PackArt(
            art = mapOf(
                ThreatType.SHAHED to R.drawable.threat_comic_shahed,
                ThreatType.FPV_LOITERING to R.drawable.threat_comic_fpv,
                ThreatType.CRUISE_MISSILE to R.drawable.threat_comic_cruise,
                ThreatType.BALLISTIC to R.drawable.threat_comic_ballistic,
                ThreatType.KAB to R.drawable.threat_comic_kab,
                ThreatType.AVIATION to R.drawable.threat_comic_aviation,
                ThreatType.RECON to R.drawable.threat_comic_recon
            ),
            facing = DRAWN_FACING,
            exhausts = listOf(
                Exhaust(0.201717f, -0.066524f),
                Exhaust(0.379828f, 0.000000f)
            )
        ),
        ThreatIconSet.RUSSIAN to PackArt(
            art = mapOf(
                ThreatType.SHAHED to R.drawable.threat_russian_shahed,
                ThreatType.FPV_LOITERING to R.drawable.threat_russian_fpv,
                ThreatType.CRUISE_MISSILE to R.drawable.threat_russian_cruise,
                ThreatType.BALLISTIC to R.drawable.threat_russian_ballistic,
                ThreatType.KAB to R.drawable.threat_russian_kab,
                ThreatType.AVIATION to R.drawable.threat_russian_aviation,
                ThreatType.RECON to R.drawable.threat_russian_recon
            ),
            facing = DRAWN_FACING,
            exhausts = listOf(
                Exhaust(0.218816f, -0.051800f),
                Exhaust(0.402748f, 0.022199f)
            )
        )
    )
}

/**
 * Active colour grade, provided once near the app root. Callers that preview a grade for the
 * user pass it explicitly; everything else inherits.
 */
val LocalIconGrade = compositionLocalOf { ThreatIconGrade.NONE }

data class Exhaust(
    val anchorXFrac: Float,
    val anchorYFrac: Float,
    val angleBiasDeg: Float = 0f
)

data class AviationGeometry(
    val facingDeg: Float,
    val exhausts: List<Exhaust>
)

data class IconGeometry(
    val facingDeg: Float,
    val anchorXFrac: Float,
    val anchorYFrac: Float
)


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
 * Threat icon that letterboxes the raster packs inside their square slot (the vector set draws
 * as-is). The photo assets are wide/short or narrow/tall, so they must keep their aspect ratio
 * instead of stretching into the box like `Icon` would.
 */
@Composable
fun ThreatIcon(
    type: ThreatType,
    set: ThreatIconSet,
    size: Dp,
    tint: Color = Color.Unspecified,
    dimmed: Boolean = false,
    contentDescription: String? = null,
    grade: ThreatIconGrade? = null
) {
    if (type != ThreatType.UNKNOWN && !IconCatalog.hasRaster(type)) {
        Icon(
            painter = painterResource(id = IconCatalog.res(type, set)),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size).alpha(if (dimmed) 0.45f else 1f)
        )
    } else {
        val effectiveGrade = grade ?: LocalIconGrade.current
        val recipe = IconCatalog.gradeMatrix(effectiveGrade)
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = IconCatalog.res(type, set)),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                colorFilter = when {
                    tint != Color.Unspecified -> ColorFilter.tint(tint)
                    recipe != null -> ColorFilter.colorMatrix(ColorMatrix(recipe.copyOf()))
                    dimmed -> DimmedPhotoFilter
                    else -> null
                },
                modifier = Modifier.fillMaxSize()
                    .alpha(if (dimmed && recipe != null) 0.45f else 1f)
            )
        }
    }
}
