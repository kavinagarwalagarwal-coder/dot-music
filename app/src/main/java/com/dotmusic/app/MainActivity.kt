package com.dotmusic.app

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup, a live preview of the dot record, one-tap widget adding, and the few settings. */
class MainActivity : Activity(), MusicCenter.Observer {
    private lateinit var preview: DotPlayerView
    private lateinit var access: Button
    private val d by lazy { resources.displayMetrics.density }
    private val doto: Typeface by lazy {
        runCatching { Typeface.Builder(assets, "fonts/Doto.ttf").setFontVariationSettings("'wght' 900, 'ROND' 100").build() }
            .getOrDefault(Typeface.MONOSPACE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        MusicCenter.attach(this)
        MusicCenter.observe(this)
        preview.start()
        val ok = MusicListener.enabled(this)
        access.text = getString(if (ok) R.string.access_on else R.string.access_off)
        access.alpha = if (ok) 0.6f else 1f
    }

    override fun onPause() {
        MusicCenter.forget(this)
        preview.stop()
        super.onPause()
    }

    override fun onTrack(track: Track?) = preview.setTrack(track, preview.takeDirection())

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (40 * d).toInt(), (22 * d).toInt(), (40 * d).toInt())
        }
        column.addView(heading(getString(R.string.app_name).uppercase(), 34f))
        column.addView(body(getString(R.string.tagline)))

        preview = DotPlayerView(this)
        column.addView(preview, LinearLayout.LayoutParams(-1, (560 * d).toInt()).apply { topMargin = (12 * d).toInt() })
        column.addView(button(getString(R.string.open_player)) {
            startActivity(Intent(this, DotPlayerActivity::class.java))
        })

        column.addView(heading(getString(R.string.section_setup), 20f))
        access = button(getString(R.string.access_off)) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        column.addView(access)
        column.addView(body(getString(R.string.access_note)))

        column.addView(heading(getString(R.string.section_widgets), 20f))
        column.addView(body(getString(R.string.widgets_note)))
        column.addView(button(getString(R.string.add_small)) { pin(SmallDotWidget::class.java) })
        column.addView(button(getString(R.string.add_medium)) { pin(MediumDotWidget::class.java) })
        column.addView(button(getString(R.string.add_wide)) { pin(WideDotWidget::class.java) })

        column.addView(heading(getString(R.string.section_style), 20f))
        column.addView(toggle(R.string.setting_mono, R.string.setting_mono_note, DotPrefs.mono(this)) {
            DotPrefs.setMono(this, it); MusicWidgets.refresh(this); recreatePreview()
        })
        column.addView(toggle(R.string.setting_animations, R.string.setting_animations_note, DotPrefs.animations(this)) {
            DotPrefs.setAnimations(this, it); MusicWidgets.refresh(this)
        })
        column.addView(toggle(R.string.setting_beat, R.string.setting_beat_note, DotPrefs.beat(this)) { on ->
            DotPrefs.setBeat(this, on)
            if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            }
        })

        column.addView(body(getString(R.string.privacy)).apply { setPadding(0, (28 * d).toInt(), 0, 0) })
        return ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(column) }
    }

    /** Mono/colour changes the whole palette; rebuild the preview's record with it. */
    private fun recreatePreview() {
        val parent = preview.parent as LinearLayout
        val index = parent.indexOfChild(preview)
        val lp = preview.layoutParams
        preview.stop()
        parent.removeView(preview)
        preview = DotPlayerView(this)
        parent.addView(preview, index, lp)
        preview.setTrack(MusicCenter.current)
    }

    private fun pin(cls: Class<*>) {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager?.isRequestPinAppWidgetSupported == true) {
            manager.requestPinAppWidget(ComponentName(this, cls), null, null)
        } else {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- views

    private fun heading(s: String, size: Float) = TextView(this).apply {
        text = s; textSize = size; typeface = doto; setTextColor(Color.WHITE)
        setPadding(0, (24 * d).toInt(), 0, (6 * d).toInt())
    }

    private fun body(s: String) = TextView(this).apply {
        text = s; textSize = 14f; setTextColor(Color.argb(170, 255, 255, 255))
        setLineSpacing(0f, 1.15f)
        setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = 26 * d; setColor(Color.rgb(28, 28, 28)); setStroke((1 * d).toInt(), Color.argb(40, 255, 255, 255)) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, (52 * d).toInt()).apply { topMargin = (8 * d).toInt() }
    }

    private fun toggle(title: Int, note: Int, checked: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (8 * d).toInt(), 0, (4 * d).toInt())
        addView(Switch(this@MainActivity).apply {
            text = getString(title); textSize = 16f; setTextColor(Color.WHITE); isChecked = checked
            minHeight = (48 * d).toInt(); gravity = Gravity.CENTER_VERTICAL
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
        addView(body(getString(note)))
    }
}
