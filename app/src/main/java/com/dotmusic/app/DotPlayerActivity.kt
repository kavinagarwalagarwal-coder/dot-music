package com.dotmusic.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager

/** Full-screen dot player. Opens from the widgets' record or title, and from the app. */
class DotPlayerActivity : Activity(), MusicCenter.Observer {
    private lateinit var view: DotPlayerView
    private val beat by lazy { BeatListener(this) { bands -> view.spectrum = bands } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        view = DotPlayerView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        MusicCenter.attach(this)
        MusicCenter.observe(this)
        view.start()
        beat.start()
    }

    override fun onPause() {
        MusicCenter.forget(this)
        view.stop()
        beat.stop()
        super.onPause()
    }

    override fun onTrack(track: Track?) {
        view.setTrack(track, view.takeDirection())
        // A now-playing screen: stay awake while the music plays.
        if (track?.isPlaying == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

}
