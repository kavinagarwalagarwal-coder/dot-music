package com.dotmusic.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** A lightweight dialer that hands calls off to Android's system dialer for confirmation. */
class DialerActivity : Activity() {
    private lateinit var numberView: TextView
    private val number = StringBuilder()
    private val density get() = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(5, 6, 8)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(16f), dp(22f), dp(14f))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val cutoutTop = if (android.os.Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            @Suppress("DEPRECATION")
            val top = maxOf(insets.systemWindowInsetTop, cutoutTop)
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            content.setPadding(dp(22f), top + dp(12f), dp(22f), bottom + dp(12f))
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "DOT"; textSize = 22f; typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = 0.16f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "PHONE  /  01"; textSize = 11f; letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(145, 151, 158))
        })
        content.addView(header, LinearLayout.LayoutParams(-1, dp(42f)))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = pill(Color.rgb(20, 22, 25), 24f)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        tabs.addView(tab("KEYPAD", selected = true) {}, LinearLayout.LayoutParams(0, dp(38f), 1f))
        tabs.addView(tab("CONTACTS", selected = false) { openContacts() }, LinearLayout.LayoutParams(0, dp(38f), 1f))
        content.addView(tabs, LinearLayout.LayoutParams(-1, dp(46f)).apply { topMargin = dp(12f) })

        content.addView(DotOrbitView(), LinearLayout.LayoutParams(-1, dp(82f)).apply { topMargin = dp(5f) })

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        numberView = TextView(this).apply {
            text = "Enter number"; textSize = 32f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START
            contentDescription = "Phone number"
        }
        display.addView(numberView, LinearLayout.LayoutParams(0, -1, 1f))
        val erase = TextView(this).apply {
            text = "⌫"; textSize = 25f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(174, 181, 188)); contentDescription = "Delete last digit"
            setOnClickListener { if (number.isNotEmpty()) { number.deleteCharAt(number.lastIndex); refreshNumber() } }
            setOnLongClickListener { number.clear(); refreshNumber(); true }
        }
        display.addView(erase, LinearLayout.LayoutParams(dp(48f), dp(54f)))
        content.addView(display, LinearLayout.LayoutParams(-1, dp(58f)))

        val keypad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val keys = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to "")
        )
        keys.forEach { rowKeys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            rowKeys.forEach { (digit, letters) ->
                row.addView(key(digit, letters) { number.append(digit); refreshNumber() },
                    LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(5f), dp(4f), dp(5f), dp(4f)) })
            }
            keypad.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        content.addView(keypad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(3f) })

        val call = TextView(this).apply {
            text = "☎     CALL"; textSize = 17f; letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER; setTextColor(Color.rgb(4, 17, 12))
            background = pill(Color.rgb(102, 232, 161), 32f)
            contentDescription = "Call this number"
            setOnClickListener { startDialIntent() }
        }
        content.addView(call, LinearLayout.LayoutParams(-1, dp(58f)).apply { topMargin = dp(12f) })
        content.addView(TextView(this).apply {
            text = "CALLS ARE PLACED WITH YOUR PHONE APP"; textSize = 9f; letterSpacing = 0.12f
            gravity = Gravity.CENTER; setTextColor(Color.rgb(94, 101, 109))
        }, LinearLayout.LayoutParams(-1, dp(24f)).apply { topMargin = dp(4f) })
        return root
    }

    private fun tab(label: String, selected: Boolean, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 11f; letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(if (selected) Color.WHITE else Color.rgb(132, 139, 147))
        if (selected) background = pill(Color.rgb(43, 47, 52), 22f)
        setOnClickListener { action() }
    }

    private fun key(digit: String, letters: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = pill(Color.rgb(17, 19, 22), 30f)
        isClickable = true; isFocusable = true
        addView(TextView(this@DialerActivity).apply {
            text = digit; textSize = 27f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setTextColor(Color.WHITE)
        })
        if (letters.isNotEmpty()) addView(TextView(this@DialerActivity).apply {
            text = letters; textSize = 9f; letterSpacing = 0.18f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(Color.rgb(123, 131, 139))
        })
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); action() }
    }

    private fun pill(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun refreshNumber() {
        numberView.text = if (number.isEmpty()) "Enter number" else number.toString()
        numberView.setTextColor(if (number.isEmpty()) Color.rgb(106, 113, 121) else Color.WHITE)
    }

    private fun startDialIntent() {
        if (number.isEmpty()) {
            Toast.makeText(this, "Enter a number first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number.toString(), null))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No phone app is available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openContacts() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No contacts app is available", Toast.LENGTH_SHORT).show()
        }
    }

    /** Dotted orbital artwork borrows the player's dot-record language without media controls. */
    private inner class DotOrbitView : View(this@DialerActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val scale = density
            val rings = listOf(12f to 12, 23f to 20, 35f to 28)
            rings.forEachIndexed { ringIndex, (radiusDp, count) ->
                for (i in 0 until count) {
                    val angle = (Math.PI * 2.0 * i / count) - Math.PI / 2.0
                    val radius = radiusDp * scale
                    val x = cx + kotlin.math.cos(angle).toFloat() * radius
                    val y = cy + kotlin.math.sin(angle).toFloat() * radius
                    val alpha = when {
                        ringIndex == 2 && i % 4 == 0 -> 160
                        ringIndex == 1 && i % 5 == 0 -> 105
                        else -> 38
                    }
                    paint.color = Color.argb(alpha, 231, 238, 242)
                    canvas.drawCircle(x, y, (if (ringIndex == 0) 2.2f else 1.35f) * scale, paint)
                }
            }
            paint.color = Color.rgb(102, 232, 161)
            canvas.drawCircle(cx + 35f * scale, cy, 2.2f * scale, paint)
        }
    }
}
