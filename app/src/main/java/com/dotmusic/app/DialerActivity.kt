package com.dotmusic.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Dot Phone: a keypad-first dialer in the app's dot-matrix look, with recents and contacts.
 *
 * Choosing Dot as the phone app lets it place carrier calls itself, which is what gives it its own
 * call screen ([DotCallActivity]). Until then, calls are handed to whichever phone app is default.
 */
class DialerActivity : Activity() {
    private lateinit var display: TextView
    private lateinit var matched: TextView
    private lateinit var callButton: TextView
    private lateinit var defaultButton: TextView
    private lateinit var keypadPage: View
    private lateinit var recentsPage: View
    private lateinit var contactsPage: View
    private lateinit var recentsList: LinearLayout
    private lateinit var contactsList: LinearLayout
    private lateinit var search: EditText
    private val tabViews = ArrayList<TextView>()
    private val number = StringBuilder()
    private var page = PAGE_KEYPAD
    private var contacts: List<Contact>? = null
    private var nameToken = 0
    private var pendingCall: String? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(buildUi())
        show(PAGE_KEYPAD)
        readIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::defaultButton.isInitialized) {
            defaultButton.visibility = if (isDefaultDialer()) View.GONE else View.VISIBLE
        }
    }

    private fun readIntent(intent: Intent?) {
        val raw = intent?.data?.schemeSpecificPart ?: return
        if (raw.isBlank()) return
        number.setLength(0); number.append(raw)
        refreshNumber()
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val cutoutTop = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            @Suppress("DEPRECATION")
            val top = maxOf(insets.systemWindowInsetTop, cutoutTop)
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            content.setPadding(dp(20f), top + dp(10f), dp(20f), bottom + dp(10f))
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "DOT"
            typeface = DotUi.doto(this@DialerActivity)
            textSize = 22f
            letterSpacing = 0.16f
            setTextColor(INK)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = getString(R.string.dialer_player).uppercase()
            typeface = DotUi.doto(this@DialerActivity)
            textSize = 11f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            background = DotUi.pill(this@DialerActivity, PILL, 18f)
            setPadding(dp(14f), 0, dp(14f), 0)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.dialer_open_player)
            setOnClickListener { startActivity(Intent(this@DialerActivity, DotPlayerActivity::class.java)) }
        }, LinearLayout.LayoutParams(-2, dp(34f)))
        content.addView(header, LinearLayout.LayoutParams(-1, dp(40f)))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = DotUi.pill(this@DialerActivity, PILL, 24f)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        tabs.addView(tab(getString(R.string.dialer_tab_keypad), PAGE_KEYPAD), LinearLayout.LayoutParams(0, dp(36f), 1f))
        tabs.addView(tab(getString(R.string.dialer_tab_recents), PAGE_RECENTS), LinearLayout.LayoutParams(0, dp(36f), 1f))
        tabs.addView(tab(getString(R.string.dialer_tab_contacts), PAGE_CONTACTS), LinearLayout.LayoutParams(0, dp(36f), 1f))
        content.addView(tabs, LinearLayout.LayoutParams(-1, dp(44f)).apply { topMargin = dp(10f) })

        keypadPage = buildKeypad()
        recentsPage = buildRecents()
        contactsPage = buildContacts()
        val pages = FrameLayout(this)
        pages.addView(keypadPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(recentsPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(contactsPage, FrameLayout.LayoutParams(-1, -1))
        content.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10f) })
        return root
    }

    private fun tab(label: String, index: Int) = TextView(this).apply {
        text = label.uppercase()
        typeface = DotUi.doto(this@DialerActivity)
        textSize = 11f
        letterSpacing = 0.1f
        gravity = Gravity.CENTER
        setTextColor(MUTED)
        isClickable = true
        isFocusable = true
        setOnClickListener { show(index) }
        tabViews += this
    }

    private fun buildKeypad(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val displayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        display = TextView(this).apply {
            text = getString(R.string.dialer_enter_number)
            textSize = 30f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            contentDescription = getString(R.string.dialer_phone_number)
        }
        matched = TextView(this).apply {
            textSize = 11f
            letterSpacing = 0.1f
            typeface = DotUi.doto(this@DialerActivity)
            setTextColor(GREEN)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        stack.addView(display, LinearLayout.LayoutParams(-1, -2))
        stack.addView(matched, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
        displayRow.addView(stack, LinearLayout.LayoutParams(0, -2, 1f))

        val erase = TextView(this).apply {
            text = "⌫"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            contentDescription = getString(R.string.dialer_delete)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (number.isNotEmpty()) { number.deleteCharAt(number.lastIndex); refreshNumber() }
            }
            setOnLongClickListener { number.setLength(0); refreshNumber(); true }
        }
        displayRow.addView(erase, LinearLayout.LayoutParams(dp(46f), dp(52f)))
        page.addView(displayRow, LinearLayout.LayoutParams(-1, dp(64f)))

        page.addView(DotOrbitView(), LinearLayout.LayoutParams(-1, dp(52f)))

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
                row.addView(key(digit, letters),
                    LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(5f), dp(4f), dp(5f), dp(4f)) })
            }
            keypad.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        page.addView(keypad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2f) })

        defaultButton = TextView(this).apply {
            text = getString(R.string.dialer_default).uppercase()
            textSize = 10f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(GREEN)
            isClickable = true
            isFocusable = true
            setOnClickListener { requestDialerRole() }
        }
        page.addView(defaultButton, LinearLayout.LayoutParams(-1, dp(34f)).apply { topMargin = dp(4f) })

        callButton = TextView(this).apply {
            text = getString(R.string.dialer_call).uppercase()
            textSize = 16f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(4, 17, 12))
            background = DotUi.pill(this@DialerActivity, GREEN, 30f)
            contentDescription = getString(R.string.dialer_call)
            isClickable = true
            isFocusable = true
            setOnClickListener { dial(number.toString()) }
        }
        page.addView(callButton, LinearLayout.LayoutParams(-1, dp(56f)).apply { topMargin = dp(4f) })
        return page
    }

    private fun buildRecents(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(section(getString(R.string.dialer_tab_recents)), LinearLayout.LayoutParams(-1, dp(26f)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        recentsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(recentsList, FrameLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    private fun buildContacts(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        search = EditText(this).apply {
            hint = getString(R.string.dialer_search_contacts)
            setHintTextColor(DIM)
            setTextColor(INK)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = DotUi.pill(this@DialerActivity, PILL, 20f)
            setPadding(dp(16f), 0, dp(16f), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = renderContacts(s?.toString().orEmpty())
            })
        }
        page.addView(search, LinearLayout.LayoutParams(-1, dp(48f)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        contactsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(contactsList, FrameLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10f) })
        return page
    }

    private fun section(label: String) = TextView(this).apply {
        text = label.uppercase()
        typeface = DotUi.doto(this@DialerActivity)
        textSize = 10f
        letterSpacing = 0.16f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(MUTED)
    }

    private fun key(digit: String, letters: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = DotUi.pill(this@DialerActivity, PILL, 26f)
        isClickable = true
        isFocusable = true
        addView(TextView(this@DialerActivity).apply {
            text = digit
            textSize = 26f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(INK)
        })
        if (letters.isNotEmpty()) addView(TextView(this@DialerActivity).apply {
            text = letters
            textSize = 9f
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(MUTED)
        })
        setOnClickListener { tap(digit) }
        // 0 doubles as +, the way every phone keypad does it.
        if (digit == "0") setOnLongClickListener { tap("+"); true }
    }

    private fun tap(digit: String) {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        number.append(digit)
        refreshNumber()
    }

    private fun refreshNumber() {
        val text = number.toString()
        display.text = if (text.isEmpty()) getString(R.string.dialer_enter_number) else text
        display.setTextColor(if (text.isEmpty()) DIM else INK)
        callButton.alpha = if (text.isEmpty()) 0.45f else 1f
        lookupName(text)
    }

    private fun show(which: Int) {
        page = which
        keypadPage.visibility = if (which == PAGE_KEYPAD) View.VISIBLE else View.GONE
        recentsPage.visibility = if (which == PAGE_RECENTS) View.VISIBLE else View.GONE
        contactsPage.visibility = if (which == PAGE_CONTACTS) View.VISIBLE else View.GONE
        tabViews.forEachIndexed { index, view ->
            val on = index == which
            view.setTextColor(if (on) INK else MUTED)
            view.background = if (on) DotUi.pill(this, PILL_ON, 20f) else null
        }
        when (which) {
            PAGE_RECENTS -> fillRecents()
            PAGE_CONTACTS -> fillContacts(search.text.toString())
        }
    }

    // ---------------------------------------------------------------- contacts and recents

    /** Name under the typed digits, once they look like a number Dot knows. */
    private fun lookupName(text: String) {
        val token = ++nameToken
        matched.text = ""
        if (text.length < 3 || !granted(Manifest.permission.READ_CONTACTS)) return
        Thread {
            var found: String? = null
            runCatching {
                contentResolver.query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(text)),
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
                )?.use { cursor -> if (cursor.moveToFirst()) found = cursor.getString(0) }
            }
            runOnUiThread {
                if (token == nameToken && !isFinishing) matched.text = found?.uppercase().orEmpty()
            }
        }.start()
    }

    private fun fillContacts(query: String) {
        contactsList.removeAllViews()
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            contactsList.addView(ask(getString(R.string.dialer_allow_contacts), getString(R.string.dialer_permission_note)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            })
            return
        }
        val cached = contacts
        if (cached != null) { renderContacts(query); return }
        Thread {
            val found = ArrayList<Contact>()
            runCatching {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext() && found.size < 600) {
                        val phone = cursor.getString(numberIndex).orEmpty()
                        if (phone.isBlank()) continue
                        found += Contact(cursor.getString(nameIndex).orEmpty().ifBlank { phone }, phone)
                    }
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                contacts = found
                renderContacts(query)
            }
        }.start()
    }

    private fun renderContacts(query: String) {
        contactsList.removeAllViews()
        val all = contacts ?: return
        val needle = query.trim()
        val stripped = needle.replace(" ", "").replace("-", "")
        val shown = all.filter {
            needle.isEmpty() || it.name.contains(needle, true) ||
                (stripped.isNotEmpty() && it.number.replace(" ", "").replace("-", "").contains(stripped))
        }
        if (shown.isEmpty()) { contactsList.addView(empty(getString(R.string.dialer_no_contacts))); return }
        shown.take(120).forEach { contact ->
            contactsList.addView(listRow(
                contact.name, contact.number, INK,
                onClick = { dial(contact.number) },
                onLong = { putNumber(contact.number) }
            ))
        }
    }

    private fun fillRecents() {
        recentsList.removeAllViews()
        if (!granted(Manifest.permission.READ_CALL_LOG)) {
            recentsList.addView(ask(getString(R.string.dialer_allow_recents), getString(R.string.dialer_permission_note)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQ_RECENTS)
            })
            return
        }
        Thread {
            val rows = ArrayList<Recent>()
            runCatching {
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                    null, null, "${CallLog.Calls.DATE} DESC"
                )?.use { cursor ->
                    val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                    val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    while (cursor.moveToNext() && rows.size < 80) {
                        rows += Recent(
                            cursor.getString(nameIndex).orEmpty(),
                            cursor.getString(numberIndex).orEmpty(),
                            cursor.getInt(typeIndex),
                            cursor.getLong(dateIndex)
                        )
                    }
                }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                recentsList.removeAllViews()
                if (rows.isEmpty()) recentsList.addView(empty(getString(R.string.dialer_no_calls)))
                rows.forEach { recentsList.addView(recentRow(it)) }
            }
        }.start()
    }

    private fun recentRow(recent: Recent): View {
        val label = recent.name.ifBlank { recent.number.ifBlank { getString(R.string.call_unknown) } }
        val missed = recent.type == CallLog.Calls.MISSED_TYPE || recent.type == CallLog.Calls.REJECTED_TYPE
        val whenText = DateUtils.getRelativeTimeSpanString(
            recent.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        return listRow(
            label, "${typeLabel(recent.type)} · $whenText", if (missed) RED else INK,
            onClick = { dial(recent.number) },
            onLong = { putNumber(recent.number) }
        )
    }

    private fun typeLabel(type: Int): String = when (type) {
        CallLog.Calls.MISSED_TYPE -> getString(R.string.call_log_missed)
        CallLog.Calls.OUTGOING_TYPE -> getString(R.string.call_log_outgoing)
        CallLog.Calls.REJECTED_TYPE -> getString(R.string.call_log_rejected)
        CallLog.Calls.BLOCKED_TYPE -> getString(R.string.call_log_blocked)
        CallLog.Calls.VOICEMAIL_TYPE -> getString(R.string.call_log_voicemail)
        else -> getString(R.string.call_log_incoming)
    }

    private fun listRow(
        title: String,
        detail: String,
        titleColor: Int,
        onClick: () -> Unit,
        onLong: (() -> Unit)? = null
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(11f), dp(16f), dp(11f))
            background = DotUi.pill(this@DialerActivity, PILL, 18f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(titleColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(TextView(this).apply {
            text = detail.uppercase()
            textSize = 10f
            letterSpacing = 0.08f
            typeface = DotUi.doto(this@DialerActivity)
            setTextColor(MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6f) }
        return row
    }

    private fun empty(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        typeface = DotUi.doto(this@DialerActivity)
        textSize = 11f
        letterSpacing = 0.1f
        gravity = Gravity.CENTER
        setTextColor(DIM)
        setPadding(0, dp(26f), 0, dp(26f))
    }

    private fun ask(title: String, note: String, onAsk: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title.uppercase()
            typeface = DotUi.doto(this@DialerActivity)
            textSize = 12f
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setTextColor(INK)
            background = DotUi.pill(this@DialerActivity, PILL, 22f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onAsk() }
        }, LinearLayout.LayoutParams(-1, dp(48f)))
        box.addView(TextView(this).apply {
            text = note
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(12f), dp(6f), 0)
        })
        return box
    }

    private fun putNumber(value: String) {
        number.setLength(0)
        number.append(value)
        refreshNumber()
        show(PAGE_KEYPAD)
    }

    // ---------------------------------------------------------------- calling

    private fun isDefaultDialer(): Boolean =
        getSystemService(TelecomManager::class.java)?.defaultDialerPackage == packageName

    private fun requestDialerRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roles = getSystemService(RoleManager::class.java)
                if (roles?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE)
                }
            } else {
                startActivityForResult(
                    Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName),
                    REQ_ROLE
                )
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.dialer_role_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun dial(raw: String) {
        val target = raw.trim()
        if (target.isEmpty()) {
            Toast.makeText(this, R.string.dialer_need_number, Toast.LENGTH_SHORT).show()
            return
        }
        if (!granted(Manifest.permission.CALL_PHONE)) {
            pendingCall = target
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            return
        }
        val uri = Uri.fromParts("tel", target, null)
        try {
            if (isDefaultDialer()) {
                val telecom = getSystemService(TelecomManager::class.java) ?: error("No telecom service")
                telecom.placeCall(uri, Bundle())
            } else {
                // Not the phone app yet: hand the number to whichever dialer is, so calls still work.
                startActivity(Intent(Intent.ACTION_CALL, uri))
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.dialer_call_failed, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.dialer_call_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Deprecated("Platform permission result callback is sufficient here.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val allowed = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_CALL -> {
                val next = pendingCall
                pendingCall = null
                if (allowed && next != null) dial(next)
            }
            REQ_RECENTS -> if (allowed) fillRecents()
            REQ_CONTACTS -> if (allowed) fillContacts(search.text.toString())
        }
    }

    // ---------------------------------------------------------------- data

    private data class Contact(val name: String, val number: String)
    private data class Recent(val name: String, val number: String, val type: Int, val date: Long)

    private companion object {
        const val PAGE_KEYPAD = 0
        const val PAGE_RECENTS = 1
        const val PAGE_CONTACTS = 2
        const val REQ_ROLE = 21
        const val REQ_CALL = 22
        const val REQ_CONTACTS = 23
        const val REQ_RECENTS = 24

        val BG = Color.rgb(5, 6, 8)
        val PILL = Color.rgb(18, 20, 23)
        val PILL_ON = Color.rgb(43, 47, 52)
        val INK = Color.WHITE
        val MUTED = Color.rgb(140, 147, 154)
        val DIM = Color.rgb(96, 103, 110)
        val GREEN = Color.rgb(102, 232, 161)
        val RED = Color.rgb(229, 83, 74)
    }

    /** Dotted orbital artwork: the player's dot language, without any media controls. */
    private inner class DotOrbitView : View(this@DialerActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val scale = density
            val rings = listOf(9f to 12, 18f to 20, 27f to 28)
            rings.forEachIndexed { ringIndex, (radiusDp, count) ->
                for (i in 0 until count) {
                    val angle = (Math.PI * 2.0 * i / count) - Math.PI / 2.0
                    val radius = radiusDp * scale
                    val x = cx + kotlin.math.cos(angle).toFloat() * radius
                    val y = cy + kotlin.math.sin(angle).toFloat() * radius
                    val alpha = when {
                        ringIndex == 2 && i % 4 == 0 -> 150
                        ringIndex == 1 && i % 5 == 0 -> 100
                        else -> 34
                    }
                    paint.color = Color.argb(alpha, 231, 238, 242)
                    canvas.drawCircle(x, y, (if (ringIndex == 0) 2f else 1.3f) * scale, paint)
                }
            }
            paint.color = GREEN
            canvas.drawCircle(cx, cy, 2.2f * scale, paint)
        }
    }
}
