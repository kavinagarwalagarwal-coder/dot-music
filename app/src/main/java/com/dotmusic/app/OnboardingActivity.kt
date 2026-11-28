package com.dotmusic.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First run, and every run until it is finished: what Dot is, why it wants to be the phone app,
 * the two permissions it needs, and how the dots should look. There is no skip button; the tour is
 * the only way into the dialer.
 */
class OnboardingActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var record: DotRecord
    private lateinit var heading: TextView
    private lateinit var body: TextView
    private lateinit var actions: LinearLayout
    private lateinit var steps: LinearLayout
    private lateinit var title: TextView
    private var step = 0
    private var force = false

    private fun dp(v: Float) = Glass.px(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        force = intent?.getBooleanExtra(EXTRA_REPLAY, false) == true
        if (Store.onboarded(this) && !force) {
            startActivity(Intent(this, DialerActivity::class.java))
            finish()
            return
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Glass.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(buildUi())
        show(0)
    }

    private fun buildUi(): View {
        root = FrameLayout(this)
        root.addView(AuraBackdrop(this), FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            @Suppress("DEPRECATION")
            val top = maxOf(insets.systemWindowInsetTop, cutout)
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            column.setPadding(dp(22f), top + dp(14f), dp(22f), bottom + dp(16f))
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title = Glass.text(this, "DOT", 18f, Glass.INK, dot = true, spacing = 0.16f)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Glass.label(this, getString(R.string.welcome_tag)), LinearLayout.LayoutParams(-2, -2))
        column.addView(header, LinearLayout.LayoutParams(-1, dp(32f)))

        record = DotRecord(this).apply {
            showInfo = false
            showTitle = false
            mono = Store.whiteDots(this@OnboardingActivity)
        }
        column.addView(record, LinearLayout.LayoutParams(-1, 0, 1f))

        heading = Glass.text(this, "", 26f, Glass.INK, medium = true)
        column.addView(heading, LinearLayout.LayoutParams(-1, -2))

        body = Glass.text(this, "", 13f, Glass.MUTED).apply {
            setLineSpacing(dp(4f).toFloat(), 1f)
        }
        column.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })

        actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        column.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18f) })

        steps = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        column.addView(steps, LinearLayout.LayoutParams(-1, dp(24f)).apply { topMargin = dp(14f) })
        repeat(4) { i ->
            val dot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (i == 0) Glass.INK else Glass.withAlpha(Color.WHITE, 50))
                }
            }
            steps.addView(dot, LinearLayout.LayoutParams(dp(6f), dp(6f)).apply { setMargins(dp(3f), 0, dp(3f), 0) })
        }
        return root
    }

    private fun show(index: Int) {
        step = index.coerceIn(0, 3)
        steps.children().forEachIndexed { i, view ->
            (view.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                if (i <= step) Glass.INK else Glass.withAlpha(Color.WHITE, 50)
            )
        }
        actions.removeAllViews()
        when (step) {
            0 -> {
                heading.text = getString(R.string.onboard_title)
                body.text = getString(R.string.onboard_body)
                record.showInfo = false
                record.showTitle = false
                record.setArt(null, animate = false)
                record.relayout()
                primary(getString(R.string.continue_label)) { show(1) }
            }
            1 -> {
                heading.text = getString(R.string.onboard_role_title)
                body.text = getString(R.string.onboard_role_body)
                if (isDefaultDialer()) {
                    record.setArt(previewArt(), animate = false)
                    record.relayout()
                    primary(getString(R.string.continue_label)) { show(2) }
                } else {
                    primary(getString(R.string.make_default)) { requestDialerRole() }
                    secondary(getString(R.string.later)) { show(2) }
                }
            }
            2 -> {
                heading.text = getString(R.string.onboard_access_title)
                body.text = getString(R.string.onboard_access_body)
                actions.addView(permissionRow(Manifest.permission.READ_CONTACTS, R.string.allow_contacts))
                actions.addView(permissionRow(Manifest.permission.READ_CALL_LOG, R.string.allow_history))
                actions.addView(permissionRow(Manifest.permission.CALL_PHONE, R.string.allow_calls))
                if (Build.VERSION.SDK_INT >= 33) {
                    actions.addView(permissionRow(Manifest.permission.POST_NOTIFICATIONS, R.string.notifications))
                }
                primary(getString(R.string.continue_label)) { show(3) }
            }
            else -> {
                heading.text = getString(R.string.onboard_style_title)
                body.text = getString(R.string.onboard_style_body)
                record.showInfo = false
                record.showTitle = false
                record.mono = Store.whiteDots(this)
                record.setArt(previewArt(), animate = false)
                record.relayout()
                actions.addView(styleRow())
                primary(getString(R.string.start)) { finishTour() }
            }
        }
        heading.alpha = 0f
        heading.translationY = dp(8f).toFloat()
        heading.animate().alpha(1f).translationY(0f)
            .setDuration(240).setInterpolator(Motion.out).start()
        body.alpha = 0f
        body.animate().alpha(1f).setDuration(320).setStartDelay(60).start()
        Motion.enter(actions, 90, 10f)
    }

    private fun previewArt(): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until 96) {
            paint.color = Color.HSVToColor(floatArrayOf(160f + y * 0.9f, 0.55f, 0.55f + y / 320f))
            canvas.drawRect(0f, y.toFloat(), 96f, y + 1f, paint)
        }
        return bitmap
    }

    private fun primary(label: String, action: () -> Unit) = actions.addView(TextView(this).apply {
        text = label.uppercase()
        typeface = Glass.doto(this@OnboardingActivity)
        textSize = 13f
        letterSpacing = 0.14f
        gravity = Gravity.CENTER
        setTextColor(Glass.ON_RED)
        background = Glass.fill(this@OnboardingActivity, Glass.RED, 28f)
        isClickable = true
        isFocusable = true
        Motion.press(this, 0.97f)
        setOnClickListener { Glass.tap(this); action() }
    }, LinearLayout.LayoutParams(-1, dp(54f)).apply { topMargin = dp(8f) })

    private fun secondary(label: String, action: () -> Unit) = actions.addView(TextView(this).apply {
        text = label.uppercase()
        typeface = Glass.doto(this@OnboardingActivity)
        textSize = 12f
        letterSpacing = 0.12f
        gravity = Gravity.CENTER
        setTextColor(Glass.MUTED)
        background = Glass.panel(this@OnboardingActivity, 24f)
        isClickable = true
        isFocusable = true
        Motion.press(this, 0.98f)
        setOnClickListener { Glass.tap(this); action() }
    }, LinearLayout.LayoutParams(-1, dp(44f)).apply { topMargin = dp(8f) })

    private fun permissionRow(permission: String, label: Int): View {
        val allowed = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.panel(this@OnboardingActivity, 22f)
            setPadding(dp(16f), 0, dp(16f), 0)
            isClickable = true
            isFocusable = true
            Motion.press(this, 0.98f)
            addView(Glass.text(this@OnboardingActivity, getString(label), 13f, Glass.INK), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Glass.text(
                this@OnboardingActivity,
                if (allowed) getString(R.string.allowed) else getString(R.string.allow),
                10f,
                if (allowed) Glass.RED else Glass.MUTED,
                dot = true, spacing = 0.1f
            ))
            setOnClickListener {
                if (!allowed) requestPermissions(arrayOf(permission), REQ_PERMISSION)
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(50f)).apply { bottomMargin = dp(8f) }
        }
    }

    private fun styleRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Glass.panel(this@OnboardingActivity, 22f)
        setPadding(dp(16f), 0, dp(16f), 0)
        isClickable = true
        isFocusable = true
        Motion.press(this, 0.98f)
        addView(Glass.text(this@OnboardingActivity, getString(R.string.white_dots), 13f, Glass.INK), LinearLayout.LayoutParams(0, -2, 1f))
        val state = Glass.text(this@OnboardingActivity, "", 10f, Glass.MUTED, dot = true)
        addView(state)
        fun sync() { state.text = if (Store.whiteDots(this@OnboardingActivity)) "ON" else "OFF" }
        sync()
        setOnClickListener {
            Store.setWhiteDots(this@OnboardingActivity, !Store.whiteDots(this@OnboardingActivity))
            record.mono = Store.whiteDots(this@OnboardingActivity)
            record.relayout()
            sync()
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(50f)).apply { bottomMargin = dp(8f) }
    }

    private fun finishTour() {
        Store.setOnboarded(this, true)
        startActivity(Intent(this, DialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
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
        }.onFailure { show(2) }
    }

    @Deprecated("Platform result callbacks are enough for the two system dialogs here.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ROLE) show(step)
    }

    @Deprecated("Platform permission result callback is sufficient here.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) show(2)
    }

    @Deprecated("Platform back handling is enough here.")
    override fun onBackPressed() {
        if (step > 0) show(step - 1) else super.onBackPressed()
    }

    private fun LinearLayout.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    companion object {
        const val EXTRA_REPLAY = "replay"
        private const val REQ_ROLE = 41
        private const val REQ_PERMISSION = 42
    }
}
