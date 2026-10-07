package com.odesaplay.oko.beta

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

enum class FeedbackCategory { BUG, IMPROVEMENT, QUESTION }

/** Downscaled map shot, ready to embed in the bundle. */
data class FeedbackShot(val width: Int, val height: Int, val jpegBase64: String)

/**
 * Pure feedback helpers: shot encoding and bundle injection. No Context, no network, so the
 * sheet and the uploader share them without a seam that can diverge.
 */
object FeedbackPayload {

    const val MAX_DIM = 720

    fun encode(src: Bitmap, maxDim: Int = MAX_DIM, quality: Int = 80): FeedbackShot {
        val scale = (maxDim / maxOf(src.width, src.height).toFloat()).coerceAtMost(1f)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bmp !== src) bmp.recycle()
        return FeedbackShot(w, h, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    /**
     * Injects the `feedback` block (and the `shot` when opted in). Strips the heavy `logs`
     * history when the tester opts out of full logs — identity blocks (app/device/prefs)
     * still ship, so the report stays attributable to a build without the weight.
     */
    fun attach(
        bundle: JSONObject,
        category: FeedbackCategory,
        message: String,
        shot: FeedbackShot?,
        includeLogs: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): JSONObject {
        val out = JSONObject(bundle.toString())
        out.put(
            "feedback",
            JSONObject()
                .put("category", category.name.lowercase())
                .put("message", message.trim())
                .put("atMillis", nowMillis)
        )
        if (shot != null) {
            out.put(
                "shot",
                JSONObject()
                    .put("width", shot.width)
                    .put("height", shot.height)
                    .put("jpegBase64", shot.jpegBase64)
            )
        }
        if (!includeLogs) out.remove("logs")
        return out
    }
}
