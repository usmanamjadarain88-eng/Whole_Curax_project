package com.curax.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/** Decode small avatars from `data:image/...;base64,...` URLs returned by the API. */
object ProfilePictureDataUrl {

    fun decodeBitmap(dataUrl: String?, maxSide: Int = 256): Bitmap? {
        val raw = dataUrl?.trim().orEmpty()
        if (raw.isEmpty() || !raw.startsWith("data:image", ignoreCase = true)) return null
        val comma = raw.indexOf(',')
        if (comma <= 0 || comma >= raw.length - 1) return null
        val b64 = raw.substring(comma + 1)
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }
}
