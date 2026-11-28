package com.dotmusic.app

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The few things worth choosing in a phone app: what makes the calls, how the dots look, updates. */
class SettingsActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var phoneRow: LinearLayout
    private lateinit var dotsRow: LinearLayout
    private lateinit var speedDialRow: LinearLayout
    private lateinit var hapticsRow: LinearLayout
    private lateinit var vibrationRow: LinearLayout
    private lateinit var missedNotificationsRow: LinearLayout
    private lateinit var badgeRow: LinearLayout
    private lateinit var missedSoundRow: LinearLayout
    private val notes = HashMap<LinearLayout, TextView>()

    private fun dp(v: Float) = Glass.px(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Glass.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (::phoneRow.isInitialized) refreshRows()
    }

    private fun buildUi(): View {
        root = FrameLayout(this)
        root.addView(AuraBackdrop(this), FrameLayout.LayoutParams(-1, -1))
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            @Suppress("DEPRECATION")
            val top = maxOf(insets.systemWindowInsetTop, cutout)
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            column.setPadding(dp(18f), top + dp(10f), dp(18f), bottom + dp(16f))
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        header.addView(Glass.display(this, getString(R.string.settings_title), 32f),
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Glass.MUTED)
            background = Glass.panel(this@SettingsActivity, 999f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.close)
            Motion.press(this, 0.94f)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42f), dp(42f)).apply { bottomMargin = dp(4f) })
        column.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12f) })

        val rows = ArrayList<View>()
        list.addView(section(getString(R.string.section_phone)))
        phoneRow = setting(getString(R.string.default_phone), "", "PH") { requestDialerRole() }
        speedDialRow = setting(getString(R.string.speed_dial_desc), speedDialNote(), "1") { pickSpeedDial() }
        list.addView(group(phoneRow, speedDialRow, infoLine(getString(R.string.voicemail_longpress))))
        rows += phoneRow
        rows += speedDialRow

        list.addView(section(getString(R.string.section_look)))
        dotsRow = setting(getString(R.string.white_dots), "", "DO") {
            Store.setWhiteDots(this, !Store.whiteDots(this))
            refreshRows()
        }
        hapticsRow = setting(getString(R.string.haptics), hapticsNote(), "HA") { toggleHaptics() }
        vibrationRow = setting(getString(R.string.vibration), vibrationNote(), "VI") { toggleVibration() }
        list.addView(group(dotsRow, hapticsRow, vibrationRow))
        rows += dotsRow
        rows += hapticsRow
        rows += vibrationRow

        list.addView(section(getString(R.string.section_missed)))
        missedNotificationsRow = setting(getString(R.string.missed_notifications), missedNotificationsNote(), "MI") { toggleMissedNotifications() }
        badgeRow = setting(getString(R.string.badge), badgeNote(), "BA") { toggleBadge() }
        missedSoundRow = setting(getString(R.string.missed_sound_desc), missedSoundNote(), "SO") { pickMissedSound() }
        list.addView(group(missedNotificationsRow, badgeRow, missedSoundRow))
        rows += missedNotificationsRow
        rows += badgeRow
        rows += missedSoundRow

        list.addView(section(getString(R.string.section_contacts)))
        val photos = setting(getString(R.string.custom_photos), getString(R.string.custom_photos_note), "IM") {
            AlertDialog.Builder(this)
                .setTitle(R.string.custom_photos)
                .setMessage(R.string.clear_photos_note)
                .setPositiveButton(R.string.clear_photos) { _, _ ->
                    ContactPhotos.clearAll(this)
                    Toast.makeText(this, R.string.photos_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        val tour = setting(getString(R.string.replay_tour), "", "TO") {
            startActivity(Intent(this, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_REPLAY, true))
        }
        list.addView(group(photos, tour))
        rows += photos
        rows += tour

        list.addView(section(getString(R.string.section_updates)))
        val updates = setting(
            getString(R.string.check_updates),
            getString(R.string.version, Updates.installedName(this)),
            "UP"
        ) { checkForUpdates() }
        list.addView(group(updates))
        rows += updates
        Motion.enterAll(rows, 26, 12f)

        list.addView(Glass.text(this, getString(R.string.privacy_note), 12f, Glass.DIM).apply {
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(dp(4f), dp(18f), dp(4f), dp(8f))
        })
        return root
    }

    private fun section(label: String): View = Glass.section(this, label).apply {
        setPadding(dp(6f), dp(22f), dp(6f), dp(9f))
    }

    /** Rows stacked in one card, a hairline between them, inset past the leading glyph. */
    private fun group(vararg rows: View): View {
        val card = Glass.group(this)
        rows.forEachIndexed { i, row ->
            if (i > 0) card.addView(Glass.divider(this, dp(58f)))
            card.addView(row)
        }
        return card
    }

    private fun setting(title: String, note: String, glyph: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.row(this@SettingsActivity)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            minimumHeight = dp(60f)
            isClickable = true
            isFocusable = true
            setOnClickListener { Glass.tap(this); onClick() }
        }
        row.addView(TextView(this).apply {
            text = glyph
            typeface = Glass.doto(this@SettingsActivity)
            textSize = 11f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(Glass.GREEN)
            background = Glass.chip(this@SettingsActivity, Glass.GREEN, 11f)
        }, LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { rightMargin = dp(12f) })

        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(Glass.text(this@SettingsActivity, title, 16f, Glass.INK))
        val noteView = Glass.text(this@SettingsActivity, note, 12.5f, Glass.MUTED).apply {
            setPadding(0, dp(2f), 0, 0)
        }
        stack.addView(noteView)
        notes[row] = noteView
        row.addView(stack, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Glass.text(this@SettingsActivity, "\u203A", 20f, Glass.DIM))
        return row
    }

    private fun refreshRows() {
        notes[phoneRow]?.text = if (isDefaultDialer()) getString(R.string.is_default_phone) else getString(R.string.make_default)
        notes[dotsRow]?.text = if (Store.whiteDots(this)) getString(R.string.on) else getString(R.string.off)
        notes[speedDialRow]?.text = speedDialNote()
        notes[hapticsRow]?.text = hapticsNote()
        notes[vibrationRow]?.text = vibrationNote()
        notes[missedNotificationsRow]?.text = missedNotificationsNote()
        notes[badgeRow]?.text = badgeNote()
        notes[missedSoundRow]?.text = missedSoundNote()
    }

    private fun infoLine(text: String): View = Glass.text(this, text, 12.5f, Glass.MUTED).apply {
        setPadding(dp(58f), dp(2f), dp(16f), dp(14f))
        setLineSpacing(dp(3f).toFloat(), 1f)
    }

    private fun speedDialNote(): String {
        val sd = Store.speedDial(this)
        return if (sd.isNullOrEmpty()) getString(R.string.speed_dial_none) else getString(R.string.speed_dial_desc) + ": " + sd
    }

    private fun hapticsNote(): String = if (Store.hapticsEnabled(this)) getString(R.string.setting_on) else getString(R.string.setting_off)

    private fun vibrationNote(): String = if (Store.vibrationEnabled(this)) getString(R.string.setting_on) else getString(R.string.setting_off)

    private fun missedNotificationsNote(): String = if (Store.missedNotifications(this)) getString(R.string.setting_on) else getString(R.string.setting_off)

    private fun badgeNote(): String = if (Store.badgeEnabled(this)) getString(R.string.setting_on) else getString(R.string.setting_off)

    private fun missedSoundNote(): String =
        if (Store.missedSoundUri(this) != null) getString(R.string.missed_sound_set) else getString(R.string.speed_dial_none)

    private fun toggleMissedNotifications() {
        Store.setMissedNotifications(this, !Store.missedNotifications(this))
        if (!Store.missedNotifications(this)) MissedCalls.clear(this)
        refreshRows()
    }

    private fun toggleBadge() {
        Store.setBadgeEnabled(this, !Store.badgeEnabled(this))
        refreshRows()
    }

    private fun toggleHaptics() {
        Store.setHapticsEnabled(this, !Store.hapticsEnabled(this))
        refreshRows()
    }

    private fun toggleVibration() {
        Store.setVibrationEnabled(this, !Store.vibrationEnabled(this))
        refreshRows()
    }

    private fun cycleTheme() {
        val next = if (Store.theme(this) == "dark") "system" else "dark"
        Store.setTheme(this, next)
        refreshRows()
    }

    private fun pickSpeedDial() {
        numberPicker(getString(R.string.speed_dial_desc), Store.speedDial(this), { number ->
            Store.setSpeedDial(this, number.ifEmpty { null })
            refreshRows()
            if (number.isNotEmpty()) Toast.makeText(this, getString(R.string.speed_dial_desc), Toast.LENGTH_SHORT).show()
        })
    }

    private fun pickMissedSound() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            type = "audio/ringtone"
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.missed_sound_desc))
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(Store.missedSoundUri(this@SettingsActivity) ?: ""))
        }
        startActivityForResult(intent, REQ_SOUND)
    }

    private fun numberPicker(title: String, initial: String?, onPick: (String) -> Unit) {
        val edit = EditText(this).apply {
            hint = getString(R.string.enter_number)
            setText(initial)
            textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setTextColor(Glass.INK)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(edit)
            .setPositiveButton(R.string.save) { _, _ ->
                val v = edit.text?.toString()?.trim().orEmpty()
                onPick(v)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SOUND && resultCode == RESULT_OK) {
            val uri = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            Store.setMissedSoundUri(this, uri?.toString())
            refreshRows()
        }
    }

    // ---------------------------------------------------------------- updates

    private fun checkForUpdates() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        Updates.background {
            val latest = Updates.latest()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (latest == null) {
                    Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                } else if (latest.versionCode <= Updates.installedCode(this)) {
                    Toast.makeText(this, getString(R.string.update_up_to_date, Updates.installedName(this)), Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_available, latest.versionName))
                        .setMessage(R.string.update_note)
                        .setPositiveButton(R.string.update_download) { _, _ -> download() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun download() {
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        Updates.background {
            val apk = runCatching { Updates.download(this) }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (apk == null) {
                    Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (Updates.canInstall(this)) {
                    Updates.install(this, apk)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.update_allow)
                        .setMessage(R.string.update_allow_note)
                        .setPositiveButton(R.string.open_settings) { _, _ ->
                            runCatching {
                                startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                                )
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun isDefaultDialer(): Boolean =
        getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage == packageName

    private fun requestDialerRole() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = getSystemService(RoleManager::class.java)
                if (roles?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE)
                }
            } else {
                startActivityForResult(
                    Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName),
                    REQ_ROLE
                )
            }
        }.onFailure { Toast.makeText(this, R.string.role_failed, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val REQ_ROLE = 51
        const val REQ_SOUND = 52
    }
}
