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

/** Settings: connecting YouTube Music, recommendations, widgets, the live wallpaper and style. */
class MainActivity : Activity() {
    private lateinit var access: Button
    private lateinit var ytButton: Button
    private lateinit var forget: Button
    private lateinit var photoButton: Button
    private lateinit var removePhoto: Button
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
        val ok = MusicListener.enabled(this)
        access.text = getString(if (ok) R.string.access_on else R.string.access_off)
        access.alpha = if (ok) 0.6f else 1f
        val yt = YouTubeMusic.installed(this)
        ytButton.text = getString(if (yt) R.string.open_yt_music else R.string.get_yt_music)
        forget.text = getString(R.string.forget_history, Taste.count())
    }

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (40 * d).toInt(), (22 * d).toInt(), (40 * d).toInt())
        }
        column.addView(heading(getString(R.string.settings_title).uppercase(), 34f))
        column.addView(body(getString(R.string.tagline)))

        column.addView(heading(getString(R.string.section_youtube), 20f))
        column.addView(body(getString(R.string.youtube_note)))
        access = button(getString(R.string.access_off)) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        column.addView(access)
        column.addView(body(getString(R.string.access_note)))
        ytButton = button(getString(R.string.open_yt_music)) { YouTubeMusic.open(this) }
        column.addView(ytButton)
        column.addView(toggle(R.string.setting_yt_only, R.string.setting_yt_only_note, DotPrefs.ytOnly(this)) { DotPrefs.setYtOnly(this, it) })

        column.addView(heading(getString(R.string.section_recs), 20f))
        column.addView(body(getString(R.string.recs_note)))
        column.addView(toggle(R.string.setting_dj, R.string.setting_dj_note, DotPrefs.dj(this)) { DotPrefs.setDj(this, it) })
        column.addView(toggle(R.string.setting_learn, R.string.setting_learn_note, DotPrefs.learn(this)) { DotPrefs.setLearn(this, it) })
        forget = button(getString(R.string.forget_history, 0)) {
            android.app.AlertDialog.Builder(this)
                .setMessage(R.string.forget_confirm)
                .setPositiveButton(R.string.forget_yes) { _, _ ->
                    Taste.clear(this); forget.text = getString(R.string.forget_history, 0)
                    Toast.makeText(this, R.string.forgotten, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        column.addView(forget)

        column.addView(heading(getString(R.string.section_widgets), 20f))
        column.addView(body(getString(R.string.widgets_note)))
        column.addView(button(getString(R.string.add_small)) { pin(SmallDotWidget::class.java) })
        column.addView(button(getString(R.string.add_medium)) { pin(MediumDotWidget::class.java) })
        column.addView(button(getString(R.string.add_stage)) { pin(StageDotWidget::class.java) })
        column.addView(button(getString(R.string.add_wide)) { pin(WideDotWidget::class.java) })

        column.addView(heading(getString(R.string.section_wallpaper), 20f))
        column.addView(body(getString(R.string.wallpaper_note)))
        column.addView(body(getString(R.string.photo_note)))
        photoButton = button(getString(R.string.pick_photo)) {
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_PHOTO)
            }
        }
        column.addView(photoButton)
        removePhoto = button(getString(R.string.remove_photo)) { WallpaperPhoto.clear(this); refreshPhoto() }
        column.addView(removePhoto)
        refreshPhoto()
        column.addView(toggle(R.string.setting_wallpaper_dots, R.string.setting_wallpaper_dots_note, DotPrefs.wallpaperDots(this)) {
            DotPrefs.setWallpaperDots(this, it)
        })
        column.addView(toggle(R.string.setting_wallpaper_playing, R.string.setting_wallpaper_playing_note, DotPrefs.wallpaperOnlyPlaying(this)) {
            DotPrefs.setWallpaperOnlyPlaying(this, it)
        })
        column.addView(button(getString(R.string.set_wallpaper)) {
            val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, DotWallpaper::class.java))
            runCatching { startActivity(intent) }.onFailure {
                runCatching { startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            }
        })

        column.addView(heading(getString(R.string.section_style), 20f))
        column.addView(toggle(R.string.setting_mono, R.string.setting_mono_note, DotPrefs.mono(this)) {
            DotPrefs.setMono(this, it); MusicWidgets.refresh(this)
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

    @Deprecated("Activity result API needs androidx; the platform callback is enough here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val ok = WallpaperPhoto.save(this, uri)
        Toast.makeText(this, if (ok) R.string.photo_saved else R.string.photo_failed, Toast.LENGTH_SHORT).show()
        refreshPhoto()
    }

    private fun refreshPhoto() {
        val has = WallpaperPhoto.exists(this)
        photoButton.text = getString(if (has) R.string.pick_photo_change else R.string.pick_photo)
        removePhoto.visibility = if (has) View.VISIBLE else View.GONE
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

    private companion object {
        const val PICK_PHOTO = 7
    }
}
