package com.dotmusic.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Dot Phone: keypad, recents, contacts and stars, in the app's liquid-glass and dot-matrix look.
 * Every contact can be given a photo of your own, which becomes the spinning dot record on the
 * call screen.
 */
class DialerActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var pages: FrameLayout
    private lateinit var keypadPage: View
    private lateinit var recentsPage: View
    private lateinit var contactsPage: View
    private lateinit var starsPage: View
    private lateinit var display: TextView
    private lateinit var matched: TextView
    private lateinit var avatar: ImageView
    private lateinit var callButton: TextView
    private lateinit var defaultBanner: LinearLayout
    private lateinit var recentsList: LinearLayout
    private lateinit var contactsList: LinearLayout
    private lateinit var starsGrid: LinearLayout
    private lateinit var search: EditText
    private lateinit var badge: TextView
    private val tabViews = ArrayList<TextView>()

    private val number = StringBuilder()
    private var page = PAGE_KEYPAD
    private var groups: List<ContactGroup>? = null
    private var recents: List<Contacts.Recent>? = null
    private var nameToken = 0
    private var pendingCall: String? = null
    private var sheet: View? = null
    private var pickingFor: ContactGroup? = null
    private var bottomInset = 0
    private val systemPhotos = android.util.LruCache<String, Bitmap>(48)

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = Glass.px(this, v)
    private fun enabled(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Glass.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(buildUi())
        showPage(PAGE_KEYPAD)
        readIntent(intent)
        if (!Store.onboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        if (Store.missedNotifications(this)) MissedCalls.start(this)
        updateBadge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        MissedCalls.appVisible = true
        if (::defaultBanner.isInitialized) {
            updateDefaultBanner()
            refreshCurrentPage()
        }
        updateBadge()
        if (MissedCalls.countNow > 0) MissedCalls.opened(this)
    }

    override fun onStop() {
        super.onStop()
        MissedCalls.appVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        MissedCalls.stop(this)
    }

    override fun onBackPressed() {
        when {
            sheet != null -> closeSheet()
            page != PAGE_KEYPAD -> showPage(PAGE_KEYPAD)
            else -> super.onBackPressed()
        }
    }

    private fun readIntent(intent: Intent?) {
        val raw = intent?.data?.schemeSpecificPart ?: return
        if (raw.isBlank()) return
        number.setLength(0)
        number.append(raw)
        refreshNumber()
    }

    // ---------------------------------------------------------------- scaffolding

    private fun buildUi(): View {
        root = FrameLayout(this)
        root.addView(AuraBackdrop(this), FrameLayout.LayoutParams(-1, -1))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
            @Suppress("DEPRECATION")
            val top = maxOf(insets.systemWindowInsetTop, cutout)
            @Suppress("DEPRECATION")
            bottomInset = insets.systemWindowInsetBottom
            content.setPadding(dp(18f), top + dp(8f), dp(18f), bottomInset + dp(10f))
            insets
        }

        content.addView(header(), LinearLayout.LayoutParams(-1, dp(44f)))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Glass.panel(this@DialerActivity, 24f)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        tabs.addView(tab(getString(R.string.tab_keypad), PAGE_KEYPAD), LinearLayout.LayoutParams(0, dp(36f), 1f))
        tabs.addView(tab(getString(R.string.tab_recents), PAGE_RECENTS), LinearLayout.LayoutParams(0, dp(36f), 1f))
        tabs.addView(tab(getString(R.string.tab_people), PAGE_CONTACTS), LinearLayout.LayoutParams(0, dp(36f), 1f))
        tabs.addView(tab(getString(R.string.tab_stars), PAGE_STARS), LinearLayout.LayoutParams(0, dp(36f), 1f))
        content.addView(tabs, LinearLayout.LayoutParams(-1, dp(44f)).apply { topMargin = dp(10f) })

        keypadPage = buildKeypad()
        recentsPage = buildListPage { recentsList = it }
        contactsPage = buildContactsPage()
        starsPage = buildListPage { starsGrid = it }
        pages = FrameLayout(this)
        pages.addView(keypadPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(recentsPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(contactsPage, FrameLayout.LayoutParams(-1, -1))
        pages.addView(starsPage, FrameLayout.LayoutParams(-1, -1))
        content.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12f) })
        return root
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(Glass.text(this, "DOT", 20f, Glass.INK, dot = true, spacing = 0.14f))
        title.addView(Glass.label(this, getString(R.string.app_tagline), Glass.DIM))
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val settings = TextView(this).apply {
            text = "⋯"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Glass.INK)
            background = Glass.panel(this@DialerActivity, 999f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.settings_title)
            setOnClickListener {
                Glass.tap(this)
                startActivity(Intent(this@DialerActivity, SettingsActivity::class.java))
            }
        }
        right.addView(settings, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        badge = TextView(this).apply {
            text = ""
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Glass.INK)
            background = Glass.fill(this@DialerActivity, Glass.RED, 999f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.missed_title)
            setOnClickListener { missedAction() }
        }
        right.addView(badge, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
            setMargins(dp(6f), 0, 0, 0)
        })
        row.addView(right, LinearLayout.LayoutParams(0, -2).apply { gravity = Gravity.END })
        return row
    }

    private fun tab(label: String, index: Int) = TextView(this).apply {
        text = label.uppercase()
        typeface = Glass.doto(this@DialerActivity)
        textSize = 10f
        letterSpacing = 0.1f
        gravity = Gravity.CENTER
        setTextColor(Glass.MUTED)
        isClickable = true
        isFocusable = true
        setOnClickListener { Glass.tap(this); showPage(index) }
        tabViews += this
    }

    private fun showPage(index: Int) {
        page = index
        keypadPage.visibility = if (index == PAGE_KEYPAD) View.VISIBLE else View.GONE
        recentsPage.visibility = if (index == PAGE_RECENTS) View.VISIBLE else View.GONE
        contactsPage.visibility = if (index == PAGE_CONTACTS) View.VISIBLE else View.GONE
        starsPage.visibility = if (index == PAGE_STARS) View.VISIBLE else View.GONE
        tabViews.forEachIndexed { i, view ->
            val on = i == index
            view.setTextColor(if (on) Glass.INK else Glass.MUTED)
            view.background = if (on) Glass.panel(this, 20f, strength = 1.9f) else null
        }
        refreshCurrentPage()
    }

    private fun refreshCurrentPage() = when (page) {
        PAGE_RECENTS -> fillRecents()
        PAGE_CONTACTS -> fillPeople(search.text.toString())
        PAGE_STARS -> fillStars()
        else -> Unit
    }

    // ---------------------------------------------------------------- keypad

    private fun buildKeypad(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.panel(this@DialerActivity, 30f)
            setPadding(dp(16f), dp(10f), dp(8f), dp(10f))
        }
        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        card.addView(avatar, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { rightMargin = dp(12f) })

        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        display = Glass.text(this, getString(R.string.enter_number), 29f, Glass.DIM)
        display.typeface = Glass.light(this)
        display.maxLines = 1
        display.ellipsize = TextUtils.TruncateAt.START
        display.contentDescription = getString(R.string.phone_number)
        matched = Glass.text(this, "", 10f, Glass.GREEN, dot = true, spacing = 0.12f)
        matched.maxLines = 1
        matched.ellipsize = TextUtils.TruncateAt.END
        stack.addView(display)
        stack.addView(matched, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2f) })
        card.addView(stack, LinearLayout.LayoutParams(0, -2, 1f))

        val erase = TextView(this).apply {
            text = "⌫"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Glass.MUTED)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.delete_digit)
            setOnClickListener {
                if (number.isNotEmpty()) {
                    Glass.tap(this)
                    number.deleteCharAt(number.lastIndex)
                    refreshNumber()
                }
            }
            setOnLongClickListener {
                number.setLength(0)
                refreshNumber()
                true
            }
        }
        card.addView(erase, LinearLayout.LayoutParams(dp(46f), dp(46f)))
        page.addView(card, LinearLayout.LayoutParams(-1, dp(76f)))

        val keypad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val keys = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to "")
        )
        keys.forEach { rowKeys ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowKeys.forEach { (digit, letters) ->
                row.addView(key(digit, letters),
                    LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) })
            }
            keypad.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        page.addView(keypad, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8f) })

        defaultBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Glass.panel(this@DialerActivity, 22f)
            isClickable = true
            setOnClickListener { requestDialerRole() }
            setPadding(dp(12f), 0, dp(12f), 0)
        }
        defaultBanner.addView(Glass.label(this, getString(R.string.make_default), Glass.GREEN))
        page.addView(defaultBanner, LinearLayout.LayoutParams(-1, dp(38f)).apply { topMargin = dp(8f) })

        callButton = TextView(this).apply {
            text = getString(R.string.call).uppercase()
            typeface = Glass.doto(this@DialerActivity)
            textSize = 15f
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(4, 20, 13))
            background = Glass.fill(this@DialerActivity, Glass.GREEN, 30f)
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.call)
            setOnClickListener { Glass.tap(this); dial(number.toString()) }
        }
        page.addView(callButton, LinearLayout.LayoutParams(-1, dp(56f)).apply { topMargin = dp(8f) })
        return page
    }

    private fun key(digit: String, letters: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Glass.panel(this@DialerActivity, 22f)
        isClickable = true
        isFocusable = true
        addView(Glass.text(this@DialerActivity, digit, 25f, Glass.INK).apply {
            gravity = Gravity.CENTER
            typeface = Glass.light(this@DialerActivity)
        })
        if (letters.isNotEmpty()) addView(Glass.text(this@DialerActivity, letters, 8f, Glass.MUTED, dot = true, spacing = 0.14f).apply {
            gravity = Gravity.CENTER
        })
        setOnClickListener { tap(this, digit) }
        if (digit == "0") setOnLongClickListener { tap(this, "+"); true }
    }

    private fun tap(key: View, digit: String) {
        Glass.tap(key)
        number.append(digit)
        refreshNumber()
    }

    private fun refreshNumber() {
        val text = number.toString()
        display.text = if (text.isEmpty()) getString(R.string.enter_number) else text
        display.setTextColor(if (text.isEmpty()) Glass.DIM else Glass.INK)
        callButton.alpha = if (text.isEmpty()) 0.5f else 1f
        avatar.visibility = View.GONE
        lookupName(text)
    }

    private fun lookupName(text: String) {
        val token = ++nameToken
        matched.text = ""
        if (text.length < 3 || !enabled(Manifest.permission.READ_CONTACTS)) return
        Thread {
            val found = Contacts.nameFor(this, text)
            runOnUiThread {
                if (token != nameToken || isFinishing) return@runOnUiThread
                matched.text = found?.uppercase().orEmpty()
                if (found != null) showTypedAvatar(Store.key(text))
            }
        }.start()
    }

    private fun showTypedAvatar(key: String) {
        val bitmap = ContactPhotos.avatar(this, key, dp(44f), Store.whiteDots(this)) ?: return
        avatar.setImageBitmap(bitmap)
        avatar.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- lists

    private fun buildListPage(assign: (LinearLayout) -> Unit): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        assign(list)
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    private fun buildContactsPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        search = EditText(this).apply {
            hint = getString(R.string.search_contacts)
            setHintTextColor(Glass.DIM)
            setTextColor(Glass.INK)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            background = Glass.panel(this@DialerActivity, 24f)
            setPadding(dp(18f), 0, dp(18f), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = renderPeople(s?.toString().orEmpty())
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

    private fun fillRecents() {
        recentsList.removeAllViews()
        if (!enabled(Manifest.permission.READ_CALL_LOG)) {
            recentsList.addView(prompt(getString(R.string.allow_history), getString(R.string.permission_note)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG), REQ_RECENTS)
            })
            return
        }
        val cached = recents
        if (cached != null) { renderRecents(cached); return }
        Thread {
            val rows = Contacts.recents(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                recents = rows
                renderRecents(rows)
                if (rows.isEmpty()) recentsList.addView(empty(getString(R.string.no_calls)))
            }
        }.start()
    }

    private fun renderRecents(rows: List<Contacts.Recent>) {
        recentsList.removeAllViews()
        if (rows.isEmpty()) { recentsList.addView(empty(getString(R.string.no_calls))); return }
        val grouped = rows.groupBy { it.key }.mapValues { it.value.first() }.toList()
            .sortedByDescending { it.second.date }
        grouped.forEach { (_, recent) ->
            val count = rows.filter { it.key == recent.key }.size
            val missed = recent.type == android.provider.CallLog.Calls.MISSED_TYPE ||
                recent.type == android.provider.CallLog.Calls.REJECTED_TYPE
            val label = recent.name.ifBlank { recent.number }
            val detail = "${typeLabel(recent.type)}${if (count > 1) " · $count" else ""} · " +
                DateUtils.getRelativeTimeSpanString(
                    recent.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )
            recentsList.addView(row(
                avatarFor(recent.key, label, recent.photoUri, dp(44f)),
                label, detail,
                if (missed) Glass.RED else Glass.INK,
                onClick = { dial(recent.number) },
                onLong = { putNumber(recent.number) }
            ))
        }
    }

    private fun fillPeople(query: String) {
        contactsList.removeAllViews()
        if (!enabled(Manifest.permission.READ_CONTACTS)) {
            contactsList.addView(prompt(getString(R.string.allow_contacts), getString(R.string.permission_note)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            })
            return
        }
        val cached = groups
        if (cached != null) { renderPeople(query); return }
        Thread {
            val found = Contacts.contacts(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                groups = found
                renderPeople(query)
            }
        }.start()
    }

    private fun renderPeople(query: String) {
        contactsList.removeAllViews()
        val all = groups ?: return
        val needle = query.trim()
        val digits = needle.filter { it.isDigit() }
        val shown = all.filter {
            needle.isEmpty() || it.name.contains(needle, true) ||
                (digits.isNotEmpty() && it.numbers.any { num -> num.filter { c -> c.isDigit() }.contains(digits) })
        }
        if (shown.isEmpty()) { contactsList.addView(empty(getString(R.string.no_contacts))); return }
        shown.take(80).forEach { group ->
            val numbersText = group.numbers.joinToString(", ") { it }
            contactsList.addView(row(
                avatarFor(group.key, group.name, group.photoUri, dp(44f)),
                group.name, numbersText, Glass.INK,
                onClick = { openGroup(group) },
                onLong = { dial(group.mainNumber) }
            ))
        }
    }

    private fun fillStars() {
        starsGrid.removeAllViews()
        if (!enabled(Manifest.permission.READ_CONTACTS)) {
            starsGrid.addView(prompt(getString(R.string.allow_contacts), getString(R.string.permission_note)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            })
            return
        }
        val cached = groups
        if (cached == null) {
            Thread {
                val found = Contacts.contacts(this)
                runOnUiThread { if (!isFinishing) { groups = found; fillStars() } }
            }.start()
            return
        }
        val starred = Store.starred(this)
        val favourites = cached.filter { starred.contains(it.key) }
        if (favourites.isEmpty()) {
            starsGrid.addView(empty(getString(R.string.no_stars)))
            return
        }
        favourites.chunked(4).forEach { chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { group ->
                line.addView(starCell(group), LinearLayout.LayoutParams(0, dp(96f), 1f))
            }
            repeat(4 - chunk.size) { line.addView(View(this), LinearLayout.LayoutParams(0, dp(96f), 1f)) }
            starsGrid.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun starCell(group: ContactGroup) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        addView(ImageView(this@DialerActivity).apply {
            setImageBitmap(avatarFor(group.key, group.name, group.photoUri, dp(62f)))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(62f), dp(62f)))
        addView(Glass.text(this@DialerActivity, group.name.substringBefore(' '), 10f, Glass.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        setOnClickListener { openGroup(group) }
        setOnLongClickListener { dial(group.mainNumber); true }
    }

    private fun row(
        leading: Bitmap,
        title: String,
        detail: String,
        titleColor: Int,
        onClick: () -> Unit,
        onLong: () -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.panel(this@DialerActivity, 24f)
            setPadding(dp(12f), dp(10f), dp(14f), dp(10f))
            isClickable = true
            isFocusable = true
            setOnClickListener { Glass.tap(this); onClick() }
            setOnLongClickListener { Glass.tap(this); onLong(); true }
        }
        box.addView(ImageView(this).apply {
            setImageBitmap(leading)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { rightMargin = dp(12f) })
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(Glass.text(this@DialerActivity, title, 15f, titleColor).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        stack.addView(Glass.text(this@DialerActivity, detail.uppercase(), 9f, Glass.MUTED, dot = true, spacing = 0.08f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        box.addView(stack, LinearLayout.LayoutParams(0, -2, 1f))
        box.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) }
        return box
    }

    private fun avatarFor(key: String, name: String, photoUri: String?, sizePx: Int): Bitmap {
        val mono = Store.whiteDots(this)
        ContactPhotos.avatar(this, key, sizePx, mono)?.let { return it }
        if (!photoUri.isNullOrBlank()) {
            val cacheKey = "$photoUri|$sizePx|$mono"
            systemPhotos.get(cacheKey)?.let { return it }
            ContactPhotos.decode(this, Uri.parse(photoUri))?.let { source ->
                val dots = ContactPhotos.dotsOf(source, sizePx, mono)
                systemPhotos.put(cacheKey, dots)
                return dots
            }
        }
        return initialAvatar(name.trim().firstOrNull()?.uppercase().orEmpty().ifEmpty { "#" }, sizePx, Glass.BLUE)
    }

    private fun initialAvatar(letter: String, sizePx: Int, tint: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = Glass.withAlpha(Color.WHITE, 52)
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx * 0.46f, ring)
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Glass.doto(this@DialerActivity)
            textSize = sizePx * 0.42f
            color = Glass.withAlpha(tint, 235)
            textAlign = Paint.Align.CENTER
        }
        val metrics = glyph.fontMetrics
        canvas.drawText(letter, sizePx / 2f, sizePx / 2f - (metrics.ascent + metrics.descent) / 2f, glyph)
        return out
    }

    private fun prompt(title: String, note: String, onAsk: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title.uppercase()
            typeface = Glass.doto(this@DialerActivity)
            textSize = 11f
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setTextColor(Glass.INK)
            background = Glass.panel(this@DialerActivity, 24f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onAsk() }
        }, LinearLayout.LayoutParams(-1, dp(50f)))
        box.addView(Glass.text(this, note, 12f, Glass.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(12f), dp(6f), 0)
        })
        return box
    }

    private fun empty(text: String) = Glass.label(this, text, Glass.DIM).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(28f), 0, dp(28f))
    }

    private fun typeLabel(type: Int): String = when (type) {
        android.provider.CallLog.Calls.MISSED_TYPE -> getString(R.string.log_missed)
        android.provider.CallLog.Calls.OUTGOING_TYPE -> getString(R.string.log_outgoing)
        android.provider.CallLog.Calls.REJECTED_TYPE -> getString(R.string.log_declined)
        android.provider.CallLog.Calls.BLOCKED_TYPE -> getString(R.string.log_blocked)
        android.provider.CallLog.Calls.VOICEMAIL_TYPE -> getString(R.string.log_voicemail)
        else -> getString(R.string.log_incoming)
    }

    private fun putNumber(value: String) {
        number.setLength(0)
        number.append(value)
        refreshNumber()
        showPage(PAGE_KEYPAD)
    }

    // ---------------------------------------------------------------- the contact card

    private fun openGroup(group: ContactGroup) {
        closeSheet()
        val overlay = FrameLayout(this).apply {
            background = android.graphics.drawable.ColorDrawable(Color.argb(168, 3, 4, 6))
            isClickable = true
            setOnClickListener { closeSheet() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.panel(this@DialerActivity, 32f, strength = 1.4f)
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            isClickable = true
            setOnClickListener { }
        }

        val record = DotRecord(this).apply {
            showInfo = false
            showTitle = false
            mono = Store.whiteDots(this@DialerActivity)
            accent = Glass.GREEN
            setTitle(group.name)
        }
        record.setArt(ContactPhotos.load(this, group.key), animate = false)
        card.addView(record, LinearLayout.LayoutParams(-1, dp(170f)))

        card.addView(Glass.text(this, group.name, 20f, Glass.INK, medium = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) })
        card.addView(Glass.text(this, group.numbers.joinToString(", ") { it }, 11f, Glass.MUTED, dot = true, spacing = 0.1f).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(pill(getString(R.string.call), Glass.GREEN, Color.rgb(4, 20, 13), filled = true) {
            dial(group.mainNumber)
        }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { setMargins(dp(4f), dp(14f), dp(4f), 0) })
        actions.addView(pill(getString(R.string.message), Color.WHITE, Glass.INK) {
            message(group.mainNumber)
        }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { setMargins(dp(4f), dp(14f), dp(4f), 0) })
        card.addView(actions, LinearLayout.LayoutParams(-1, -2))

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val starred = Store.starred(this).contains(group.key)
        tools.addView(tool(if (starred) getString(R.string.unstar) else getString(R.string.star)) {
            Store.toggleStar(this@DialerActivity, group.key)
            openGroup(group)
        }, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { setMargins(dp(4f), dp(10f), dp(4f), 0) })
        tools.addView(tool(getString(R.string.choose_photo)) {
            pickingFor = group
            val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runCatching { startActivityForResult(Intent.createChooser(pick, getString(R.string.choose_photo)), REQ_PHOTO) }
        }, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { setMargins(dp(4f), dp(10f), dp(4f), 0) })
        tools.addView(tool(getString(R.string.copy)) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.phone_number), group.mainNumber))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(44f), 1f).apply { setMargins(dp(4f), dp(10f), dp(4f), 0) })
        card.addView(tools, LinearLayout.LayoutParams(-1, -2))

        if (ContactPhotos.has(this, group.key)) {
            card.addView(tool(getString(R.string.remove_photo), Glass.RED) {
                ContactPhotos.clear(this@DialerActivity, group.key)
                openGroup(group)
            }, LinearLayout.LayoutParams(-1, dp(42f)).apply { setMargins(dp(4f), dp(8f), dp(4f), 0) })
        }

        overlay.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(14f), 0, dp(14f), bottomInset + dp(14f))
        })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        card.alpha = 0f
        card.translationY = dp(36f).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        sheet = overlay

        // The address-book photo is turned into dots off the main thread, then fades in.
        val photoUri = group.photoUri
        if (!ContactPhotos.has(this, group.key) && !photoUri.isNullOrBlank()) {
            Thread {
                val source = ContactPhotos.decode(this, Uri.parse(photoUri))
                runOnUiThread {
                    if (sheet === overlay && source != null) record.setArt(source)
                }
            }.start()
        }
    }

    private fun pill(label: String, fill: Int, ink: Int, filled: Boolean = false, action: () -> Unit) =
        TextView(this).apply {
            text = label.uppercase()
            typeface = Glass.doto(this@DialerActivity)
            textSize = 12f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(ink)
            background = if (filled) Glass.fill(this@DialerActivity, fill, 26f)
            else Glass.panel(this@DialerActivity, 26f)
            isClickable = true
            isFocusable = true
            setOnClickListener { Glass.tap(this); action() }
        }

    private fun tool(label: String, tint: Int = Glass.INK, action: () -> Unit) =
        TextView(this).apply {
            text = label.uppercase()
            typeface = Glass.doto(this@DialerActivity)
            textSize = 9f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(tint)
            background = Glass.panel(this@DialerActivity, 22f)
            isClickable = true
            isFocusable = true
            setOnClickListener { Glass.tap(this); action() }
        }

    private fun closeSheet() {
        val open = sheet ?: return
        sheet = null
        open.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(open) }.start()
    }

    private fun updateBadge() {
        if (!::badge.isInitialized) return
        val n = MissedCalls.countNow
        val show = n > 0 && Store.missedNotifications(this) && Store.badgeEnabled(this)
        badge.visibility = if (show) View.VISIBLE else View.GONE
        badge.text = if (show) n.toString() else ""
    }

    private fun missedAction() {
        val number = MissedCalls.numberNow ?: return
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
        MissedCalls.clear(this)
        updateBadge()
    }

    private fun message(number: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, R.string.no_messaging, Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Platform result callback is enough for one photo picker.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val group = pickingFor ?: return
        val bitmap = ContactPhotos.decode(this, uri)
        if (bitmap == null) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val saved = ContactPhotos.save(this, group.key, bitmap)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (!saved) Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
                refreshCurrentPage()
                openGroup(group)
            }
        }.start()
    }

    // ---------------------------------------------------------------- calling

    private fun isDefaultDialer(): Boolean =
        getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage == packageName

    private fun updateDefaultBanner() {
        defaultBanner.visibility = if (isDefaultDialer()) View.GONE else View.VISIBLE
    }

    private fun requestDialerRole() {
        try {
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
        } catch (_: Exception) {
            Toast.makeText(this, R.string.role_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun dial(raw: String) {
        val target = raw.trim()
        if (target.isEmpty()) {
            Toast.makeText(this, R.string.need_number, Toast.LENGTH_SHORT).show()
            return
        }
        if (!enabled(Manifest.permission.CALL_PHONE)) {
            pendingCall = target
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            return
        }
        closeSheet()
        val uri = Uri.fromParts("tel", target, null)
        try {
            if (isDefaultDialer()) {
                val telecom = getSystemService(android.telecom.TelecomManager::class.java) ?: error("No telecom")
                telecom.placeCall(uri, Bundle())
            } else {
                startActivity(Intent(Intent.ACTION_CALL, uri))
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.call_failed, Toast.LENGTH_LONG).show()
        }
    }

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
            REQ_RECENTS -> if (allowed) { recents = null; fillRecents() }
            REQ_CONTACTS -> if (allowed) { groups = null; refreshCurrentPage() }
            REQ_RECENTS -> if (allowed) { recents = null; fillRecents(); if (Store.missedNotifications(this)) MissedCalls.start(this) }
        }
    }

    private companion object {
        const val PAGE_KEYPAD = 0
        const val PAGE_RECENTS = 1
        const val PAGE_CONTACTS = 2
        const val PAGE_STARS = 3
        const val REQ_ROLE = 31
        const val REQ_CALL = 32
        const val REQ_CONTACTS = 33
        const val REQ_RECENTS = 34
        const val REQ_PHOTO = 35
    }
}
