package com.dotmusic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import java.io.File
import kotlin.math.max

/**
 * The live wallpaper's background photo. Android doesn't let apps read your current wallpaper,
 * so you pick the photo once; a screen-sized copy is kept in the app's private storage.
 */
object WallpaperPhoto {
    private fun file(c: Context) = File(c.filesDir, "wallpaper.jpg")

    fun exists(c: Context) = file(c).exists()

    fun clear(c: Context) { file(c).delete(); bump(c) }

    /** Copies the picked photo, shrunk to about screen size so it loads fast and stays small. */
    fun save(c: Context, uri: Uri): Boolean = runCatching {
        val screen = c.resources.displayMetrics
        val target = max(screen.widthPixels, screen.heightPixels)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val bitmap = c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        file(c).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        bump(c)
        true
    }.getOrDefault(false)

    fun load(c: Context): Bitmap? = runCatching { BitmapFactory.decodeFile(file(c).path) }.getOrNull()

    /** Draws [photo] to fill [w]×[h], cropping the edges like Android does for wallpapers. */
    fun drawCover(canvas: Canvas, photo: Bitmap, w: Int, h: Int, matrix: Matrix, paint: Paint) {
        val scale = max(w.toFloat() / photo.width, h.toFloat() / photo.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate((w - photo.width * scale) / 2f, (h - photo.height * scale) / 2f)
        canvas.drawBitmap(photo, matrix, paint)
    }

    /** Tells a running wallpaper to reload the photo. */
    private fun bump(c: Context) = c.getSharedPreferences("dots", Context.MODE_PRIVATE).edit()
        .putLong("photo_version", System.currentTimeMillis()).apply()
}
