package com.dotmusic.app

import android.content.Context

/** The few choices the app remembers. */
object DotPrefs {
    private fun p(c: Context) = c.getSharedPreferences("dots", Context.MODE_PRIVATE)

    /** White dots like Nothing's glyphs, instead of the cover's own colours. */
    fun mono(c: Context) = p(c).getBoolean("mono", false)
    fun setMono(c: Context, on: Boolean) = p(c).edit().putBoolean("mono", on).apply()

    /** Widget animations (equaliser, orbit, ripples) while a song plays. */
    fun animations(c: Context) = p(c).getBoolean("animations", true)
    fun setAnimations(c: Context, on: Boolean) = p(c).edit().putBoolean("animations", on).apply()

    /** Live wallpaper: show the dot player over the background photo at all. */
    fun wallpaperDots(c: Context) = p(c).getBoolean("wallpaper_dots", true)
    fun setWallpaperDots(c: Context, on: Boolean) = p(c).edit().putBoolean("wallpaper_dots", on).apply()

    /** Live wallpaper: only while music plays (otherwise just the photo). */
    fun wallpaperOnlyPlaying(c: Context) = p(c).getBoolean("wallpaper_only_playing", true)
    fun setWallpaperOnlyPlaying(c: Context, on: Boolean) = p(c).edit().putBoolean("wallpaper_only_playing", on).apply()

    fun listen(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        p(c).registerOnSharedPreferenceChangeListener(l)
    fun unlisten(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        p(c).unregisterOnSharedPreferenceChangeListener(l)

    /** The full-screen player reacts to the music's real loudness (needs the microphone permission). */
    fun beat(c: Context) = p(c).getBoolean("beat", false)
    fun setBeat(c: Context, on: Boolean) = p(c).edit().putBoolean("beat", on).apply()
}
