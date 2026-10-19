package com.dongzhongcen.listenstat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Wallpaper picked with the system Photo Picker; a downscaled copy lives in the app's private
 * files dir. Blur: RenderEffect on Android 12+, otherwise a cheap downscale + box blur.
 */
class WallpaperStore(private val context: Context) {
    val file: File get() = File(context.filesDir, "wallpaper.jpg")
    fun exists() = file.isFile && file.length() > 0

    /** Blocking (call on IO): decode, downscale to <= 1600px and store as JPEG. */
    fun import(uri: Uri) {
        val bmp = decode(uri) ?: throw IllegalArgumentException("decode failed")
        val tmp = File(context.filesDir, "wallpaper.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    fun remove() { file.delete() }

    /** Blocking (call on IO). */
    fun load(): Bitmap? = if (exists()) BitmapFactory.decodeFile(file.path) else null

    private fun decode(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val s = info.size
                val scale = MAX_SIDE.toFloat() / max(s.width, s.height)
                if (scale < 1f) decoder.setTargetSize((s.width * scale).roundToInt(), (s.height * scale).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    companion object {
        private const val MAX_SIDE = 1600

        /** Shows [bitmap] in [view] blurred by [blurDp]. */
        fun show(view: ImageView, bitmap: Bitmap, blurDp: Int) {
            val px = blurDp * view.resources.displayMetrics.density
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.setImageBitmap(bitmap)
                view.setRenderEffect(
                    if (px > 0f) RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP) else null
                )
            } else {
                view.setImageBitmap(if (blurDp > 0) fallbackBlur(bitmap, blurDp) else bitmap)
            }
        }

        /** Pre-Android 12: shrink, box-blur twice, let the ImageView scale it back up (filtered). */
        fun fallbackBlur(src: Bitmap, blurDp: Int): Bitmap {
            val factor = (2 + blurDp / 3).coerceAtMost(10)
            val w = max(1, src.width / factor)
            val h = max(1, src.height / factor)
            val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            val r = 2
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            repeat(2) {
                boxBlur(px, w, h, r, horizontal = true)
                boxBlur(px, w, h, r, horizontal = false)
            }
            small.setPixels(px, 0, w, 0, 0, w, h)
            return small
        }

        private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val out = IntArray(len)
            for (line in 0 until lines) {
                fun idx(i: Int) = if (horizontal) line * w + i else i * w + line
                for (i in 0 until len) {
                    var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val c = px[idx(j)]
                        a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                    }
                    out[i] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
                for (i in 0 until len) px[idx(i)] = out[i]
            }
        }
    }
}
