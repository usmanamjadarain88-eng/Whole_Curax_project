package com.curax.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Encode/decode user profile photos (data URLs) for sidebar + admin linked-user list. */
object UserProfileImageCodec {

    private const val MAX_EDGE = 512
    private const val JPEG_QUALITY = 85

    fun loadAndDownscale(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val ow = bounds.outWidth
        val oh = bounds.outHeight
        if (ow <= 0 || oh <= 0) return null
        var sample = 1
        while (ow / sample > MAX_EDGE || oh / sample > MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    fun toJpegDataUrl(source: Bitmap): String {
        val bmp = scaleDownIfNeeded(source)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        if (bmp !== source) bmp.recycle()
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    private fun scaleDownIfNeeded(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        val maxSide = max(w, h)
        if (maxSide <= MAX_EDGE) return b
        val scale = MAX_EDGE.toFloat() / maxSide.toFloat()
        val nw = (w * scale).roundToInt().coerceAtLeast(1)
        val nh = (h * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val m = Matrix().apply { postScale(scale, scale) }
        canvas.drawBitmap(b, m, null)
        if (!b.isRecycled) b.recycle()
        return out
    }

    fun bitmapFromDataUrl(dataUrl: String): Bitmap? {
        val s = dataUrl.trim()
        if (!s.startsWith("data:image")) return null
        val comma = s.indexOf(',')
        if (comma < 0) return null
        val b64 = s.substring(comma + 1).trim()
        if (b64.isEmpty()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
}
