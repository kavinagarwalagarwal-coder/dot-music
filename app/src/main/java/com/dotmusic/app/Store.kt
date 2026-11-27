package com.dotmusic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Everything the phone remembers: the tour, the dot style, stars, and the contact photos. */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("dot_phone", Context.MODE_PRIVATE)

    fun onboarded(c: Context) = prefs(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context, done: Boolean) = prefs(c).edit().putBoolean("onboarded", done).apply()

    /** Draw every contact photo in white dots, Nothing style. */
    fun whiteDots(c: Context) = prefs(c).getBoolean("white_dots", false)
    fun setWhiteDots(c: Context, on: Boolean) = prefs(c).edit().putBoolean("white_dots", on).apply()

    fun starred(c: Context): Set<String> = prefs(c).getStringSet("starred", emptySet()) ?: emptySet()

    /** Returns whether the contact is starred now. */
    fun toggleStar(c: Context, key: String): Boolean {
        val next = starred(c).toMutableSet()
        val on = if (next.contains(key)) { next -= key; false } else { next += key; true }
        prefs(c).edit().putStringSet("starred", next).apply()
        return on
    }

    /**
     * The key a phone number is remembered by: digits only, last ten, so +44 7700 900123 and
     * 07700900123 are the same person. Falls back to the raw text for short codes.
     */
        fun key(number: String): String {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length >= 10 -> digits.takeLast(10)
            digits.isNotEmpty() -> digits
            else -> number.trim()
        }
    }

    fun missedNotifications(c: Context) = prefs(c).getBoolean("missed", true)
    fun setMissedNotifications(c: Context, on: Boolean) = prefs(c).edit().putBoolean("missed", on).apply()
    fun badgeEnabled(c: Context) = prefs(c).getBoolean("badge", true)
    fun setBadgeEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("badge", on).apply()
    fun missedSoundEnabled(c: Context) = prefs(c).getBoolean("missed_sound", true)
    fun setMissedSoundEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("missed_sound", on).apply()
    fun missedSoundUri(c: Context): String? = prefs(c).getString("missed_sound_uri", null)
    fun setMissedSoundUri(c: Context, uri: String?) = prefs(c).edit().putString("missed_sound_uri", uri).apply()
    fun hapticsEnabled(c: Context) = prefs(c).getBoolean("haptics", true)
    fun setHapticsEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("haptics", on).apply()
    fun vibrationEnabled(c: Context) = prefs(c).getBoolean("vibration", true)
    fun setVibrationEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("vibration", on).apply()
    fun theme(c: Context): String = prefs(c).getString("theme", "system") ?: "system"
    fun setTheme(c: Context, t: String) = prefs(c).edit().putString("theme", t).apply()
    fun speedDial(c: Context): String? = prefs(c).getString("speed_dial", null)
    fun setSpeedDial(c: Context, number: String?) = prefs(c).edit().putString("speed_dial", number).apply()

}

/**
 * Photos chosen inside Dot for a contact, kept in the app's own storage so they can be turned into
 * the dot record the call screen shows. Keyed by [Store.key], so they follow the number.
 */
object ContactPhotos {
    private const val MAX_EDGE = 512
    private val cache = android.util.LruCache<String, Bitmap>(24)

    private fun dir(c: Context) = File(c.filesDir, "contact_photos").apply { mkdirs() }

    private fun file(c: Context, key: String) =
        File(dir(c), Integer.toHexString(key.hashCode()) + ".jpg")

    fun has(c: Context, key: String) = key.isNotEmpty() && file(c, key).exists()

    fun load(c: Context, key: String): Bitmap? {
        if (key.isEmpty()) return null
        cache.get(key)?.let { return it }
        return runCatching {
            val bitmap = BitmapFactory.decodeFile(file(c, key).absolutePath)
            if (bitmap != null) cache.put(key, bitmap)
            bitmap
        }.getOrNull()
    }

    /** Centre-crops to a square, scales it down and writes it as a JPEG. */
    fun save(c: Context, key: String, source: Bitmap): Boolean = runCatching {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side, side
        )
        val edge = minOf(side, MAX_EDGE)
        val scaled = if (edge == side) square else Bitmap.createScaledBitmap(square, edge, edge, true)
        file(c, key).outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val kept = runCatching { scaled.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        if (scaled !== source && scaled !== square) scaled.recycle()
        if (square !== source) square.recycle()
        // Avatars of the old photo are stale now, so the whole small cache goes.
        cache.evictAll()
        if (kept != null) cache.put(key, kept)
        true
    }.getOrDefault(false)

    fun clear(c: Context, key: String) {
        cache.evictAll()
        runCatching { file(c, key).delete() }
    }

    fun clearAll(c: Context) {
        cache.evictAll()
        runCatching { dir(c).listFiles()?.forEach { it.delete() } }
    }

    /** A decoded bitmap from a picked image, sampled so a 40MP photo doesn't blow up the heap. */
    fun decode(c: Context, uri: android.net.Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE && bounds.outHeight / (sample * 2) >= MAX_EDGE) sample *= 2
        c.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** The photo as a small dot disc, for lists and cards. */
    fun avatar(c: Context, key: String, sizePx: Int, mono: Boolean = false): Bitmap? {
        val source = load(c, key) ?: return null
        val cacheKey = "avatar|$key|$sizePx|$mono"
        cache.get(cacheKey)?.let { return it }
        val art = DotArt.cover(source, n = 19, size = sizePx, mono = mono, disc = true)
        cache.put(cacheKey, art)
        return art
    }

    /** A dot disc drawn from a bitmap that isn't stored (an address-book photo, say). */
    fun dotsOf(source: Bitmap, sizePx: Int, mono: Boolean = false): Bitmap =
        DotArt.cover(source, n = 19, size = sizePx, mono = mono, disc = true)
}
