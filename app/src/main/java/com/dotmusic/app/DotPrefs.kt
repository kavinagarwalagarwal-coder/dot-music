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

    /** The app that plays the music (YouTube Music unless you pick another). */
    fun playerPackage(c: Context): String = p(c).getString("player_package", null) ?: PlayerApp.YT_MUSIC
    fun setPlayerPackage(c: Context, pkg: String) = p(c).edit().putString("player_package", pkg).apply()

    /** Follow only the player app, never other apps' sessions. */
    fun playerOnly(c: Context) = p(c).getBoolean("player_only", false)
    fun setPlayerOnly(c: Context, on: Boolean) = p(c).edit().putBoolean("player_only", on).apply()

    /** Next goes to Dot's own pick instead of the player app's queue. */
    fun dj(c: Context) = p(c).getBoolean("dj", false)
    fun setDj(c: Context, on: Boolean) = p(c).edit().putBoolean("dj", on).apply()

    /** Learn from what plays, for recommendations. */
    fun learn(c: Context) = p(c).getBoolean("learn", true)
    fun setLearn(c: Context, on: Boolean) = p(c).edit().putBoolean("learn", on).apply()

    /** Your YouTube Data API key: turns on YouTube search and playback in Dot. */
    fun youtubeKey(c: Context): String = p(c).getString("youtube_key", null).orEmpty().trim()
    fun setYoutubeKey(c: Context, key: String) = p(c).edit().putString("youtube_key", key.trim()).apply()

    /** Audius: free, no key. */
    fun audius(c: Context) = p(c).getBoolean("audius", true)
    fun setAudius(c: Context, on: Boolean) = p(c).edit().putBoolean("audius", on).apply()

    /** Your Jamendo client id: turns on Jamendo. */
    fun jamendoId(c: Context): String = p(c).getString("jamendo_id", null).orEmpty().trim()
    fun setJamendoId(c: Context, id: String) = p(c).edit().putString("jamendo_id", id.trim()).apply()
}
